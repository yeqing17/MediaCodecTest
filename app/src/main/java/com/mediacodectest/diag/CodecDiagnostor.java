package com.mediacodectest.diag;

import android.media.MediaCodecList;
import android.media.MediaCodecInfo;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates platform MediaCodec decoders at startup so we can confirm which
 * amlogic / software decoders are visible to Android before ExoPlayer picks one.
 */
public final class CodecDiagnostor {

    public static final String MIME_AVC = "video/avc";
    public static final String MIME_HEVC = "video/hevc";

    private CodecDiagnostor() {
    }

    /** All decoder names supporting the given mime type (e.g. video/avc). */
    @NonNull
    public static List<String> listDecoderNames(@NonNull String mimeType) {
        List<String> names = new ArrayList<>();
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mimeType)) {
                    names.add(info.getName());
                    break;
                }
            }
        }
        return names;
    }

    /** Multi-line listing of AVC + HEVC decoders for the panel and report. */
    @NonNull
    public static String buildDecoderListing() {
        StringBuilder sb = new StringBuilder();
        appendSection(sb, "AVC Decoders", listDecoderNames(MIME_AVC));
        appendSection(sb, "HEVC Decoders", listDecoderNames(MIME_HEVC));
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String title, List<String> names) {
        sb.append("--- ").append(title).append(" (").append(names.size()).append(") ---\n");
        if (names.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (String n : names) {
                sb.append(n).append('\n');
            }
        }
    }
}
