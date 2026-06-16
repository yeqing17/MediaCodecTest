package com.mediacodectest.analytics;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * MediaCodecSelector that only offers software decoders (e.g. c2.android.avc.decoder,
 * OMX.google.h264.decoder). Wired into DefaultRenderersFactory when "Force Software
 * Decode" is on, so no ffmpeg extension is required to exercise the software path.
 */
@OptIn(markerClass = UnstableApi.class)
public class SoftwareCodecSelector implements MediaCodecSelector {

    public static final SoftwareCodecSelector INSTANCE = new SoftwareCodecSelector();

    @Override
    public List<MediaCodecInfo> getDecoderInfo(String mimeType, boolean secure, boolean tunneling)
            throws MediaCodecUtil.DecoderQueryException {
        List<MediaCodecInfo> all = MediaCodecUtil.getDecoderInfo(mimeType, secure, tunneling);
        if (all == null || all.isEmpty()) {
            return all;
        }
        List<MediaCodecInfo> software = new ArrayList<>();
        for (MediaCodecInfo info : all) {
            if (!info.isHardwareAccelerated()) {
                software.add(info);
            }
        }
        return software.isEmpty() ? all : software;
    }
}
