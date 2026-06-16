package com.mediacodectest.export;

import android.content.Context;
import android.os.Environment;

import java.io.File;

/**
 * Resolves a writable MediaCodecTest output directory. On Android 9 / legacy storage
 * this is /sdcard/MediaCodecTest as required by the PRD; on scoped-storage devices it
 * falls back to the app-specific external dir so export always succeeds.
 */
public final class OutputDirs {

    public static final String FOLDER = "MediaCodecTest";

    private OutputDirs() {
    }

    public static File get(Context context) {
        File primary = new File(Environment.getExternalStorageDirectory(), FOLDER);
        if (ensure(primary)) {
            return primary;
        }
        File fallback = new File(context.getExternalFilesDir(null), FOLDER);
        //noinspection ResultOfMethodCallIgnored
        fallback.mkdirs();
        return fallback;
    }

    private static boolean ensure(File dir) {
        if (dir.exists() && dir.isDirectory() && dir.canWrite()) {
            return true;
        }
        try {
            return dir.mkdirs() || dir.exists();
        } catch (Exception ignored) {
            return false;
        }
    }
}
