package com.mediacodectest.analytics;

import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Aggregates the {@link AnalyticsListener} stream into the fields shown in the
 * stats panel: format (mime / resolution / bitrate), accumulated dropped frames.
 * The current decoder name is resolved through ExoPlayer's own MediaCodecUtil so it
 * reflects the decoder that would actually be selected for the active mime type.
 */
@OptIn(markerClass = UnstableApi.class)
public class StatsCollector implements AnalyticsListener {

    private static final String TAG = "MCT";

    private volatile String mimeType = "N/A";
    private volatile int width = 0;
    private volatile int height = 0;
    private volatile long bitrate = C.LENGTH_UNSET;
    private volatile int droppedTotal = 0;
    private volatile long firstFrameRealtimeMs = 0;

    private boolean forceSoftware;

    public void setForceSoftware(boolean forceSoftware) {
        this.forceSoftware = forceSoftware;
    }

    @Override
    public void onVideoInputFormatChanged(EventTime eventTime, Format format) {
        mimeType = format.sampleMimeType != null ? format.sampleMimeType : "N/A";
        width = format.width;
        height = format.height;
        bitrate = format.bitrate;
    }

    @Override
    public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {
        droppedTotal += droppedFrames;
    }

    @Override
    public void onRenderedFirstFrame(EventTime eventTime, Object output, long renderTimeMs) {
        if (firstFrameRealtimeMs == 0) {
            firstFrameRealtimeMs = android.os.SystemClock.elapsedRealtime();
        }
    }

    // ---- Load tracing: shows whether ExoPlayer is pulling data and how much. ----

    @Override
    public void onLoadStarted(EventTime eventTime, LoadEventInfo loadEventInfo,
                              MediaLoadData mediaLoadData) {
        Log.i(TAG, "load start: " + loadEventInfo.uri
                + " | " + dataTypeName(mediaLoadData.dataType));
    }

    @Override
    public void onLoadCompleted(EventTime eventTime, LoadEventInfo loadEventInfo,
                                MediaLoadData mediaLoadData) {
        Log.i(TAG, "load done: " + loadEventInfo.uri
                + " | bytes=" + loadEventInfo.bytesLoaded
                + " | " + dataTypeName(mediaLoadData.dataType));
    }

    @Override
    public void onLoadError(EventTime eventTime, LoadEventInfo loadEventInfo,
                            MediaLoadData mediaLoadData, IOException error, boolean wasCanceled) {
        Log.e(TAG, "load ERROR: " + loadEventInfo.uri
                + " | " + error.getClass().getSimpleName() + ": " + error.getMessage()
                + (wasCanceled ? " (canceled)" : ""));
    }

    @Override
    public void onLoadCanceled(EventTime eventTime, LoadEventInfo loadEventInfo,
                               MediaLoadData mediaLoadData) {
        Log.i(TAG, "load CANCELED: " + loadEventInfo.uri
                + " | bytes=" + loadEventInfo.bytesLoaded
                + " | " + dataTypeName(mediaLoadData.dataType));
    }

    private static String dataTypeName(int dataType) {
        switch (dataType) {
            case C.DATA_TYPE_MEDIA: return "media";
            case C.DATA_TYPE_MANIFEST: return "manifest";
            case C.DATA_TYPE_DRM: return "drm";
            default: return "type=" + dataType;
        }
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getResolution() {
        if (width <= 0 || height <= 0) {
            return "N/A";
        }
        return String.format(Locale.US, "%dx%d", width, height);
    }

    public String getBitrate() {
        if (bitrate == C.LENGTH_UNSET || bitrate <= 0) {
            return "N/A";
        }
        return String.format(Locale.US, "%d kbps", bitrate / 1000);
    }

    public int getDroppedTotal() {
        return droppedTotal;
    }

    public long getFirstFrameRealtimeMs() {
        return firstFrameRealtimeMs;
    }

    public String getDecoderName() {
        return resolveDecoderName(mimeType, forceSoftware);
    }

    public void reset() {
        mimeType = "N/A";
        width = 0;
        height = 0;
        bitrate = C.LENGTH_UNSET;
        droppedTotal = 0;
        firstFrameRealtimeMs = 0;
    }

    private static String resolveDecoderName(String mimeType, boolean forceSoftware) {
        if (mimeType == null || "N/A".equals(mimeType)) {
            return "N/A";
        }
        try {
            List<MediaCodecInfo> infos = MediaCodecUtil.getDecoderInfos(mimeType, false, false);
            if (infos == null || infos.isEmpty()) {
                return "none";
            }
            if (forceSoftware) {
                for (MediaCodecInfo info : infos) {
                    if (!info.hardwareAccelerated) {
                        return info.name;
                    }
                }
            }
            // First entry is ExoPlayer's preferred decoder (hardware by default).
            return infos.get(0).name;
        } catch (MediaCodecUtil.DecoderQueryException e) {
            return "error";
        }
    }
}
