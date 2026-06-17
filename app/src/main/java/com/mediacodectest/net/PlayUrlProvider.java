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
 * Resolves a playable live URL via the homed 3-step flow (see 获取播放链接的流程.md):
 *
 *   1. account/login          -> access_token + device_id (device_id becomes verifycode)
 *   2. media/channel/get_info -> play_token
 *   3. assemble the final playurl with protocol=http & playtype=live
 *
 * Only account / deviceno / chnlid come from the user; every other request
 * parameter is fixed per the captured session.
 */
public class PlayUrlProvider {

    private static final String TAG = "MCT";

    private static final String LOGIN_URL =
            "http://access.tsyrmt.cn/account/login";
    private static final String CHANNEL_INFO_URL =
            "http://slave.tsyrmt.cn/media/channel/get_info";
    private static final String PLAYURL_URL =
            "http://stream.live.slave.tsyrmt.cn:14311/playurl";

    // Fixed login params from the captured request. Only account + deviceno vary.
    private static final String LOGIN_FIXED_PARAMS =
            "&accounttype=2&accesstoken=&devicetype=1&grouptype=7"
                    + "&pwd=96e79218965eb72c92a549dd5a330112"
                    + "&systeminfo=1&needoperator=1&isforce=1";

    public interface Callback {
        void onUrl(String url);
        void onError(String message);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public void fetch(Map<String, String> params, Callback callback) {
        executor.execute(() -> {
            try {
                String account = param(params, "account");
                String deviceno = param(params, "deviceno");
                String chnlid = param(params, "chnlid");
                if (account.isEmpty() || deviceno.isEmpty() || chnlid.isEmpty()) {
                    postError(callback, "account / deviceno / chnlid 不能为空");
                    return;
                }

                // Step 1: login -> access_token + device_id (verifycode)
                JSONObject login = getJson(buildLoginUrl(account, deviceno));
                if (login.optInt("ret", -1) != 0) {
                    postError(callback, "登录失败: ret=" + login.optInt("ret", -1)
                            + " " + login.optString("ret_msg"));
                    return;
                }
                String accessToken = login.optString("access_token", "");
                long deviceId = login.optLong("device_id", -1);
                if (accessToken.isEmpty() || deviceId <= 0) {
                    postError(callback, "登录返回缺少 access_token / device_id");
                    return;
                }
                Log.i(TAG, "playurl step1 ok: access_token=" + truncate(accessToken, 32)
                        + " device_id=" + deviceId);

                // Step 2: channel get_info -> play_token
                JSONObject info = getJson(buildChannelInfoUrl(accessToken, chnlid));
                if (info.optInt("ret", -1) != 0) {
                    postError(callback, "获取频道信息失败: ret=" + info.optInt("ret", -1)
                            + " " + info.optString("ret_msg"));
                    return;
                }
                String playToken = info.optString("play_token", "");
                if (playToken.isEmpty()) {
                    postError(callback, "频道信息返回缺少 play_token");
                    return;
                }
                Log.i(TAG, "playurl step2 ok: play_token=" + playToken);

                // Step 3: assemble final playurl
                String finalUrl = buildPlayUrl(accessToken, chnlid, playToken, deviceId);
                Log.i(TAG, "playurl step3 ok: " + finalUrl);
                final String result = finalUrl;
                post(callback, () -> callback.onUrl(result));
            } catch (Exception e) {
                Log.e(TAG, "playurl failed", e);
                postError(callback, e.getMessage());
            }
        });
    }

    // ---- URL assembly ----

    private String buildLoginUrl(String account, String deviceno) {
        return LOGIN_URL + "?account=" + enc(account) + "&deviceno=" + enc(deviceno)
                + LOGIN_FIXED_PARAMS;
    }

    private String buildChannelInfoUrl(String accessToken, String chnlid) {
        return CHANNEL_INFO_URL + "?accesstoken=" + enc(accessToken)
                + "&postersize=0&livesize=0&chnlid=" + enc(chnlid);
    }

    private String buildPlayUrl(String accessToken, String chnlid,
                                String playToken, long deviceId) {
        return PLAYURL_URL + "?protocol=http&playtype=live"
                + "&accesstoken=" + enc(accessToken)
                + "&programid=" + enc(chnlid)
                + "&playtoken=" + enc(playToken)
                + "&verifycode=" + deviceId;
    }

    // ---- HTTP ----

    private JSONObject getJson(String fullUrl) throws Exception {
        HttpURLConnection conn = null;
        try {
            Log.i(TAG, "playurl request: " + fullUrl);
            conn = (HttpURLConnection) new URL(fullUrl).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            conn.setDoInput(true);

            int code = conn.getResponseCode();
            String body = readFully(conn);
            Log.i(TAG, "playurl response (" + code + "): " + truncate(body, 500));

            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code);
            }
            return new JSONObject(body.trim());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
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

    // ---- Threading helpers ----

    private void postError(Callback callback, String message) {
        final String msg = message;
        post(callback, () -> callback.onError(msg));
    }

    private void post(Callback callback, Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    // ---- Misc ----

    private static String param(Map<String, String> params, String key) {
        String v = params == null ? null : params.get(key);
        return v == null ? "" : v.trim();
    }

    private static String enc(String s) {
        return URLEncoder.encode(nullToEmpty(s), StandardCharsets.UTF_8);
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

    /**
     * The three user inputs for the playurl Config panel, in UI order.
     * Defaults are the example values from 获取播放链接的流程.md so the panel
     * works out of the box; swap them for the account/device/channel under test.
     */
    public static Map<String, String> defaultParamKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("account", "760053843406");
        keys.put("deviceno", "08091b11000010000011221802000017");
        keys.put("chnlid", "4200000953");
        return keys;
    }
}
