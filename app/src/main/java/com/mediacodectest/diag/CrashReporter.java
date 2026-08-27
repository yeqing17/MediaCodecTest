package com.mediacodectest.diag;

import android.content.Context;
import android.util.Log;

import com.mediacodectest.export.OutputDirs;

import java.io.File;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Uncaught-exception watcher. Writes the full stack trace to
 * <em>&lt;output dir&gt;/crash_&lt;timestamp&gt;.txt</em> before chaining to the
 * platform handler, so a field crash survives process death and can be read
 * straight off /sdcard/MediaCodecTest/ or shared via Export Log.
 *
 * Install once at the top of Activity.onCreate().
 */
public final class CrashReporter {

    private static final String TAG = "MCT";

    private CrashReporter() {
    }

    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                File dir = OutputDirs.get(app);
                String name = "crash_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(new Date()) + ".txt";
                File out = new File(dir, name);
                try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
                    pw.println("Thread : " + thread.getName());
                    pw.println("Time   : " + new Date());
                    pw.println("Device : " + android.os.Build.MANUFACTURER + " "
                            + android.os.Build.MODEL + " (API "
                            + android.os.Build.VERSION.SDK_INT + ")");
                    pw.println();
                    throwable.printStackTrace(pw);
                }
                Log.e(TAG, "crash saved: " + out.getAbsolutePath(), throwable);
            } catch (Throwable ignore) {
                // The reporter must never replace the actual crash with its own.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }
}
