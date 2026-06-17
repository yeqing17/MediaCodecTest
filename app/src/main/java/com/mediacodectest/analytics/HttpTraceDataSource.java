package com.mediacodectest.analytics;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Wraps a {@link DataSource} to log what ExoPlayer actually pulls from the playurl
 * endpoint: request URI, redirected URI, HTTP status, Content-Type and the first few
 * hundred bytes of the body. This is what tells us whether the URL returns a media
 * stream or (e.g.) a JSON error / token-expired body when playback stalls on buffering.
 */
@OptIn(markerClass = UnstableApi.class)
public final class HttpTraceDataSource implements DataSource {

    private static final String TAG = "MCT";
    private static final int BODY_HEAD = 512;
    private static final int READ_LOG_INTERVAL = 256 * 1024;

    private final DataSource delegate;
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    @Nullable private DataSpec dataSpec;
    private long bytesRead = 0;

    public HttpTraceDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        delegate.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        this.body.reset();
        this.bytesRead = 0;
        long declared = delegate.open(dataSpec);
        Uri requested = dataSpec.uri;
        Uri actual = delegate.getUri();
        Log.i(TAG, "HTTP open: " + requested
                + (actual != null && !actual.equals(requested) ? " -> " + actual : "")
                + " | status=" + status()
                + " | type=" + header("Content-Type")
                + " | declaredLen=" + (declared == C.LENGTH_UNSET ? "unknown" : declared));
        // Hide the server's bogus placeholder length (10TB on this live source) from
        // ExoPlayer: report unknown length so it treats this as a live stream and keeps
        // reading until EOF, instead of waiting for 10TB / doing VOD-style seeking.
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = delegate.read(buffer, offset, length);
        if (read > 0) {
            long before = bytesRead;
            bytesRead += read;
            int keep = Math.min(read, BODY_HEAD - body.size());
            if (keep > 0) {
                body.write(buffer, offset, keep);
            }
            if (bytesRead / READ_LOG_INTERVAL != before / READ_LOG_INTERVAL) {
                Log.i(TAG, "HTTP read: " + (dataSpec != null ? dataSpec.uri : "?")
                        + " totalBytes=" + bytesRead);
            }
        }
        return read;
    }

    @Override
    public void close() throws IOException {
        Uri uri = dataSpec != null ? dataSpec.uri : null;
        try {
            delegate.close();
        } finally {
            Log.i(TAG, "HTTP close: " + uri
                    + " | bytes=" + bytesRead
                    + " | type=" + header("Content-Type")
                    + " | status=" + status()
                    + " | bodyHead=" + ascii(body.toByteArray()));
        }
    }

    @Nullable
    @Override
    public Uri getUri() {
        return delegate.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return delegate.getResponseHeaders();
    }

    // ---- helpers ----

    private String status() {
        return delegate instanceof HttpDataSource
                ? String.valueOf(((HttpDataSource) delegate).getResponseCode())
                : "-";
    }

    @Nullable
    private String header(String name) {
        Map<String, List<String>> headers = delegate.getResponseHeaders();
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                List<String> v = e.getValue();
                return (v == null || v.isEmpty()) ? null : v.get(0);
            }
        }
        return null;
    }

    private static String ascii(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "<empty>";
        }
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int c = b & 0xFF;
            sb.append(c >= 0x20 && c < 0x7F ? (char) c : '.');
        }
        return sb.toString();
    }

    /** Wraps a base factory so every DataSource ExoPlayer creates is traced. */
    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory base;

        public Factory(DataSource.Factory base) {
            this.base = base;
        }

        @Override
        public DataSource createDataSource() {
            return new HttpTraceDataSource(base.createDataSource());
        }
    }
}
