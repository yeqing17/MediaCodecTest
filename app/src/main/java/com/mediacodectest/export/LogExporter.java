package com.mediacodectest.export;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Runs "logcat -d" and persists the output. Without READ_LOGS (granted only on
 * rooted/system apps) only this process' logs are captured; that still includes the
 * MCT tag and ExoPlayer, which run in-process.
 */
public final class LogExporter {

    private static final String TAG = "MCT";

    private LogExporter() {
    }

    public static File export(Context context) throws Exception {
        File dir = OutputDirs.get(context);
        String name = "log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".txt";
        File out = new File(dir, name);

        Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time"});
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        try {
            process.waitFor();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        try (OutputStream os = new FileOutputStream(out)) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "log exported: " + out.getAbsolutePath());
        return out;
    }
}
