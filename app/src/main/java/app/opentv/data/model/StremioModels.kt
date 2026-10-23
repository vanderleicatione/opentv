/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.model

import kotlinx.serialization.Serializable

/**
 * A user-added Stremio add-on: a small web service (identified by its manifest URL) that, given a
 * title, returns playable stream links. OpenTV ships with none — the user pastes the manifest URL
 * of an add-on they have configured themselves (including any debrid key, which is baked into that
 * personalised URL on the add-on's own site, never entered into OpenTV). Stored on-device only,
 * exactly like a provider credential.
 *
 * This is the same open protocol Stremio and Kodi speak; OpenTV is only a neutral client for it and
 * hosts, indexes and bundles no content of its own.
 */
@Serializable
data class StremioAddon(
    /** The manifest URL the user pasted, e.g. https://…/manifest.json. Its identity for de-dup. */
    val manifestUrl: String,
    /** Display name read from the add-on's manifest at add time. */
    val name: String,
)

/**
 * A single playable stream an add-on returned for a title. OpenTV keeps only streams that carry a
 * direct, ready-to-play [url] (what debrid-backed add-ons return); raw torrent (infoHash-only)
 * entries are dropped, because OpenTV plays URLs and deliberately does no torrenting itself.
 */
data class StremioStream(
    val url: String,
    /** A human label for the quality/source line the add-on supplied (e.g. "4K • 12GB"). */
    val title: String,
    /** Which add-on produced this, for grouping in the picker. */
    val addonName: String,
    /**
     * Request headers the add-on says this stream needs (its `behaviorHints.proxyHeaders.request`).
     * Live-TV add-ons routinely require a specific `User-Agent` (e.g. a VLC string) and the CDN
     * answers 403 without it — the usual cause of a channel that "doesn't work". Empty when the
     * add-on set none.
     */
    val headers: Map<String, String> = emptyMap(),
)

/**
 * A live channel discovered in a Stremio add-on's `tv` catalogue — the raw material for mirroring
 * an add-on's live channels into Live Channels via the TV Input Framework. Unlike [StremioStream],
 * this is only the channel's identity; its playable URL is resolved on demand at tune time with the
 * add-on's `stream` resource (see TifSync / OpenTvInputService), the same just-in-time pattern
 * Stalker uses.
 */
data class StremioLiveChannel(
    /** The add-on's own id for this channel, passed back to its `stream` resource. */
    val id: String,
    val name: String,
    val logo: String?,
    /** The add-on this came from — needed to resolve the stream later. */
    val addonManifestUrl: String,
    val addonName: String,
)
