/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.source.smoothstreaming;

import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.Window;
import com.google.android.exoplayer2.source.AdaptiveMediaSourceEventListener;
import com.google.android.exoplayer2.source.AdaptiveMediaSourceEventListener.EventDispatcher;
import com.google.android.exoplayer2.source.MediaPeriod;
import com.google.android.exoplayer2.source.MediaPeriod.Callback;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.SinglePeriodTimeline;
import com.google.android.exoplayer2.source.smoothstreaming.manifest.SsManifest;
import com.google.android.exoplayer2.source.smoothstreaming.manifest.SsManifest.StreamElement;
import com.google.android.exoplayer2.source.smoothstreaming.manifest.SsManifestParser;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.Loader;
import com.google.android.exoplayer2.upstream.ParsingLoadable;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;
import java.io.IOException;
import java.util.ArrayList;

/**
 * A SmoothStreaming {@link MediaSource}.
 */
public final class SsMediaSource implements MediaSource,
    Loader.Callback<ParsingLoadable<SsManifest>> {

  /**
   * The default minimum number of times to retry loading data prior to failing.
   */
  public static final int DEFAULT_MIN_LOADABLE_RETRY_COUNT = 3;
  /**
   * The offset in milliseconds subtracted from the live edge position when calculating the default
   * position returned by {@link #getDefaultStartPosition(int)}.
   */
  private static final long LIVE_EDGE_OFFSET_MS = 30000;

  private static final int MINIMUM_MANIFEST_REFRESH_PERIOD_MS = 5000;

  private final Uri manifestUri;
  private final DataSource.Factory dataSourceFactory;
  private final SsChunkSource.Factory chunkSourceFactory;
  private final int minLoadableRetryCount;
  private final EventDispatcher eventDispatcher;
  private final SsManifestParser manifestParser;
  private final ArrayList<SsMediaPeriod> mediaPeriods;

  private MediaSource.Listener sourceListener;
  private DataSource manifestDataSource;
  private Loader manifestLoader;

  private long manifestLoadStartTimestamp;
  private SsManifest manifest;
  private Window window;

  private Handler manifestRefreshHandler;

  public SsMediaSource(Uri manifestUri, DataSource.Factory manifestDataSourceFactory,
      SsChunkSource.Factory chunkSourceFactory, Handler eventHandler,
      AdaptiveMediaSourceEventListener eventListener) {
    this(manifestUri, manifestDataSourceFactory, chunkSourceFactory,
        DEFAULT_MIN_LOADABLE_RETRY_COUNT, eventHandler, eventListener);
  }

  public SsMediaSource(Uri manifestUri, DataSource.Factory dataSourceFactory,
      SsChunkSource.Factory chunkSourceFactory, int minLoadableRetryCount,
      Handler eventHandler, AdaptiveMediaSourceEventListener eventListener) {
    this.manifestUri = Util.toLowerInvariant(manifestUri.getLastPathSegment()).equals("manifest")
        ? manifestUri : Uri.withAppendedPath(manifestUri, "Manifest");
    this.dataSourceFactory = dataSourceFactory;
    this.chunkSourceFactory = chunkSourceFactory;
    this.minLoadableRetryCount = minLoadableRetryCount;
    this.eventDispatcher = new EventDispatcher(eventHandler, eventListener);
    manifestParser = new SsManifestParser();
    mediaPeriods = new ArrayList<>();
  }

  // MediaSource implementation.

  @Override
  public void prepareSource(MediaSource.Listener listener) {
    sourceListener = listener;
    manifestDataSource = dataSourceFactory.createDataSource();
    manifestLoader = new Loader("Loader:Manifest");
    manifestRefreshHandler = new Handler();
    startLoadingManifest();
  }

  @Override
  public int getNewPlayingPeriodIndex(int oldPlayingPeriodIndex, Timeline oldTimeline) {
    return oldPlayingPeriodIndex;
  }

  @Override
  public Position getDefaultStartPosition(int index) {
    if (window == null) {
      return null;
    }
    if (manifest.isLive) {
      long startPositionUs = Math.max(window.startTimeMs,
          window.endTimeMs - LIVE_EDGE_OFFSET_MS) * 1000;
      return new Position(0, startPositionUs);
    }
    return Position.DEFAULT;
  }

  @Override
  public void maybeThrowSourceInfoRefreshError() throws IOException {
    manifestLoader.maybeThrowError();
  }

  @Override
  public MediaPeriod createPeriod(int index, Callback callback, Allocator allocator,
      long positionUs) {
    Assertions.checkArgument(index == 0);
    SsMediaPeriod period = new SsMediaPeriod(manifest, chunkSourceFactory, minLoadableRetryCount,
        eventDispatcher, manifestLoader, callback, allocator);
    mediaPeriods.add(period);
    return period;
  }

  @Override
  public void releasePeriod(MediaPeriod period) {
    ((SsMediaPeriod) period).release();
    mediaPeriods.remove(period);
  }

  @Override
  public void releaseSource() {
    sourceListener = null;
    manifest = null;
    manifestDataSource = null;
    manifestLoadStartTimestamp = 0;
    if (manifestLoader != null) {
      manifestLoader.release();
      manifestLoader = null;
    }
    if (manifestRefreshHandler != null) {
      manifestRefreshHandler.removeCallbacksAndMessages(null);
      manifestRefreshHandler = null;
    }
  }

  // Loader.Callback implementation

  @Override
  public void onLoadCompleted(ParsingLoadable<SsManifest> loadable, long elapsedRealtimeMs,
      long loadDurationMs) {
    eventDispatcher.loadCompleted(loadable.dataSpec, loadable.type, elapsedRealtimeMs,
        loadDurationMs, loadable.bytesLoaded());
    manifest = loadable.getResult();
    manifestLoadStartTimestamp = elapsedRealtimeMs - loadDurationMs;
    for (int i = 0; i < mediaPeriods.size(); i++) {
      mediaPeriods.get(i).updateManifest(manifest);
    }
    Timeline timeline;
    if (manifest.isLive) {
      long startTimeUs = Long.MAX_VALUE;
      for (int i = 0; i < manifest.streamElements.length; i++) {
        StreamElement element = manifest.streamElements[i];
        if (element.chunkCount > 0) {
          startTimeUs = Math.min(startTimeUs, element.getStartTimeUs(0));
        }
      }
      if (startTimeUs == Long.MAX_VALUE || manifest.dvrWindowLengthUs == C.UNSET_TIME_US
          || manifest.dvrWindowLengthUs == 0) {
        timeline = new SinglePeriodTimeline(0, C.UNSET_TIME_US, false);
      } else {
        long periodDurationUs = startTimeUs + manifest.dvrWindowLengthUs;
        Window window = Window.createWindow(0, startTimeUs, 0, periodDurationUs,
            manifest.dvrWindowLengthUs, true);
        timeline = new SinglePeriodTimeline(0, periodDurationUs, window);
      }
    } else {
      boolean isSeekable = manifest.durationUs != C.UNSET_TIME_US;
      timeline = new SinglePeriodTimeline(0, manifest.durationUs, isSeekable);
    }
    window = timeline.getWindow(0);
    sourceListener.onSourceInfoRefreshed(timeline, manifest);
    scheduleManifestRefresh();
  }

  @Override
  public void onLoadCanceled(ParsingLoadable<SsManifest> loadable, long elapsedRealtimeMs,
      long loadDurationMs, boolean released) {
    eventDispatcher.loadCompleted(loadable.dataSpec, loadable.type, elapsedRealtimeMs,
        loadDurationMs, loadable.bytesLoaded());
  }

  @Override
  public int onLoadError(ParsingLoadable<SsManifest> loadable, long elapsedRealtimeMs,
      long loadDurationMs, IOException error) {
    boolean isFatal = error instanceof ParserException;
    eventDispatcher.loadError(loadable.dataSpec, loadable.type, elapsedRealtimeMs, loadDurationMs,
        loadable.bytesLoaded(), error, isFatal);
    return isFatal ? Loader.DONT_RETRY_FATAL : Loader.RETRY;
  }

  // Internal methods

  private void scheduleManifestRefresh() {
    if (!manifest.isLive) {
      return;
    }
    long nextLoadTimestamp = manifestLoadStartTimestamp + MINIMUM_MANIFEST_REFRESH_PERIOD_MS;
    long delayUntilNextLoad = Math.max(0, nextLoadTimestamp - SystemClock.elapsedRealtime());
    manifestRefreshHandler.postDelayed(new Runnable() {
      @Override
      public void run() {
        startLoadingManifest();
      }
    }, delayUntilNextLoad);
  }

  private void startLoadingManifest() {
    ParsingLoadable<SsManifest> loadable = new ParsingLoadable<>(manifestDataSource,
        manifestUri, C.DATA_TYPE_MANIFEST, manifestParser);
    long elapsedRealtimeMs = manifestLoader.startLoading(loadable, this, minLoadableRetryCount);
    eventDispatcher.loadStarted(loadable.dataSpec, loadable.type, elapsedRealtimeMs);
  }

}
