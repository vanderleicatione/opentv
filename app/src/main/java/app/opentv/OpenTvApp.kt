/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.content.Context
import app.opentv.core.LocaleUtils
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.work.SyncWorker
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OpenTvApp : Application(), ImageLoaderFactory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The app-wide Coil loader, tuned for a poster-and-logo heavy UI on a low-end TV box. The
     * default loader keeps a small memory cache and no disk cache, so scrolling back through a
     * shelf — or reopening Movies — re-downloads and re-decodes every image. Here:
     *  - a generous memory cache and a 256 MB disk cache mean art you've already seen paints from
     *    cache, instantly, instead of hitting the network;
     *  - no crossfade — an immediate swap reads as snappier on a d-pad grid than a fade, and skips
     *    a frame of blending per image;
     *  - RGB_565 for opaque art (posters/backdrops) halves the memory per bitmap, so more fits in
     *    cache; Coil keeps ARGB_8888 for anything with transparency, so channel logos are untouched.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .allowRgb565(true)
            .build()

    // So notifications and any app-context resources use the chosen language too, not just the UI.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleUtils.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        val graph = ServiceLocator.get(this)
        SyncWorker.schedule(this)

        appScope.launch {
            val prefs = getSharedPreferences("opentv", MODE_PRIVATE)
            val seen = prefs.getInt("normalizer_version", 0)
            if (seen < CatalogRepository.NORMALIZER_VERSION) {
                graph.catalogRepository.renormalizeAll()
                prefs.edit().putInt("normalizer_version", CatalogRepository.NORMALIZER_VERSION).apply()
            }
            // Runs on every launch. It is cheap when nothing is stale (feeds within their
            // refresh window are skipped), but it is what makes the free regional guide turn
            // itself on and download the first time — without waiting for the user to find
            // the refresh button. ensureFeeds + auto-enable-by-region + matcher all live here.
            graph.epgRepository.syncAll(System.currentTimeMillis(), force = false)

            // Keep the Live Channels guide in step with what the app just synced, so a box that
            // added the OpenTV input sees fresh channels/EPG on launch. No-ops off a TV device.
            // Via the foreground service, not an inline TifSync.sync(): on tguard-style boxes this
            // appScope coroutine dies the moment the launcher returns, which would cut the sync short;
            // the service's ongoing notification lets it run to completion. runCatching guards the
            // Android 12+ "can't start a FGS from the background" case (periodic worker then catches up).
            runCatching { app.opentv.tif.TifSyncService.start(this@OpenTvApp) }
        }
    }
}
