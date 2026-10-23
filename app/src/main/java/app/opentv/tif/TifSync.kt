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
import android.util.Log
import app.opentv.core.ServiceLocator
import app.opentv.data.model.shownName
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * ## Strategy: full replace
 *
 * Each sync wipes this input's rows and re-inserts them. Diffing (insert-new / update-changed /
 * delete-stale, matched on internal id) would preserve the row ids and any per-channel state Live
 * Channels keeps, but it is a great deal more code for a first version. A full replace is a handful
 * of lines and always correct; the only visible cost is that channels blink out and back during a
 * sync, and a hide/reorder done *inside* Live Channels is reset. Revisit if that becomes a problem.
 *
 * shortcut: full replace + per-row channel insert; fine for typical live lists (hundreds), revisit
 * if someone pushes tens of thousands of channels into the guide.
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
        // channels are ones the user chose not to see, so they stay out of the guide too.
        val channels = runCatching { graph.database.channels().allForMatching() }
            .getOrElse { Log.w(TAG, "channel read failed", it); return 0 }
            .filter { !it.hidden }

        runCatching {
            // Deleting a channel cascades to its programmes in the TV provider, so clearing this
            // input's channels clears its whole guide in one call — no separate Programs delete.
            resolver.delete(channelsForInput(inputId), null, null)

            var published = 0
            channels.forEachIndexed { index, channel ->
                val channelValues = ContentValues().apply {
                    put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
                    put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
                    put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
                    put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, (channel.number ?: index + 1).toString())
                    put(TvContract.Channels.COLUMN_DISPLAY_NAME, channel.shownName)
                    // Our own id, so onTune can map the framework's channel Uri back to a Channel row.
                    // COLUMN_INTERNAL_PROVIDER_DATA (a BLOB) exists since API 21; the tidier
                    // COLUMN_INTERNAL_PROVIDER_ID only arrived in API 26, and minSdk here is 23.
                    put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, channel.id.toString().toByteArray())
                }
                val channelUri = resolver.insert(TvContract.Channels.CONTENT_URI, channelValues) ?: return@forEachIndexed
                val channelRowId = ContentUris.parseId(channelUri)
                published++

                val epgId = channel.epgCandidates.firstOrNull() ?: return@forEachIndexed
                val programmes = runCatching {
                    graph.database.programmes().upcoming(epgId, now, PROGRAMMES_PER_CHANNEL)
                }.getOrDefault(emptyList())
                if (programmes.isEmpty()) return@forEachIndexed

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
            Log.i(TAG, "Published $published channels to Live Channels")
            published
        }.getOrElse { Log.w(TAG, "TIF sync failed", it); 0 }
    }

    /** Channels belonging to this input. */
    private fun channelsForInput(inputId: String): Uri =
        TvContract.buildChannelsUriForInput(inputId)
}
