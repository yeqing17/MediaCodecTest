package com.mediacodectest.net;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Calls the ICC playurl endpoint and extracts the playable URL. The response shape is
 * not documented, so extraction tries the common keys (playurl / playUrl / url) at the
 * top level and under a nested "data" object, then falls back to a raw URL string.
 */
public class PlayUrlProvider {

    private static final String TAG = "MCT";
    private static final String BASE_URL =
            "http://httpicc.slave.tsyrmt.cn:14311/playurl";

    public interface Callback {
        void onUrl(String url);
        void onError(String message);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public void fetch(Map<String, String> params, Callback callback) {
        executor.execute(() -> {
            String fullUrl = buildUrl(params);
            Log.i(TAG, "playurl request: " + fullUrl);
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(fullUrl).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod("GET");
                conn.setDoInput(true);

                int code = conn.getResponseCode();
                String body = readFully(conn);
                Log.i(TAG, "playurl response (" + code + "): " + truncate(body, 500));

                if (code < 200 || code >= 300) {
                    postError(callback, "HTTP " + code);
                    return;
                }
                String url = extractUrl(body);
                if (url == null) {
                    postError(callback, "No URL in response");
                    return;
                }
                final String result = url;
                post(callback, () -> callback.onUrl(result));
            } catch (Exception e) {
                Log.e(TAG, "playurl failed", e);
                postError(callback, e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private void postError(Callback callback, String message) {
        final String msg = message;
        post(callback, () -> callback.onError(msg));
    }

    private void post(Callback callback, Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    private String buildUrl(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return BASE_URL;
        }
        StringBuilder sb = new StringBuilder(BASE_URL);
        char sep = BASE_URL.indexOf('?') >= 0 ? '&' : '?';
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            sb.append(sep)
                    .append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(nullToEmpty(e.getValue()), StandardCharsets.UTF_8));
            sep = '&';
        }
        return sb.toString();
    }

    private String extractUrl(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Plain URL string.
        if (trimmed.startsWith("http://") || trimmed.startsWith("rtsp://")
                || trimmed.startsWith("https://") || trimmed.startsWith("rtmp://")) {
            return trimmed;
        }
        try {
            JSONObject json = new JSONObject(trimmed);
            String[] topKeys = {"playurl", "playUrl", "url", "playURL"};
            for (String key : topKeys) {
                if (json.has(key)) {
                    String v = json.optString(key, null);
                    if (v != null && !v.isEmpty()) {
                        return v;
                    }
                }
            }
            if (json.has("data") && json.opt("data") instanceof JSONObject) {
                JSONObject data = json.getJSONObject("data");
                for (String key : topKeys) {
                    if (data.has(key)) {
                        String v = data.optString(key, null);
                        if (v != null && !v.isEmpty()) {
                            return v;
                        }
                    }
                }
            }
            // Last resort: scan for the first http URL substring.
            int idx = trimmed.indexOf("http://");
            if (idx >= 0) {
                return trimmed.substring(idx).split("\"|'|<|\\s")[0];
            }
        } catch (Exception ignored) {
            int idx = trimmed.indexOf("http://");
            if (idx >= 0) {
                return trimmed.substring(idx).split("\"|'|<|\\s")[0];
            }
        }
        return null;
    }

    private String readFully(HttpURLConnection conn) throws Exception {
        java.io.InputStream is = null;
        try {
            is = conn.getInputStream();
        } catch (Exception e) {
            is = conn.getErrorStream();
        }
        if (is == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public static Map<String, String> defaultParamKeys() {
        // Order preserved for the UI. Defaults taken from a captured ICC live
        // session (noplaycube.log): live stream over plain HTTP, ICC multicast rate.
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("playtype", "live");
        keys.put("protocol", "http");
        keys.put("verifycode", "");
        keys.put("accesstoken", "");
        keys.put("programid", "");
        keys.put("playtoken", "");
        // ICC rate may be a plain speed (1) or an "icc@@<multicast-url>" value.
        keys.put("rate", "icc@@udp://238.1.1.1:5000");
        keys.put("icctrial", "0");
        keys.put("tick", "");
        return keys;
    }
}
