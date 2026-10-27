/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.tif

import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The TV input Live Channels talks to when the user tunes to one of OpenTV's channels.
 *
 * Live Channels owns the surface and the guide; this service's only job is: given the channel the
 * user landed on, play its stream onto that surface. It reuses the app's streaming HTTP client and
 * the same URL-resolution path the in-app player uses (so Stalker channels, which mint a URL per
 * play, still work), but keeps its *own* small [ExoPlayer] — the in-app [app.opentv.player.PlayerController]
 * is built around a Compose screen, not a bare framework [Surface], and dragging it in here would
 * couple the two for no gain.
 */
@OptIn(UnstableApi::class)
class OpenTvInputService : TvInputService() {

    override fun onCreateSession(inputId: String): Session = OpenTvSession(applicationContext)

    @OptIn(UnstableApi::class)
    private class OpenTvSession(private val context: Context) : Session(context) {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val graph = ServiceLocator.get(context)

        /**
         * One OkHttp data source whose default User-Agent is swapped per tune — the same trick the
         * in-app player uses, because many panels 403 any UA they do not recognise.
         */
        private val httpFactory = OkHttpDataSource.Factory(graph.streamingHttpClient)

        private val player: ExoPlayer =
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory)),
                )
                .build()
                .apply {
                    addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            notifyVideoAvailable()
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            Log.w(TAG, "playback error", error)
                            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                        }
                    })
                    playWhenReady = true
                }

        /** Newest tune wins: an older in-flight resolve must not clobber a channel-surf. */
        private var tuneJob: Job? = null

        override fun onSetSurface(surface: Surface?): Boolean {
            player.setVideoSurface(surface)
            return true
        }

        override fun onSetStreamVolume(volume: Float) {
            player.volume = volume
        }

        override fun onTune(channelUri: Uri): Boolean {
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
            tuneJob?.cancel()
            tuneJob = scope.launch {
                val data = internalDataOf(channelUri)
                if (data == null) {
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                    return@launch
                }
                // The channel row stores its Room `channels` id; resolve it to a playable URL. For
                // the add-on list this is already a direct URL (CatalogRepository resolved it at
                // refresh time); Stalker still mints its URL here on demand.
                val resolved: Pair<String, String>? = withContext(Dispatchers.IO) {
                    val channel = data.toLongOrNull()
                        ?.let { graph.database.channels().byId(it) } ?: return@withContext null
                    val source = graph.database.sources().byId(channel.sourceId)
                    graph.catalogRepository.resolvePlaybackUrl(channel, source) to
                        (source?.userAgent ?: Source.DEFAULT_USER_AGENT)
                }
                val (url, userAgent) = resolved ?: run {
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                    return@launch
                }

                httpFactory.setDefaultRequestProperties(mapOf("User-Agent" to userAgent))
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
            }
            return true
        }

        // Captions and parental controls are handled by the stream/player itself; nothing to do.
        override fun onSetCaptionEnabled(enabled: Boolean) {}

        override fun onRelease() {
            tuneJob?.cancel()
            player.release()
        }

        /** Read back the origin key we stored in the channel row at sync time (see TifSync). */
        private fun internalDataOf(channelUri: Uri): String? =
            context.contentResolver.query(
                channelUri,
                arrayOf(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) return null
                c.getBlob(0)?.let { String(it) }
            }
    }

    private companion object {
        const val TAG = "OpenTvInputService"
    }
}
