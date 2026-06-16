package com.mediacodectest.diag;

import android.os.Build;

/**
 * Collects basic device / platform facts for the diagnostic panel and report.
 */
public final class DeviceInfo {

    private DeviceInfo() {
    }

    public static String getManufacturer() {
        return nullToEmpty(Build.MANUFACTURER);
    }

    public static String getModel() {
        return nullToEmpty(Build.MODEL);
    }

    public static String getBrand() {
        return nullToEmpty(Build.BRAND);
    }

    public static String getBoard() {
        return nullToEmpty(Build.BOARD);
    }

    public static String getAndroidVersion() {
        return nullToEmpty(Build.VERSION.RELEASE);
    }

    public static int getSdkVersion() {
        return Build.VERSION.SDK_INT;
    }

    /** Multi-line block used in the stats view and exported report. */
    public static String toBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("Manufacturer : ").append(getManufacturer()).append('\n');
        sb.append("Brand        : ").append(getBrand()).append('\n');
        sb.append("Model        : ").append(getModel()).append('\n');
        sb.append("Board        : ").append(getBoard()).append('\n');
        sb.append("Android      : ").append(getAndroidVersion())
                .append(" (SDK ").append(getSdkVersion()).append(")\n");
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
