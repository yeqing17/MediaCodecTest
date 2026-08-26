package com.mediacodectest;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Html;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.ui.PlayerView;

import com.mediacodectest.analytics.FpsCounter;
import com.mediacodectest.analytics.HttpTraceDataSource;
import com.mediacodectest.analytics.SoftwareCodecSelector;
import com.mediacodectest.analytics.StatsCollector;
import com.mediacodectest.diag.CodecDiagnostor;
import com.mediacodectest.diag.DeviceInfo;
import com.mediacodectest.export.LogExporter;
import com.mediacodectest.export.ReportExporter;
import com.mediacodectest.net.PlayUrlProvider;
import com.mediacodectest.net.SchemeRoutingDataSource;
import com.mediacodectest.net.UdpStreamStats;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-screen diagnostic player. Lets an operator play a live stream through pure
 * Media3/ExoPlayer (bypassing IJKPlayer) and watch real render FPS, dropped frames,
 * decoder, etc., to decide whether a stutter is in the MediaCodec/Surface stack or in IJK.
 */
public class MainActivity extends ComponentActivity {

    private static final String TAG = "MCT";
    private static final int REQ_STORAGE = 1001;
    private static final long STATS_INTERVAL_MS = 1000L;
    private static final int MAX_ERROR_RETRIES = 5;
    private static final long RECONNECT_DELAY_MS = 2000L;

    private EditText urlInput;
    private CheckBox forceSoftwareBox;
    private CheckBox autoReconnectBox;
    private Button muteBtn;
    private boolean muted = false;
    private Spinner presetSpinner;
    private TextView advancedToggle;
    private View configContainer;
    private View getUrlBtnView;
    private boolean advancedOpen = false;
    private PlayerView playerView;
    private TextView statsView;
    private ScrollView statsScroll;
    private View statsDot;
    private ActivityResultLauncher<String[]> openFileLauncher;
    private final Map<String, EditText> configFields = new LinkedHashMap<>();

    private ExoPlayer player;
    private final FpsCounter fpsCounter = new FpsCounter();
    private final StatsCollector stats = new StatsCollector();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final PlayUrlProvider playUrlProvider = new PlayUrlProvider();

    private String deviceBlock;
    private String decoderListing;
    private long playbackStartedAtMs = 0;
    private long playClickElapsedMs = 0;
    private boolean pendingExportReport = false;
    private int errorRetriesLeft = 0;
    @Nullable private WifiManager.MulticastLock multicastLock;
    private final Runnable reconnectRunnable = this::doStartPlayback;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            Log.i(TAG, "state=" + stateName(state));
            if (state == Player.STATE_READY && playbackStartedAtMs == 0) {
                playbackStartedAtMs = System.currentTimeMillis();
            }
            if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                playbackStartedAtMs = 0;
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            Log.e(TAG, "player error", error);
            toast("Player error: " + error.getMessage());
            scheduleReconnect();
        }
    };

    private final Runnable statsUpdater = new Runnable() {
        @Override
        public void run() {
            updateStats();
            mainHandler.postDelayed(this, STATS_INTERVAL_MS);
        }
    };

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        deviceBlock = DeviceInfo.toBlock();
        decoderListing = CodecDiagnostor.buildDecoderListing();

        urlInput = findViewById(R.id.urlInput);
        forceSoftwareBox = findViewById(R.id.forceSoftware);
        autoReconnectBox = findViewById(R.id.autoReconnectBox);
        muteBtn = findViewById(R.id.muteBtn);
        presetSpinner = findViewById(R.id.presetSpinner);
        advancedToggle = findViewById(R.id.advancedToggle);
        configContainer = findViewById(R.id.configContainer);
        getUrlBtnView = findViewById(R.id.getUrlBtn);
        playerView = findViewById(R.id.playerView);
        statsView = findViewById(R.id.statsView);
        statsScroll = findViewById(R.id.statsScroll);
        statsDot = findViewById(R.id.statsDot);

        // SAF file picker: returns a content:// URI for any local file (U盘 / sdcard /
        // tmp). No storage permission needed. We take a persistable read grant so the
        // chosen path stays valid if the user replays it later.
        openFileLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null) return;
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignore) {
                    }
                    String s = uri.toString();
                    urlInput.setText(s);
                    urlInput.setSelection(s.length());
                    toast("已选择文件，开始播放");
                    startPlayback();
                });

        buildConfigFields();
        bindButtons();

        setupPresets();

        // Prefill the default multicast address so a fresh install can hit Play
        // directly; anything the user restores/types keeps priority.
        if (urlInput.getText().toString().isEmpty()) {
            String def = getString(R.string.udp_default_url);
            urlInput.setText(def);
            urlInput.setSelection(def.length());
        }

        updateStats();
        requestStoragePermissionIfNeeded();
    }

    private void setupPresets() {
        final java.lang.String[] labels = getResources().getStringArray(R.array.preset_labels);
        final java.lang.String[] urls = getResources().getStringArray(R.array.preset_urls);
        presetSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels));
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean first = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Skip the synthetic initial selection so we don't clobber a typed URL.
                if (first) {
                    first = false;
                    return;
                }
                if (position < 0 || position >= urls.length) return;
                String u = urls[position];
                if (u == null || u.isEmpty()) return;
                if (u.startsWith("local://")) {
                    String resolved = resolveLocalMediaTs();
                    urlInput.setText(resolved);
                    urlInput.setSelection(resolved.length());
                } else {
                    urlInput.setText(u);
                    urlInput.setSelection(u.length());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void buildConfigFields() {
        LinearLayout container = findViewById(R.id.configContainer);
        for (Map.Entry<String, String> e : PlayUrlProvider.defaultParamKeys().entrySet()) {
            EditText field = new EditText(this);
            field.setHint(e.getKey());
            field.setText(e.getValue());
            field.setInputType(InputType.TYPE_CLASS_TEXT);
            field.setTag(e.getKey());
            field.setTextSize(13);
            // Match the dark input style used in the layout (MCT.EditText is XML-only).
            field.setBackgroundResource(R.drawable.bg_input);
            field.setTextColor(getColor(R.color.text));
            field.setHintTextColor(getColor(R.color.text_dim));
            field.setPadding(dp(12), dp(8), dp(12), dp(8));

            TextView label = new TextView(this);
            label.setText(e.getKey());
            label.setTextSize(12);
            label.setTextColor(getColor(R.color.text_dim));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            label.setPadding(0, 0, 12, 0);
            row.addView(field, lp);

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = 6;
            container.addView(row, rowLp);

            configFields.put(e.getKey(), field);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void bindButtons() {
        findViewById(R.id.playBtn).setOnClickListener(v -> startPlayback());
        findViewById(R.id.stopBtn).setOnClickListener(v -> stopPlayback());
        findViewById(R.id.openFileBtn).setOnClickListener(v -> openLocalFile());
        if (muteBtn != null) {
            muteBtn.setOnClickListener(v -> toggleMute());
        }
        findViewById(R.id.getUrlBtn).setOnClickListener(v -> fetchPlayUrl());
        findViewById(R.id.exportLogBtn).setOnClickListener(v -> exportLog());
        findViewById(R.id.exportReportBtn).setOnClickListener(v -> exportReport());
        advancedToggle.setOnClickListener(v -> toggleAdvanced());
    }

    private void openLocalFile() {
        openFileLauncher.launch(new String[]{"*/*"});
    }

    /**
     * Resolve the "local media.ts" preset to a file:// path. Looks first on the
     * primary shared storage (sdcard), then on every mounted removable volume
     * (U盘 / SD). Falls back to a default path and prompts the user to pick via
     * the 文件 button when nothing readable is found.
     */
    private String resolveLocalMediaTs() {
        File sd = Environment.getExternalStorageDirectory();
        File primary = new File(sd, "media.ts");
        if (primary.exists() && primary.canRead()) {
            return "file://" + primary.getAbsolutePath();
        }
        // getExternalFilesDirs returns app-private dirs on every mounted volume;
        // strip the /Android/data/<pkg>/files suffix to reach each volume root.
        File[] dirs = getExternalFilesDirs(null);
        if (dirs != null) {
            for (File d : dirs) {
                if (d == null) continue;
                File vol = volumeRoot(d);
                if (vol == null) continue;
                File mf = new File(vol, "media.ts");
                if (mf.exists() && mf.canRead()) {
                    return "file://" + mf.getAbsolutePath();
                }
            }
        }
        toast("未找到 media.ts，请点 文件 按钮手动选择");
        return "file://" + primary.getAbsolutePath();
    }

    private static File volumeRoot(File appFilesDir) {
        String p = appFilesDir.getAbsolutePath();
        int i = p.indexOf("/Android/data/");
        return i > 0 ? new File(p.substring(0, i)) : null;
    }

    private void toggleAdvanced() {
        advancedOpen = !advancedOpen;
        int visibility = advancedOpen ? View.VISIBLE : View.GONE;
        // Guard against a stale/inconsistent layout build where a view id is
        // missing, so toggling the panel can never NPE the app.
        if (configContainer != null) {
            configContainer.setVisibility(visibility);
        }
        if (getUrlBtnView != null) {
            getUrlBtnView.setVisibility(visibility);
        }
        if (advancedToggle != null) {
            advancedToggle.setText(advancedOpen
                    ? "playurl Config  [-]" : "playurl Config  [+]");
        }
    }

    // ---- Playback ----

    /** Play button entry: refreshes the reconnect budget, then (re)starts playback. */
    private void startPlayback() {
        errorRetriesLeft = MAX_ERROR_RETRIES;
        doStartPlayback();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void doStartPlayback() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            toast("Enter a URL first");
            return;
        }
        String lower = url.toLowerCase(Locale.US);
        boolean isUdp = lower.startsWith("udp:") || lower.startsWith("rtp:");

        UdpStreamStats.reset();
        if (isUdp) {
            // Wi-Fi drivers silently drop multicast frames unless a MulticastLock is
            // held; Ethernet devices don't need it but holding it is harmless.
            ensureMulticastLock();
            UdpStreamStats.setTransportLabel("UDP 待连接...");
        } else {
            releaseMulticastLock();
        }

        boolean forceSoftware = forceSoftwareBox.isChecked();
        stats.setForceSoftware(forceSoftware);
        stats.reset();
        fpsCounter.reset();
        playbackStartedAtMs = 0;
        playClickElapsedMs = SystemClock.elapsedRealtime();

        releasePlayer();

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this);
        renderersFactory.setEnableDecoderFallback(true);
        renderersFactory.setExtensionRendererMode(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
        if (forceSoftware) {
            renderersFactory.setMediaCodecSelector(SoftwareCodecSelector.INSTANCE);
            Log.i(TAG, "Using SOFTWARE decode path");
        } else {
            Log.i(TAG, "Using HARDWARE decode path");
        }

        // Live servers commonly throttle/drop clients using the default "ExoPlayer" UA
        // (confirmed: the same playurl plays in VLC, but ExoPlayer got only ~940 bytes
        // before the connection was cut). VLC's UA is accepted, so use it; loosen the
        // read timeout for slow live starts.
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000);
        // Route by URI scheme per open(): http(s) keeps the traced HTTP stack,
        // udp/rtp go to the multicast DataSource, file/content to DefaultDataSource.
        SchemeRoutingDataSource.Factory dataSourceFactory =
                new SchemeRoutingDataSource.Factory(getApplicationContext(),
                        new HttpTraceDataSource.Factory(httpFactory));
        DefaultMediaSourceFactory mediaSourceFactory =
                new DefaultMediaSourceFactory(dataSourceFactory, new DefaultExtractorsFactory());

        Log.i(TAG, "Play URL: " + url + (isUdp ? " [UDP/multicast]" : ""));

        player = new ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();
        player.addListener(playerListener);
        player.addAnalyticsListener(stats);
        player.setVideoFrameMetadataListener(fpsCounter);
        applyMuted();
        playerView.setPlayer(player);

        MediaItem item = MediaItem.fromUri(url);
        player.setMediaItem(item);
        player.prepare();
        player.play();

        mainHandler.removeCallbacks(statsUpdater);
        mainHandler.post(statsUpdater);
    }

    private void stopPlayback() {
        errorRetriesLeft = 0;
        mainHandler.removeCallbacks(reconnectRunnable);
        if (player != null) {
            player.stop();
        }
    }

    private void releasePlayer() {
        if (player != null) {
            player.removeAnalyticsListener(stats);
            player.removeListener(playerListener);
            player.setVideoFrameMetadataListener(null);
            player.release();
            player = null;
            playerView.setPlayer(null);
        }
    }

    /** Error-triggered retry, honoring the Auto Reconnect checkbox and its budget. */
    private void scheduleReconnect() {
        if (errorRetriesLeft <= 0 || autoReconnectBox == null || !autoReconnectBox.isChecked()) {
            return;
        }
        errorRetriesLeft--;
        toast(String.format(Locale.US, "播放出错，%ds 后自动重连（剩 %d 次）",
                RECONNECT_DELAY_MS / 1000, errorRetriesLeft));
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
    }

    private void toggleMute() {
        muted = !muted;
        if (muteBtn != null) {
            muteBtn.setText(muted ? "声音" : "静音");
        }
        applyMuted();
    }

    private void applyMuted() {
        if (player != null) {
            player.setVolume(muted ? 0f : 1f);
        }
    }

    private void ensureMulticastLock() {
        if (multicastLock != null) {
            return;
        }
        try {
            WifiManager wm = getSystemService(WifiManager.class);
            if (wm == null) {
                Log.w(TAG, "no WifiManager; Wi-Fi multicast frames may be filtered");
                return;
            }
            multicastLock = wm.createMulticastLock("mct-udp");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            Log.i(TAG, "multicast lock acquired");
        } catch (Exception e) {
            Log.w(TAG, "multicast lock acquire failed", e);
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock != null) {
            try {
                multicastLock.release();
            } catch (Exception ignore) {
                // already released / state lost
            }
            multicastLock = null;
            Log.i(TAG, "multicast lock released");
        }
    }

    // ---- playurl fetch ----

    private void fetchPlayUrl() {
        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, EditText> e : configFields.entrySet()) {
            params.put(e.getKey(), e.getValue().getText().toString());
        }
        toast("Requesting playurl...");
        playUrlProvider.fetch(params, new PlayUrlProvider.Callback() {
            @Override
            public void onUrl(String url) {
                urlInput.setText(url);
                urlInput.setSelection(url.length());
                toast("Got URL");
            }

            @Override
            public void onError(String message) {
                toast("playurl error: " + message);
            }
        });
    }

    // ---- Export ----

    private void exportLog() {
        ioExecutor.execute(() -> {
            try {
                final java.io.File out = LogExporter.export(this);
                runOnUiThread(() -> toast("Log: " + out.getAbsolutePath()));
            } catch (Exception e) {
                Log.e(TAG, "export log failed", e);
                runOnUiThread(() -> toast("Export log failed: " + e.getMessage()));
            }
        });
    }

    private void exportReport() {
        if (!hasStoragePermission()) {
            pendingExportReport = true;
            requestStoragePermissionIfNeeded();
            return;
        }
        doExportReport();
    }

    private void doExportReport() {
        ioExecutor.execute(() -> {
            try {
                int fps = fpsCounter.getLastFps();
                boolean forceSoftware = forceSoftwareBox.isChecked();
                String url = urlInput.getText().toString();
                final java.io.File out = ReportExporter.export(
                        this, stats, fps, forceSoftware, url, playbackStartedAtMs);
                runOnUiThread(() -> toast("Report: " + out.getAbsolutePath()));
            } catch (Exception e) {
                Log.e(TAG, "export report failed", e);
                runOnUiThread(() -> toast("Export report failed: " + e.getMessage()));
            }
        });
    }

    // ---- Stats rendering ----

    private static final String HEX_ACCENT = "#4D9FFF";
    private static final String HEX_DIM = "#93A0B4";
    private static final String HEX_GREEN = "#3DD68C";
    private static final String HEX_AMBER = "#FFB454";
    private static final String HEX_RED = "#FF6161";

    /** FPS verdict at a glance: ≥23 good (25fps source), 18–22 marginal, below red. */
    @OptIn(markerClass = UnstableApi.class)
    private void updateStats() {
        int fps = fpsCounter.tickAndReset();
        StringBuilder sb = new StringBuilder();

        sb.append(section("DEVICE")).append(esc(deviceBlock)).append('\n');
        sb.append(section("DECODERS")).append(esc(decoderListing)).append('\n');

        sb.append(section("PLAYBACK"));
        sb.append(kv("Transport", esc(UdpStreamStats.getTransportLabel())));
        sb.append(kv("Decoder", esc(stats.getDecoderName())));
        sb.append(kv("MimeType", esc(stats.getMimeType())));
        sb.append(kv("Res", stats.getResolution()));

        String fpsHex = fps <= 0 ? HEX_DIM : fps >= 23 ? HEX_GREEN
                : fps >= 18 ? HEX_AMBER : HEX_RED;
        sb.append(colon("FPS")).append(span(fpsHex, "<b>" + fps + "</b>"))
                .append("  ").append(span(HEX_DIM, "(peak " + fpsCounter.getPeakFps() + ")"))
                .append('\n');

        buildRest(sb);

        // Preserve scroll position across the per-second setText(), otherwise the
        // ScrollView snaps back to the top every refresh.
        int scrollY = statsScroll != null ? statsScroll.getScrollY() : 0;
        statsView.setText(Html.fromHtml(sb.toString(), Html.FROM_HTML_MODE_LEGACY));
        if (statsScroll != null) {
            final int sy = scrollY;
            statsScroll.post(() -> statsScroll.scrollTo(0, sy));
        }
    }

    private void buildRest(StringBuilder sb) {
        int dropped = stats.getDroppedTotal();
        sb.append(kvHtml("Dropped", dropped > 0
                ? span(HEX_RED, "<b>" + dropped + "</b>")
                : span(HEX_DIM, String.valueOf(dropped))));
        sb.append(kv("Bitrate", stats.getBitrate()));

        long firstFrameMs = stats.getFirstFrameRealtimeMs();
        if (firstFrameMs > 0 && playClickElapsedMs > 0) {
            sb.append(kvHtml("FirstFr", span(HEX_DIM,
                    (firstFrameMs - playClickElapsedMs) + " ms")));
        }

        if (UdpStreamStats.isActive()) {
            long delta = UdpStreamStats.takeByteDelta(); // bytes since previous second
            sb.append(kvHtml("UDP RX", span(HEX_ACCENT, delta * 8 / 1000 + " kbps")
                    + span(HEX_DIM, "  total " + UdpStreamStats.getTotalBytes() / 1024 + " KB")));
            long lost = UdpStreamStats.getLostPackets();
            sb.append(kvHtml("UDPPkt", lost > 0
                    ? span(HEX_RED, UdpStreamStats.getTotalPackets() + "  lost <b>" + lost + "</b>")
                    : span(HEX_GREEN, UdpStreamStats.getTotalPackets() + "  lost 0")));
        }

        if (player != null) {
            sb.append(kv("Buffered", player.getBufferedPercentage() + "%  "
                    + formatMs(player.getBufferedPosition())));
            sb.append(kv("Position", formatMs(player.getCurrentPosition())));
            int state = player.getPlaybackState();
            boolean playing = player.isPlaying();
            String hex = playing ? HEX_GREEN
                    : state == Player.STATE_BUFFERING ? HEX_AMBER
                    : state == Player.STATE_READY ? HEX_ACCENT
                    : HEX_RED;
            sb.append(kvHtml("State", span(hex, esc(stateName(state))
                    + (playing ? " [playing]" : ""))));
            applyDot(state, playing);
        } else {
            sb.append(kv("State", "stopped"));
            setDotColor(getColor(R.color.text_dim));
        }
    }

    private void applyDot(int state, boolean playing) {
        int color;
        if (playing) {
            color = getColor(R.color.green);
        } else if (state == Player.STATE_BUFFERING) {
            color = getColor(R.color.amber);
        } else if (state == Player.STATE_READY) {
            color = getColor(R.color.accent);
        } else {
            color = getColor(R.color.red);
        }
        setDotColor(color);
    }

    private void setDotColor(int color) {
        if (statsDot != null) {
            statsDot.setBackgroundTintList(ColorStateList.valueOf(color));
        }
    }

    // ---- tiny html builders for the stats panel ----

    /** Key padded so the ": values" column stays aligned in monospace. */
    private static String colon(String key) {
        StringBuilder b = new StringBuilder(key);
        while (b.length() < 9) {
            b.append(' ');
        }
        return b.append(": ").toString();
    }

    private static String kv(String key, String value) {
        return colon(key) + value + '\n';
    }

    private static String kvHtml(String key, String htmlValue) {
        return colon(key) + htmlValue + '\n';
    }

    private static String span(String hex, String inner) {
        return "<font color='" + hex + "'>" + inner + "</font>";
    }

    private static String section(String title) {
        return span(HEX_ACCENT, "<b>" + title + "</b>") + "  "
                + span("#2A3342", "──────────────") + '\n';
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---- Permissions ----

    private void requestStoragePermissionIfNeeded() {
        if (!hasStoragePermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    }, REQ_STORAGE);
        }
    }

    private boolean hasStoragePermission() {
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && pendingExportReport
                && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pendingExportReport = false;
            doExportReport();
        }
    }

    // ---- Lifecycle ----

    @Override
    protected void onStart() {
        super.onStart();
        mainHandler.removeCallbacks(statsUpdater);
        mainHandler.post(statsUpdater);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mainHandler.removeCallbacks(statsUpdater);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(reconnectRunnable);
        releasePlayer();
        releaseMulticastLock();
    }

    // ---- Helpers ----

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    private static String stateName(int state) {
        switch (state) {
            case Player.STATE_IDLE: return "idle";
            case Player.STATE_BUFFERING: return "buffering";
            case Player.STATE_READY: return "ready";
            case Player.STATE_ENDED: return "ended";
            default: return String.valueOf(state);
        }
    }

    private static String formatMs(long ms) {
        if (ms < 0) {
            return "--:--:--";
        }
        long s = ms / 1000;
        long m = s / 60;
        long h = m / 60;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m % 60, s % 60);
    }
}
