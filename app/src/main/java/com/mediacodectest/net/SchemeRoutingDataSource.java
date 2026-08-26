package com.mediacodectest.net;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Picks the underlying DataSource by URI scheme at open() time:
 *
 *   http / https   -> the traced HTTP stack (HttpTraceDataSource + DefaultHttpDataSource)
 *   udp / rtp      -> UdpMulticastDataSource (raw MPEG-TS over UDP, multicast or unicast)
 *   everything else-> DefaultDataSource (file://, content://, asset, data, ...)
 *                     with a FileDataSource base that fails loudly on exotic schemes
 *
 * ExoPlayer creates one DataSource per media period and reuses it across loads, so this
 * class also owns switching children between opens (e.g. reconnect after an error).
 */
@OptIn(markerClass = UnstableApi.class)
public final class SchemeRoutingDataSource implements DataSource {

    private final Context appContext;
    private final DataSource.Factory httpTracedFactory;
    private final List<TransferListener> listeners = new ArrayList<>();

    @Nullable private DataSource child;

    public SchemeRoutingDataSource(Context context, DataSource.Factory httpTracedFactory) {
        this.appContext = context.getApplicationContext();
        this.httpTracedFactory = httpTracedFactory;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        listeners.add(transferListener);
        if (child != null) {
            child.addTransferListener(transferListener);
        }
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        closeChildQuietly();
        String scheme = dataSpec.uri.getScheme();
        scheme = scheme != null ? scheme.toLowerCase(Locale.US) : "";

        DataSource next;
        switch (scheme) {
            case "udp":
            case "rtp":
                next = new UdpMulticastDataSource();
                break;
            case "http":
            case "https":
                next = httpTracedFactory.createDataSource();
                break;
            default:
                next = new DefaultDataSource(appContext, new FileDataSource());
                break;
        }
        for (TransferListener l : listeners) {
            next.addTransferListener(l);
        }
        child = next;
        return child.open(dataSpec);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        return requireChild().read(buffer, offset, length);
    }

    @Override
    @Nullable
    public Uri getUri() {
        return child != null ? child.getUri() : null;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return child != null ? child.getResponseHeaders()
                : Collections.<String, List<String>>emptyMap();
    }

    @Override
    public void close() throws IOException {
        if (child != null) {
            try {
                child.close();
            } finally {
                child = null;
            }
        }
    }

    private DataSource requireChild() throws IOException {
        if (child == null) {
            throw new IOException("SchemeRoutingDataSource not opened");
        }
        return child;
    }

    private void closeChildQuietly() {
        if (child == null) {
            return;
        }
        try {
            child.close();
        } catch (IOException ignore) {
            // The previous load is dead either way; never mask the new open().
        } finally {
            child = null;
        }
    }

    /** Wraps a base factory so every DataSource ExoPlayer creates routes by scheme. */
    public static final class Factory implements DataSource.Factory {
        private final Context context;
        private final DataSource.Factory httpTracedFactory;

        public Factory(Context context, DataSource.Factory httpTracedFactory) {
            this.context = context.getApplicationContext();
            this.httpTracedFactory = httpTracedFactory;
        }

        @Override
        public DataSource createDataSource() {
            return new SchemeRoutingDataSource(context, httpTracedFactory);
        }
    }
}
