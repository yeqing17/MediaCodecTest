package com.mediacodectest.export;

import android.content.Context;
import android.util.Log;

import com.mediacodectest.analytics.StatsCollector;
import com.mediacodectest.diag.CodecDiagnostor;
import com.mediacodectest.diag.DeviceInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes report.txt: a single snapshot of device info, available decoders, and the
 * current playback state (decoder, fps, dropped, resolution, bitrate, url, elapsed).
 */
public final class ReportExporter {

    private static final String TAG = "MCT";

    private ReportExporter() {
    }

    public static File export(Context context, StatsCollector stats, int fps,
                              boolean forceSoftware, String url, long startedAtMs)
            throws Exception {
        File dir = OutputDirs.get(context);
        File out = new File(dir, "report.txt");

        StringBuilder sb = new StringBuilder();
        sb.append("MediaCodecTest Diagnostic Report\n");
        sb.append("Generated : ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append("\n\n");

        sb.append("=== Device ===\n").append(DeviceInfo.toBlock()).append('\n');
        sb.append("=== Available Decoders ===\n")
                .append(CodecDiagnostor.buildDecoderListing()).append('\n');

        sb.append("=== Playback ===\n");
        sb.append("Decode Mode    : ").append(forceSoftware ? "Software" : "Hardware").append('\n');
        sb.append("Current Decoder: ").append(stats.getDecoderName()).append('\n');
        sb.append("MimeType       : ").append(stats.getMimeType()).append('\n');
        sb.append("Resolution     : ").append(stats.getResolution()).append('\n');
        sb.append("FPS            : ").append(fps).append('\n');
        sb.append("Dropped Frames : ").append(stats.getDroppedTotal()).append('\n');
        sb.append("Bitrate        : ").append(stats.getBitrate()).append('\n');
        sb.append("Play URL       : ").append(url == null ? "" : url).append('\n');

        long elapsedMs = startedAtMs > 0 ? System.currentTimeMillis() - startedAtMs : 0;
        sb.append("Play Duration  : ").append(formatDuration(elapsedMs)).append('\n');

        try (OutputStream os = new FileOutputStream(out)) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "report exported: " + out.getAbsolutePath());
        return out;
    }

    private static String formatDuration(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long s = ms / 1000;
        long m = s / 60;
        long h = m / 60;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m % 60, s % 60);
    }
}
