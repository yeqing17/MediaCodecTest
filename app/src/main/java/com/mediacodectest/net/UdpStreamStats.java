package com.mediacodectest.net;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide counters shared between {@link UdpMulticastDataSource} (writer, loader
 * thread) and MainActivity's stats panel (reader, UI thread). All values survive across
 * players because the DataSource instance is recreated per load while the panel wants a
 * continuous picture of one multicast session.
 */
public final class UdpStreamStats {

    private UdpStreamStats() {}

    private static final AtomicLong totalBytes = new AtomicLong();
    private static final AtomicLong totalPackets = new AtomicLong();
    private static final AtomicLong lostPackets = new AtomicLong();
    private static final AtomicLong deltaCursor = new AtomicLong();

    private static final AtomicBoolean active = new AtomicBoolean();
    private static volatile String transportLabel = "N/A";

    /** Reset everything; called when a new playback session starts. */
    public static void reset() {
        totalBytes.set(0);
        totalPackets.set(0);
        lostPackets.set(0);
        deltaCursor.set(0);
        active.set(false);
        transportLabel = "N/A";
    }

    /** Called once per received datagram with the payload length handed to ExoPlayer. */
    public static void onPacket(int payloadBytes) {
        totalPackets.incrementAndGet();
        totalBytes.addAndGet(payloadBytes);
    }

    public static void onPacketsLost(int count) {
        if (count > 0) {
            lostPackets.addAndGet(count);
        }
    }

    /**
     * Bytes delivered since the previous call. The 1 Hz stats updater uses this as an
     * RX-rate probe; clamped at 0 in case of a mid-session reset making cur &lt; cursor.
     */
    public static long takeByteDelta() {
        long cur = totalBytes.get();
        long prev = deltaCursor.getAndSet(cur);
        return Math.max(0, cur - prev);
    }

    public static void setActive(boolean a) {
        active.set(a);
    }

    public static boolean isActive() {
        return active.get();
    }

    /**
     * Short description rendered on the stats panel, e.g.
     * "UDP 组播 udp://239.1.1.5:1234 [wlan0]". Also refreshed before the socket exists
     * so the panel can show "UDP 待连接..." while joining.
     */
    public static void setTransportLabel(String label) {
        transportLabel = label;
    }

    public static String getTransportLabel() {
        return transportLabel;
    }

    public static long getTotalPackets() {
        return totalPackets.get();
    }

    public static long getTotalBytes() {
        return totalBytes.get();
    }

    public static long getLostPackets() {
        return lostPackets.get();
    }
}
