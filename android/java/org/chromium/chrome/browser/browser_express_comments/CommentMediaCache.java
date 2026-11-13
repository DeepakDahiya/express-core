/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;

import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.File;

/**
 * Process-wide disk cache for comment and post media playback.
 *
 * Without it every ExoPlayer instance streams its bytes from the origin on
 * every bind — a reel swiped back to, an inline card scrolled past twice, an
 * audio bubble replayed all re-download in full. Media objects live under
 * versioned keys (a timestamp or UUID is part of the URL), so bytes cached
 * once are valid forever and eviction is purely an LRU space concern.
 *
 * {@link SimpleCache} refuses to share a directory between two live
 * instances, which is why everything here funnels through one singleton.
 */
public final class CommentMediaCache {
    /** LRU budget for cached media bytes. */
    private static final long MAX_CACHE_BYTES = 256L * 1024 * 1024;

    private static final String CACHE_DIR_NAME = "browser_express_media";

    private static SimpleCache sCache;

    private CommentMediaCache() {}

    private static synchronized SimpleCache getCache(Context context) {
        if (sCache == null) {
            File dir =
                    new File(
                            context.getApplicationContext().getCacheDir(),
                            CACHE_DIR_NAME);
            sCache =
                    new SimpleCache(
                            dir,
                            new LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                            new StandaloneDatabaseProvider(
                                    context.getApplicationContext()));
        }
        return sCache;
    }

    /**
     * A data-source factory that reads through the shared cache and falls
     * back to the plain upstream on any cache error, so a corrupted cache
     * entry degrades to exactly today's uncached behaviour instead of a
     * playback failure. The upstream is a {@link DefaultDataSource}, so
     * content:// and file:// URIs (a just-posted comment still uploading)
     * keep working.
     */
    public static DataSource.Factory dataSourceFactory(Context context) {
        DefaultHttpDataSource.Factory httpFactory =
                new DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true);
        DefaultDataSource.Factory upstreamFactory =
                new DefaultDataSource.Factory(
                        context.getApplicationContext(), httpFactory);
        return new CacheDataSource.Factory()
                .setCache(getCache(context))
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    /**
     * Media-source factory for {@code ExoPlayer.Builder.setMediaSourceFactory}
     * so every player built for comment/post media reads through the cache.
     */
    public static DefaultMediaSourceFactory mediaSourceFactory(Context context) {
        return new DefaultMediaSourceFactory(dataSourceFactory(context));
    }
}
