package com.mediacodectest.analytics;

import android.media.MediaFormat;

import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.common.util.UnstableApi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts rendered frames via {@link VideoFrameMetadataListener}. ExoPlayer fires
 * {@link #onVideoFrameAboutToBeRendered} for every frame just before it reaches the
 * Surface, so summing these per second gives the *real* on-screen FPS, which is the
 * number we compare against the 25fps source stream.
 */
@OptIn(markerClass = UnstableApi.class)
public class FpsCounter implements VideoFrameMetadataListener {

    private final AtomicInteger framesThisSecond = new AtomicInteger(0);
    private volatile int lastFps = 0;
    private volatile int peakFps = 0;

    @Override
    public void onVideoFrameAboutToBeRendered(
            long presentationTimeUs, long releaseTimeNs, Format format, MediaFormat mediaFormat) {
        framesThisSecond.incrementAndGet();
    }

    /** Called once per second; returns the FPS rendered during the previous interval. */
    public int tickAndReset() {
        int count = framesThisSecond.getAndSet(0);
        lastFps = count;
        if (count > peakFps) {
            peakFps = count;
        }
        return count;
    }

    public int getLastFps() {
        return lastFps;
    }

    public int getPeakFps() {
        return peakFps;
    }

    public void reset() {
        framesThisSecond.set(0);
        lastFps = 0;
        peakFps = 0;
    }
}
