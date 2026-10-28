/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.tif

import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.net.Uri
import android.provider.BaseColumns
import android.util.Log
import app.opentv.core.ServiceLocator
import app.opentv.data.model.shownName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Publishes OpenTV's live channels and their guide into the system **TV Input Framework** so they
 * show up *inside* Live Channels, in the same grid as the antenna and broadcast inputs.
 *
 * ## Why this exists at all
 *
 * OpenTV's own screens are a self-contained player: its channels live in its Room database and are
 * never visible to anything else. Live Channels only ever shows what is registered as a *TV input*
 * via [TvContract]. The one and only way to get IPTV sitting next to the antenna — rather than in a
 * separate app the user has to switch to — is to hand our channels and EPG to that provider under
 * our own input id. We cannot go the other way (pull the antenna into OpenTV): the physical tuner
 * is readable only by the system TV app, never by a third-party package.
 *
 * ## Strategy: diff, not wipe-and-reinsert
 *
 * Each sync keeps the existing rows and only updates changed channels, inserts new ones and deletes
 * stale ones, matched on our internal channel id (stored in COLUMN_INTERNAL_PROVIDER_DATA). This
 * preserves the framework row ids and, crucially, never empties the input mid-sync: on this
 * project's target boxes (TCL "tguard") the process is killed the instant it backgrounds, and the
 * old wipe-then-reinsert left the guide with zero channels whenever the re-insert never ran. Stale
 * channels are removed last, so an interrupted sync keeps extra (still-valid) channels, never none.
 *
 * shortcut: per-channel programme replace (delete + bulkInsert) each sync; fine for typical live
 * lists (hundreds), revisit if someone pushes tens of thousands of channels into the guide.
 */
object TifSync {

    private const val TAG = "TifSync"

    /** How far ahead to publish programmes per channel. Live Channels only needs "now + next few". */
    private const val PROGRAMMES_PER_CHANNEL = 50

    /** One sync at a time: the app-start trigger and the periodic worker can both fire this. */
    private val lock = Mutex()

    /** The stable input id the framework knows us by, derived from the service component. */
    fun inputId(context: Context): String =
        TvContract.buildInputId(ComponentName(context, OpenTvInputService::class.java))

    /** True on boxes that actually have Live Channels / a TIF. Phones and tablets return false. */
    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV)

    /**
     * Mirror OpenTV's visible live channels + guide into the TV provider. Safe to call from
     * anywhere; it no-ops off a TIF device and serialises concurrent callers. Never throws — a
     * provider that rejects a write is logged, not fatal, because this is a nice-to-have surface
     * layered on top of the app, not the app itself.
     */
    suspend fun sync(context: Context): Int = lock.withLock {
        if (!isSupported(context)) return 0

        val appContext = context.applicationContext
        val graph = ServiceLocator.get(appContext)
        val resolver = appContext.contentResolver
        val inputId = inputId(appContext)
        val now = System.currentTimeMillis()

        // channels table is live channels only (VOD/series live in their own tables). Hidden
        // channels are ones the user chose not to see, so they stay out of the guide too. Channels
        // generated from Stremio add-ons are already stored here as a synthetic source (see
        // CatalogRepository.syncStremioAddons), so there is nothing add-on-specific to do here.
        val channels = runCatching { graph.database.channels().allForMatching() }
            .getOrElse { Log.w(TAG, "channel read failed", it); return 0 }
            .filter { !it.hidden }

        // All the provider writes run on IO, never the caller's thread. The setup activity calls
        // this from its main-thread lifecycle scope, and doing thousands of inserts there blocks the
        // UI long enough that this aggressive TV (TCL "tguard") backgrounds and kills the process
        // mid-sync — exactly what left the input with zero channels.
        withContext(Dispatchers.IO) {
            runCatching {
                // Read the rows already published for this input, keyed by our internal channel id, so
                // we can update/keep them in place instead of wiping everything up front (see class doc).
                val existing = HashMap<String, Long>()
                resolver.query(
                    channelsForInput(inputId),
                    arrayOf(BaseColumns._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA),
                    null, null, null,
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                    val keyIndex = cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)
                    while (cursor.moveToNext()) {
                        val key = cursor.getBlob(keyIndex)?.let { String(it) } ?: continue
                        existing[key] = cursor.getLong(idIndex)
                    }
                }

                val seen = HashSet<String>()
                var number = 0
                var published = 0
                channels.forEach { channel ->
                    number++
                    val key = channel.id.toString()
                    seen += key
                    val channelValues = channelRow(inputId, channel.number ?: number, channel.shownName)
                        .apply {
                            // Our own id, so onTune can map the framework's channel Uri back to a Channel
                            // row. COLUMN_INTERNAL_PROVIDER_DATA (a BLOB) exists since API 21; the tidier
                            // COLUMN_INTERNAL_PROVIDER_ID only arrived in API 26, and minSdk here is 23.
                            put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, key.toByteArray())
                        }
                    val existingRowId = existing[key]
                    val channelRowId = if (existingRowId != null) {
                        resolver.update(
                            ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, existingRowId),
                            channelValues, null, null,
                        )
                        existingRowId
                    } else {
                        val uri = resolver.insert(TvContract.Channels.CONTENT_URI, channelValues) ?: return@forEach
                        ContentUris.parseId(uri)
                    }
                    published++

                    // Replace just this channel's programmes. Scoped to one channel, so an interrupted
                    // sync only blanks that channel's guide, never the channel list itself.
                    resolver.delete(TvContract.buildProgramsUriForChannel(channelRowId), null, null)
                    val epgId = channel.epgCandidates.firstOrNull() ?: return@forEach
                    val programmes = runCatching {
                        graph.database.programmes().upcoming(epgId, now, PROGRAMMES_PER_CHANNEL)
                    }.getOrDefault(emptyList())
                    if (programmes.isEmpty()) return@forEach

                    val rows = programmes.map { p ->
                        ContentValues().apply {
                            put(TvContract.Programs.COLUMN_CHANNEL_ID, channelRowId)
                            put(TvContract.Programs.COLUMN_TITLE, p.title)
                            put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, p.startUtcMillis)
                            put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, p.endUtcMillis)
                            p.description?.let { put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, it) }
                        }
                    }.toTypedArray()
                    resolver.bulkInsert(TvContract.Programs.CONTENT_URI, rows)
                }

                // Remove channels that are no longer in OpenTV — done last so a kill mid-sync keeps
                // extra (still-valid) channels rather than losing any. Deleting a channel cascades to
                // its programmes in the TV provider, so there is no separate Programs cleanup.
                existing.forEach { (key, rowId) ->
                    if (key !in seen) {
                        resolver.delete(ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, rowId), null, null)
                    }
                }

                Log.i(TAG, "Published $published channels to Live Channels")
                published
            }.getOrElse { Log.w(TAG, "TIF sync failed", it); 0 }
        }
    }

    /** The columns every channel row shares; callers add COLUMN_INTERNAL_PROVIDER_DATA. */
    private fun channelRow(inputId: String, number: Int, name: String): ContentValues =
        ContentValues().apply {
            put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
            put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
            put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, number.toString())
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, name)
        }

    /** Channels belonging to this input. */
    private fun channelsForInput(inputId: String): Uri =
        TvContract.buildChannelsUriForInput(inputId)
}
