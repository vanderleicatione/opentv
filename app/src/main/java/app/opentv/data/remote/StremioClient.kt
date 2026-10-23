/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import android.util.Log
import app.opentv.data.model.StremioAddon
import app.opentv.data.model.StremioLiveChannel
import app.opentv.data.model.StremioStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A thin, read-only client for the open Stremio add-on protocol — the same protocol Stremio and
 * many Kodi add-ons speak. OpenTV ships with no add-ons and hosts no content; this only talks to
 * manifest URLs the user has pasted in themselves.
 *
 * ## What it does
 * - [fetchManifest] fetches an add-on's `manifest.json` and returns its display name if it looks
 *   like a real add-on (has an id + name), so the settings screen can validate a pasted URL.
 * - [streams] asks an add-on for the streams it offers for a title and returns only the ones with a
 *   direct playable URL. Debrid-backed add-ons resolve a title to such a URL internally (using the
 *   debrid key baked into the user's personalised manifest URL), so OpenTV never handles a debrid
 *   key and never touches a torrent — it just plays the URL the add-on hands back.
 *
 * ## Blocking on purpose
 * Methods block on the calling thread; callers already run them inside `withContext(Dispatchers.IO)`,
 * matching [TmdbClient].
 */
class StremioClient(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /**
     * Fetch and lightly validate an add-on manifest. Returns the add-on's display name, or null if
     * the URL doesn't resolve to something that looks like a Stremio manifest (id + name present).
     */
    fun fetchManifest(manifestUrl: String): String? {
        val url = manifestUrl.trim().toHttpUrlOrNull() ?: return null
        val o = get(url)?.jsonObject ?: return null
        val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val hasId = o["id"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
        return if (hasId) name else null
    }

    /**
     * The streams [addonManifestUrl] offers for a title. [type] is "movie" or "series"; [id] is the
     * IMDb id — `tt1234567` for a film, `tt1234567:1:3` for series season 1 episode 3. Only entries
     * with a direct `url` are returned (see the class note on why torrents are skipped).
     */
    fun streams(addonManifestUrl: String, addonName: String, type: String, id: String): List<StremioStream> {
        // A manifest URL is "<base>/manifest.json"; resource URLs hang off the same base.
        val base = addonManifestUrl.trim().removeSuffix("manifest.json").trimEnd('/')
        val url = "$base/stream/$type/$id.json".toHttpUrlOrNull() ?: return emptyList()
        val streams = get(url)?.jsonObject?.get("streams")?.jsonArray ?: return emptyList()
        return streams.mapNotNull { element ->
            val s = element.jsonObject
            val streamUrl = s["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null // infoHash-only (raw torrent) — skipped on purpose
            val label = (s["title"] ?: s["name"])?.jsonPrimitive?.contentOrNull
                ?.replace('\n', ' ')?.trim()?.takeIf { it.isNotBlank() } ?: "Stream"
            // behaviorHints.proxyHeaders.request — the headers the add-on wants on the stream
            // request (commonly a VLC User-Agent for live CDNs). Passed through to the player.
            val requestHeaders = (s["behaviorHints"] as? JsonObject)
                ?.get("proxyHeaders")?.let { it as? JsonObject }
                ?.get("request")?.let { it as? JsonObject }
                ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
                ?.toMap().orEmpty()
            StremioStream(url = streamUrl, title = label, addonName = addonName, headers = requestHeaders)
        }
    }

    /**
     * The live channels an add-on offers: every entry of each of its `tv` catalogues. Reads the
     * manifest for the catalogue ids, then pulls the first page of each (metas → channels). Only the
     * default page is fetched — enough for a channel list; add-ons paginate with `skip`, which we do
     * not follow here.
     *
     * shortcut: first catalogue page only, no skip-paging; revisit if an add-on's tv catalogue is
     * larger than one page and the tail matters.
     */
    fun tvChannels(addon: StremioAddon): List<StremioLiveChannel> {
        val manifestUrl = addon.manifestUrl.trim()
        val base = manifestUrl.removeSuffix("manifest.json").trimEnd('/')
        val manifest = (get(manifestUrl.toHttpUrlOrNull() ?: return emptyList()) as? JsonObject) ?: return emptyList()

        val tvCatalogIds = (manifest["catalogs"] as? JsonArray).orEmpty().mapNotNull { element ->
            val c = element as? JsonObject ?: return@mapNotNull null
            if (c["type"]?.jsonPrimitive?.contentOrNull != "tv") return@mapNotNull null
            c["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }

        // De-dup by channel id: the same channel can appear in more than one catalogue (e.g. "Todos").
        val out = LinkedHashMap<String, StremioLiveChannel>()
        for (catId in tvCatalogIds) {
            val metas = (get("$base/catalog/tv/$catId.json".toHttpUrlOrNull() ?: continue) as? JsonObject)
                ?.get("metas") as? JsonArray ?: continue
            for (element in metas) {
                val m = element as? JsonObject ?: continue
                val id = m["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                val name = m["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: id
                val logo = (m["poster"] ?: m["logo"])?.jsonPrimitive?.contentOrNull
                out.getOrPut(id) { StremioLiveChannel(id, name, logo, manifestUrl, addon.name) }
            }
        }
        return out.values.toList()
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun get(url: HttpUrl): JsonElement? {
        val request = Request.Builder().url(url).header("User-Agent", "OpenTV").build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                json.parseToJsonElement(body)
            }
        }.onFailure { Log.w(TAG, "Stremio request failed", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "StremioClient"
    }
}
