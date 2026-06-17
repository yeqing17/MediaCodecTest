package com.mediacodectest;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
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

    private EditText urlInput;
    private CheckBox forceSoftwareBox;
    private Spinner presetSpinner;
    private TextView advancedToggle;
    private View configContainer;
    private View getUrlBtnView;
    private boolean advancedOpen = false;
    private PlayerView playerView;
    private TextView statsView;
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
    private boolean pendingExportReport = false;

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
        presetSpinner = findViewById(R.id.presetSpinner);
        advancedToggle = findViewById(R.id.advancedToggle);
        configContainer = findViewById(R.id.configContainer);
        getUrlBtnView = findViewById(R.id.getUrlBtn);
        playerView = findViewById(R.id.playerView);
        statsView = findViewById(R.id.statsView);

        buildConfigFields();
        bindButtons();

        setupPresets();

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
                if (position >= 0 && position < urls.length && urls[position].length() > 0) {
                    urlInput.setText(urls[position]);
                    urlInput.setSelection(urls[position].length());
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

            TextView label = new TextView(this);
            label.setText(e.getKey());
            label.setTextSize(12);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            label.setPadding(0, 0, 12, 0);
            row.addView(field, lp);

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = 4;
            container.addView(row, rowLp);

            configFields.put(e.getKey(), field);
        }
    }

    private void bindButtons() {
        findViewById(R.id.playBtn).setOnClickListener(v -> startPlayback());
        findViewById(R.id.stopBtn).setOnClickListener(v -> stopPlayback());
        findViewById(R.id.getUrlBtn).setOnClickListener(v -> fetchPlayUrl());
        findViewById(R.id.exportLogBtn).setOnClickListener(v -> exportLog());
        findViewById(R.id.exportReportBtn).setOnClickListener(v -> exportReport());
        advancedToggle.setOnClickListener(v -> toggleAdvanced());
    }

    private void toggleAdvanced() {
        advancedOpen = !advancedOpen;
        int visibility = advancedOpen ? View.VISIBLE : View.GONE;
        configContainer.setVisibility(visibility);
        getUrlBtnView.setVisibility(visibility);
        advancedToggle.setText(advancedOpen
                ? "playurl Config  [-]" : "playurl Config  [+]");
    }

    // ---- Playback ----

    @OptIn(markerClass = UnstableApi.class)
    private void startPlayback() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            toast("Enter a URL first");
            return;
        }
        boolean forceSoftware = forceSoftwareBox.isChecked();
        stats.setForceSoftware(forceSoftware);
        stats.reset();
        fpsCounter.reset();
        playbackStartedAtMs = 0;

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

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory();
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this,
                new HttpTraceDataSource.Factory(httpFactory));
        DefaultMediaSourceFactory mediaSourceFactory =
                new DefaultMediaSourceFactory(dataSourceFactory, new DefaultExtractorsFactory());

        Log.i(TAG, "Play URL: " + url);

        player = new ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();
        player.addListener(playerListener);
        player.addAnalyticsListener(stats);
        player.setVideoFrameMetadataListener(fpsCounter);
        playerView.setPlayer(player);

        MediaItem item = MediaItem.fromUri(url);
        player.setMediaItem(item);
        player.prepare();
        player.play();

        mainHandler.removeCallbacks(statsUpdater);
        mainHandler.post(statsUpdater);
    }

    private void stopPlayback() {
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

    @OptIn(markerClass = UnstableApi.class)
    private void updateStats() {
        int fps = fpsCounter.tickAndReset();
        StringBuilder sb = new StringBuilder();

        sb.append("=== Device ===\n").append(deviceBlock).append('\n');
        sb.append("=== Decoders ===\n").append(decoderListing).append('\n');

        sb.append("=== Playback ===\n");
        sb.append("Decoder  : ").append(stats.getDecoderName()).append('\n');
        sb.append("MimeType : ").append(stats.getMimeType()).append('\n');
        sb.append("Res      : ").append(stats.getResolution()).append('\n');
        sb.append("FPS      : ").append(fps)
                .append("  (peak ").append(fpsCounter.getPeakFps()).append(")\n");
        sb.append("Dropped  : ").append(stats.getDroppedTotal()).append('\n');
        sb.append("Bitrate  : ").append(stats.getBitrate()).append('\n');

        if (player != null) {
            sb.append("Buffered : ").append(player.getBufferedPercentage()).append("%  ");
            sb.append(formatMs(player.getBufferedPosition())).append('\n');
            sb.append("Position : ").append(formatMs(player.getCurrentPosition())).append('\n');
            int state = player.getPlaybackState();
            sb.append("State    : ").append(stateName(state));
            if (player.isPlaying()) {
                sb.append(" [playing]");
            }
            sb.append('\n');
        }

        statsView.setText(sb.toString());
    }

    // ---- Permissions ----

    private void requestStoragePermissionIfNeeded() {
        if (!hasStoragePermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
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
        releasePlayer();
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
