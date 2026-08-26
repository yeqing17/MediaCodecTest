package com.mediacodectest.net;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays raw MPEG-TS streams carried over UDP multicast (IPTV-style {@code udp://@grp:port}),
 * unicast UDP, and optionally RTP (12-byte header stripped before the TS payload reaches
 * ExoPlayer's extractor). ExoPlayer ships no UDP DataSource, so udp:// URLs never reach
 * the TS extractor without this class.
 *
 * Supported URL shapes:
 * <pre>
 *   udp://@239.1.1.5:1234            join group 239.1.1.5 on port 1234
 *   udp://239.1.1.5:1234             same ('@' is optional)
 *   udp://192.168.1.10:5000          unicast listen: bind port 5000, no group join
 *   rtp://@239.1.1.5:1234            RTP over UDP (header handled automatically)
 *   ?ifname=eth0                     restrict to one NIC (multi-interface devices)
 *   ?rcvbuf=8388608                  SO_RCVBUF bytes (default 4 MB, OS caps apply)
 *   ?rtp=auto|on|off                 RTP demux mode; auto strips only PT=33 headers
 * </pre>
 *
 * Join order across candidate interfaces: eth* first, then wlan*, cellular last — on a
 * device with both Ethernet and Wi-Fi the wired NIC wins unless ?ifname= says otherwise.
 */
@OptIn(markerClass = UnstableApi.class)
public final class UdpMulticastDataSource extends BaseDataSource {

    private static final String TAG = "MCT";

    private static final int DEFAULT_RCVBUF = 4 * 1024 * 1024;
    private static final int MIN_RCVBUF = 256 * 1024;
    private static final int SO_TIMEOUT_MS = 1000;
    /** Give up when not even one datagram arrived this long after open(). */
    private static final long FIRST_PACKET_TIMEOUT_MS = 15000;
    /** Give up when packets stopped flowing for this long mid-stream. */
    private static final long STALL_TIMEOUT_MS = 15000;

    private static final int RTP_HEADER_FIXED = 12;
    private static final int RTP_PT_MP2T = 33;
    private static final int SEQ_WINDOW = 8192;

    public static final String RTP_AUTO = "auto";
    public static final String RTP_ON = "on";
    public static final String RTP_OFF = "off";

    private final AtomicBoolean closed = new AtomicBoolean(true);
    /** Scratch every incoming datagram lands in before the copy into {@link #stage}. */
    private final byte[] scratch = new byte[65536];
    /**
     * Contiguous pending payload consumed by read(). Two max-size IP datagrams always
     * fit: a single loader thread alternates receive->read, so at most one undrained
     * datagram coexists with the freshly received one.
     */
    private final byte[] stage = new byte[131072];
    private final DatagramPacket packet = new DatagramPacket(new byte[0], 0);

    @Nullable private MulticastSocket socket;
    @Nullable private NetworkInterface joinedInterface;
    @Nullable private InetSocketAddress joinedGroup;
    @Nullable private DataSpec dataSpec;

    private int stagePos = 0;
    private int stageEnd = 0;

    private int rcvbufRequested = DEFAULT_RCVBUF;
    private String rtpMode = RTP_AUTO;
    private int lastSeq = -1;
    private boolean seqValid = false;

    private long openedAtMs = 0;
    private long lastProgressAtMs = 0;
    private boolean gotAnyPacket = false;

    public UdpMulticastDataSource() {
        super(/* isNetwork= */ true);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        closed.set(false);
        stagePos = 0;
        stageEnd = 0;
        seqValid = false;
        gotAnyPacket = false;
        long now = SystemClock.elapsedRealtime();
        openedAtMs = now;
        lastProgressAtMs = now;

        Uri uri = dataSpec.uri;
        Parsed parsed = parseUri(uri.toString());
        rtpMode = parsed.rtpMode;
        rcvbufRequested = parsed.rcvbuf;

        InetAddress group = InetAddress.getByName(parsed.host);
        boolean isMulticast = group.isMulticastAddress();

        MulticastSocket s = new MulticastSocket((InetSocketAddress) null);
        try {
            s.setReuseAddress(true);
            s.setReceiveBufferSize(rcvbufRequested);
            s.bind(new InetSocketAddress(parsed.port));
            s.setSoTimeout(SO_TIMEOUT_MS);
        } catch (SocketException e) {
            s.close();
            throw new IOException("UDP socket setup failed: " + e.getMessage(), e);
        }
        socket = s;

        if (isMulticast) {
            List<NetworkInterface> candidates = candidateInterfaces(parsed.ifName);
            if (candidates.isEmpty()) {
                closeQuietly();
                throw new IOException("no usable network interface"
                        + (parsed.ifName != null ? " '" + parsed.ifName + "'" : ""));
            }
            IOException last = null;
            for (NetworkInterface nif : candidates) {
                try {
                    if (!familyMatches(group, nif)) continue;
                    InetSocketAddress endpoint = new InetSocketAddress(group, parsed.port);
                    s.joinGroup(endpoint, nif);
                    joinedInterface = nif;
                    joinedGroup = endpoint;
                    break;
                } catch (IOException e) {
                    last = e;
                    Log.w(TAG, "UDP join failed on " + nif.getName() + ": " + e.getMessage());
                }
            }
            if (joinedInterface == null) {
                String msg = "joinGroup(" + group.getHostAddress() + ") failed on all interfaces";
                if (last != null) msg += ": " + last.getMessage();
                closeQuietly();
                throw new IOException(msg);
            }
        }

        int actualBuf = s.getReceiveBufferSize();
        String ifaceName = joinedInterface != null ? joinedInterface.getName() : null;
        Log.i(TAG, "UDP open: " + uri
                + " | " + (isMulticast ? "multicast" : "unicast")
                + " " + parsed.host + ":" + parsed.port
                + " | iface=" + (ifaceName != null ? ifaceName : "-")
                + " | rcvbuf=" + actualBuf + "/" + rcvbufRequested
                + " | rtp=" + rtpMode);

        UdpStreamStats.setTransportLabel("UDP " + (isMulticast ? "组播" : "单播")
                + " " + parsed.host + ":" + parsed.port
                + (ifaceName != null ? " [" + ifaceName + "]" : ""));
        UdpStreamStats.setActive(true);

        transferInitializing(dataSpec);
        transferStarted(dataSpec);
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        ensurePayload();
        int n = Math.min(length, stageEnd - stagePos);
        System.arraycopy(stage, stagePos, buffer, offset, n);
        stagePos += n;
        bytesTransferred(n);
        return n;
    }

    /**
     * Pulls datagrams through {@link #intake(int)} until at least one payload byte is
     * contiguous at {@link #stagePos}. read() may hand back fewer bytes than requested
     * (the DataSource contract allows it), which also keeps this loop immune to huge
     * extractor-side read requests. Enforces the open/progress deadlines here.
     */
    private void ensurePayload() throws IOException {
        while (stageEnd - stagePos <= 0) {
            if (closed.get()) {
                throw new InterruptedIOException("closed");
            }
            checkDeadlines();
            packet.setData(scratch, 0, scratch.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                continue; // loop re-checks deadlines and the closed flag
            } catch (SocketException e) {
                if (closed.get()) {
                    throw new InterruptedIOException("closed");
                }
                throw new IOException("UDP receive failed: " + e.getMessage(), e);
            }
            intake(packet.getLength());
            lastProgressAtMs = SystemClock.elapsedRealtime();
            gotAnyPacket = true;
        }
    }

    /**
     * Strips an RTP header when present (on {@link #scratch}), counts the payload and
     * appends it to {@link #stage}, compacting the staging window when its tail is hit.
     */
    private void intake(int len) {
        int off = 0;
        int headerLen = RTP_OFF.equals(rtpMode) ? 0 : rtpHeaderLength(len);
        if (headerLen > 0) {
            accountSeq(scratch, off + 2);
            off += headerLen;
            len -= headerLen;
        }
        if (len <= 0) {
            return;
        }
        UdpStreamStats.onPacket(len);

        if (stageEnd == stagePos) { // fully drained -> restart at 0
            stagePos = 0;
            stageEnd = 0;
        } else if (stageEnd + len > stage.length) {
            System.arraycopy(stage, stagePos, stage, 0, stageEnd - stagePos);
            stageEnd -= stagePos;
            stagePos = 0;
        }
        System.arraycopy(scratch, off, stage, stageEnd, len);
        stageEnd += len;
    }

    /**
     * Total RTP header length when {@code scratch} starts with an RTP MP2T datagram,
     * or 0 when it must be treated as raw MPEG-TS. Raw TS never false-positives here:
     * its 0x47 sync byte decodes as RTP version 1, and AUTO mode additionally demands
     * the registered PT=33 plus a 0x47 first payload byte.
     */
    private int rtpHeaderLength(int len) {
        if (len < RTP_HEADER_FIXED) {
            return 0;
        }
        int b0 = scratch[0] & 0xFF;
        int b1 = scratch[1] & 0xFF;
        if ((b0 >> 6) != 2) { // RTP version 2; TS sync 0x47 => "version 1", rejected here
            return 0;
        }
        boolean ptMp2t = (b1 & 0x7F) == RTP_PT_MP2T;
        if (!ptMp2t && !RTP_ON.equals(rtpMode)) {
            return 0;
        }
        int cc = b0 & 0x0F;
        int hdr = RTP_HEADER_FIXED + 4 * cc;
        if ((b0 & 0x10) != 0) { // X bit: extension follows CSRC list
            if (len < hdr + 4) {
                return 0;
            }
            int extWords = ((scratch[hdr + 2] & 0xFF) << 8) | (scratch[hdr + 3] & 0xFF);
            hdr += 4 + 4 * extWords;
        }
        if (len < hdr) {
            return 0;
        }
        if (!RTP_ON.equals(rtpMode) && (scratch[hdr] & 0xFF) != 0x47) {
            return 0; // claims MP2T but payload is not TS -> safer to pass through raw
        }
        return hdr;
    }

    private void accountSeq(byte[] buf, int seqOffset) {
        int seq = ((buf[seqOffset] & 0xFF) << 8) | (buf[seqOffset + 1] & 0xFF);
        if (seqValid) {
            int delta = (seq - lastSeq) & 0xFFFF;
            if (delta > 0 && delta < SEQ_WINDOW) {
                UdpStreamStats.onPacketsLost(delta - 1);
            }
        }
        lastSeq = seq;
        seqValid = true;
    }

    private void checkDeadlines() throws IOException {
        long now = SystemClock.elapsedRealtime();
        if (!gotAnyPacket && now - openedAtMs > FIRST_PACKET_TIMEOUT_MS) {
            throw new IOException(FIRST_PACKET_TIMEOUT_MS / 1000
                    + "s 未收到任何UDP数据（检查组播地址/VLAN/网卡）");
        }
        if (gotAnyPacket && now - lastProgressAtMs > STALL_TIMEOUT_MS) {
            throw new IOException(STALL_TIMEOUT_MS / 1000 + "s 无UDP数据（流中断）");
        }
    }

    @Override
    @Nullable
    public Uri getUri() {
        return dataSpec != null ? dataSpec.uri : null;
    }

    @Override
    public void close() throws IOException {
        Uri uri = dataSpec != null ? dataSpec.uri : null;
        try {
            closeQuietly();
        } finally {
            transferEnded();
            Log.i(TAG, "UDP close: " + uri);
        }
    }

    /** Leaves the group and closes the socket; safe from any thread and repeatable. */
    private void closeQuietly() {
        closed.set(true);
        MulticastSocket s = socket;
        socket = null;
        NetworkInterface nif = joinedInterface;
        InetSocketAddress group = joinedGroup;
        joinedInterface = null;
        joinedGroup = null;
        if (s != null) {
            if (nif != null && group != null) {
                try {
                    s.leaveGroup(group, nif);
                } catch (Exception ignore) {
                    // best effort only
                }
            }
            s.disconnect();
            s.close();
        }
        UdpStreamStats.setActive(false);
    }

    // ---- helpers ----

    private static final class Parsed {
        String host;
        int port;
        @Nullable String ifName;
        int rcvbuf = DEFAULT_RCVBUF;
        String rtpMode = RTP_AUTO;
    }

    /**
     * Manual host/port surgery instead of Uri.getAuthority(): Android's Uri parser
     * yields null hosts for "@group" authorities, and VLC's '@' convention must keep
     * working. Query params ride along on any shape.
     */
    private static Parsed parseUri(String spec) throws IOException {
        int schemeEnd = spec.indexOf("://");
        if (schemeEnd < 0) {
            throw new IOException("not a valid udp/rtp url: " + spec);
        }
        String rest = spec.substring(schemeEnd + 3);
        int q = rest.indexOf('?');
        String query = q >= 0 ? rest.substring(q + 1) : null;
        if (q >= 0) {
            rest = rest.substring(0, q);
        }
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            rest = rest.substring(0, hash);
        }
        rest = rest.trim();
        while (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        Parsed p = new Parsed();
        if (query != null && !query.isEmpty()) {
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                String k = eq < 0 ? kv : kv.substring(0, eq);
                String v = eq < 0 ? "" : kv.substring(eq + 1).trim();
                switch (k) {
                    case "ifname":
                        p.ifName = v.isEmpty() ? null : v;
                        break;
                    case "rcvbuf":
                        try {
                            p.rcvbuf = Math.max(MIN_RCVBUF, Integer.parseInt(v));
                        } catch (NumberFormatException ignore) {
                            // keep default on garbage input
                        }
                        break;
                    case "rtp": {
                        String m = v.toLowerCase(Locale.US);
                        if (RTP_ON.equals(m) || RTP_OFF.equals(m)) {
                            p.rtpMode = m;
                        }
                        break;
                    }
                    default:
                        break; // unknown params ignored (forward compatibility)
                }
            }
        }
        int colon = rest.lastIndexOf(':');
        if (colon <= 0 || colon == rest.length() - 1) {
            throw new IOException("udp/rtp url needs host:port, got: " + rest);
        }
        p.host = rest.substring(0, colon);
        if (p.host.startsWith("@")) {
            p.host = p.host.substring(1);
        }
        try {
            p.port = Integer.parseInt(rest.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            throw new IOException("bad port in: " + rest);
        }
        if (p.port <= 0 || p.port > 65535) {
            throw new IOException("port out of range: " + p.port);
        }
        return p;
    }

    /** Interfaces we may join on, eth-first/wlan-next ordered, honoring ?ifname=. */
    private static List<NetworkInterface> candidateInterfaces(@Nullable String preferred)
            throws SocketException {
        List<NetworkInterface> all = new ArrayList<>();
        Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
        while (en.hasMoreElements()) {
            NetworkInterface nif = en.nextElement();
            try {
                if (nif.isUp() && !nif.isLoopback() && nif.supportsMulticast()) {
                    all.add(nif);
                }
            } catch (SocketException ignore) {
                // skip interfaces that fail basic queries
            }
        }
        Collections.sort(all, Comparator.comparingInt(nif -> nameRank(nif.getName())));
        if (preferred == null || preferred.isEmpty()) {
            return all;
        }
        List<NetworkInterface> picked = new ArrayList<>();
        for (NetworkInterface nif : all) {
            if (nif.getName().equalsIgnoreCase(preferred)) {
                picked.add(nif);
            }
        }
        return picked;
    }

    private static int nameRank(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.startsWith("eth")) return 0;
        if (n.startsWith("wlan")) return 1;
        if (n.contains("rmnet") || n.contains("ccmni")) return 9; // cellular last
        return 5;
    }

    /** True when the interface owns at least one address of the group's IP family. */
    private static boolean familyMatches(InetAddress group, NetworkInterface nif) {
        Class<?> family = group instanceof Inet4Address ? Inet4Address.class
                : InetAddress.class;
        for (java.net.InterfaceAddress ia : nif.getInterfaceAddresses()) {
            if (family.isInstance(ia.getAddress())) {
                return true;
            }
        }
        return false;
    }
}
