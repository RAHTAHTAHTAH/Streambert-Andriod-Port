package com.streambert.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Native Trakt.tv scrobbling. Uses the OAuth device-code flow (suitable for TV/mobile
 * apps without a redirect URI) and the /scrobble/* endpoints to report playback
 * progress. Credentials (client id/secret) and the access token are stored in
 * SharedPreferences; the user completes authorization via trakt.tv/activate.
 */
public class BridgeTrakt {

    private static final String TAG = "BridgeTrakt";
    private static final String API = "https://api.trakt.tv";
    private static final String PREFS = "streambert_trakt";
    private static final String KEY_CLIENT_ID = "client_id";
    private static final String KEY_CLIENT_SECRET = "client_secret";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_DEVICE_CODE = "device_code";
    private static final String KEY_USER_CODE = "user_code";
    private static final String KEY_VERIFICATION_URL = "verification_url";

    private final MainActivity activity;

    public BridgeTrakt(MainActivity activity) {
        this.activity = activity;
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @android.webkit.JavascriptInterface
    public void setCredentials(String clientId, String clientSecret) {
        prefs().edit()
                .putString(KEY_CLIENT_ID, clientId == null ? "" : clientId.trim())
                .putString(KEY_CLIENT_SECRET, clientSecret == null ? "" : clientSecret.trim())
                .apply();
    }

    @android.webkit.JavascriptInterface
    public String getCredentials() {
        try {
            JSONObject o = new JSONObject();
            o.put("clientId", prefs().getString(KEY_CLIENT_ID, ""));
            o.put("hasSecret", !prefs().getString(KEY_CLIENT_SECRET, "").isEmpty());
            o.put("authorized", !prefs().getString(KEY_ACCESS_TOKEN, "").isEmpty());
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    @android.webkit.JavascriptInterface
    public boolean hasCredentials() {
        return !prefs().getString(KEY_CLIENT_ID, "").isEmpty();
    }

    @android.webkit.JavascriptInterface
    public boolean isAuthorized() {
        return !prefs().getString(KEY_ACCESS_TOKEN, "").isEmpty();
    }

    /**
     * Start device-code OAuth flow. Returns JSON with user_code and verification_url
     * that the user must visit to authorize. Poll authorize() afterwards.
     */
    @android.webkit.JavascriptInterface
    public String requestDeviceCode() {
        String clientId = prefs().getString(KEY_CLIENT_ID, "");
        if (clientId.isEmpty()) return fail("No client id set");
        try {
            JSONObject body = new JSONObject();
            body.put("client_id", clientId);
            String resp = httpPost(API + "/oauth/device/code", body.toString(), null);
            if (resp == null) return fail("Device code request failed");
            JSONObject r = new JSONObject(resp);
            String deviceCode = r.optString("device_code", "");
            String userCode = r.optString("user_code", "");
            String verificationUrl = r.optString("verification_url", "https://trakt.tv/activate");
            int expiresIn = r.optInt("expires_in", 600);
            int interval = r.optInt("interval", 5);
            prefs().edit()
                    .putString(KEY_DEVICE_CODE, deviceCode)
                    .putString(KEY_USER_CODE, userCode)
                    .putString(KEY_VERIFICATION_URL, verificationUrl)
                    .apply();
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("userCode", userCode);
            out.put("verificationUrl", verificationUrl);
            out.put("expiresIn", expiresIn);
            out.put("interval", interval);
            return out.toString();
        } catch (Exception e) {
            Log.e(TAG, "requestDeviceCode failed", e);
            return fail(e.getMessage());
        }
    }

    @android.webkit.JavascriptInterface
    public String getUserCode() {
        return prefs().getString(KEY_USER_CODE, "");
    }

    @android.webkit.JavascriptInterface
    public String getVerificationUrl() {
        String url = prefs().getString(KEY_VERIFICATION_URL, "");
        if (url.isEmpty()) url = "https://trakt.tv/activate";
        return url;
    }

    /**
     * Poll for authorization. Returns {ok:true, authorized:true} once the user
     * completes the flow; {ok:true, authorized:false, pending:true} while waiting;
     * {ok:false, error} on failure/expiry.
     */
    @android.webkit.JavascriptInterface
    public String pollAuthorization() {
        String clientId = prefs().getString(KEY_CLIENT_ID, "");
        String clientSecret = prefs().getString(KEY_CLIENT_SECRET, "");
        String deviceCode = prefs().getString(KEY_DEVICE_CODE, "");
        if (clientId.isEmpty() || deviceCode.isEmpty()) return fail("No pending device code");
        try {
            JSONObject body = new JSONObject();
            body.put("code", deviceCode);
            body.put("client_id", clientId);
            body.put("client_secret", clientSecret);
            String resp = httpPost(API + "/oauth/device/token", body.toString(), null);
            if (resp == null) {
                // Could be pending (400) or expired
                return "{\"ok\":true,\"authorized\":false,\"pending\":true}";
            }
            JSONObject r = new JSONObject(resp);
            if (r.has("access_token")) {
                prefs().edit()
                        .putString(KEY_ACCESS_TOKEN, r.optString("access_token", ""))
                        .putString(KEY_REFRESH_TOKEN, r.optString("refresh_token", ""))
                        .remove(KEY_DEVICE_CODE)
                        .remove(KEY_USER_CODE)
                        .apply();
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("authorized", true);
                return out.toString();
            }
            if (r.has("error")) {
                String err = r.optString("error", "");
                if ("authorization_pending".equals(err) || "slow_down".equals(err)) {
                    return "{\"ok\":true,\"authorized\":false,\"pending\":true}";
                }
                return fail(err);
            }
            return "{\"ok\":true,\"authorized\":false,\"pending\":true}";
        } catch (Exception e) {
            Log.e(TAG, "pollAuthorization failed", e);
            return fail(e.getMessage());
        }
    }

    @android.webkit.JavascriptInterface
    public void disconnect() {
        prefs().edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_DEVICE_CODE)
                .remove(KEY_USER_CODE)
                .apply();
    }

    /**
     * Scrobble a movie/show/episode. type: "movie" or "episode".
     * For episodes, pass showTitle and season/episode numbers.
     * progress: 0-100.
     * action: "start", "pause", or "stop".
     */
    @android.webkit.JavascriptInterface
    public String scrobble(String action, String type, String title, int year,
                           String showTitle, int season, int episode, double progress) {
        String token = prefs().getString(KEY_ACCESS_TOKEN, "");
        String clientId = prefs().getString(KEY_CLIENT_ID, "");
        if (token.isEmpty() || clientId.isEmpty()) return fail("Not authorized");

        try {
            JSONObject media = new JSONObject();
            if ("movie".equals(type)) {
                JSONObject movie = new JSONObject();
                movie.put("title", title);
                if (year > 0) movie.put("year", year);
                media.put("movie", movie);
            } else {
                JSONObject show = new JSONObject();
                show.put("title", showTitle);
                if (year > 0) show.put("year", year);
                JSONObject ep = new JSONObject();
                ep.put("season", season);
                ep.put("number", episode);
                media.put("show", show);
                media.put("episode", ep);
            }
            media.put("progress", progress);

            String endpoint;
            if ("start".equals(action)) endpoint = "/scrobble/start";
            else if ("pause".equals(action)) endpoint = "/scrobble/pause";
            else endpoint = "/scrobble/stop";

            String resp = httpPost(API + endpoint, media.toString(), token, clientId);
            if (resp == null) return fail("Scrobble failed");
            return "{\"ok\":true}";
        } catch (Exception e) {
            Log.e(TAG, "scrobble failed", e);
            return fail(e.getMessage());
        }
    }

    @android.webkit.JavascriptInterface
    public String addToHistory(String type, String title, int year,
                               String showTitle, int season, int episode) {
        String token = prefs().getString(KEY_ACCESS_TOKEN, "");
        String clientId = prefs().getString(KEY_CLIENT_ID, "");
        if (token.isEmpty() || clientId.isEmpty()) return fail("Not authorized");

        try {
            JSONObject item = new JSONObject();
            if ("movie".equals(type)) {
                JSONObject movie = new JSONObject();
                movie.put("title", title);
                if (year > 0) movie.put("year", year);
                item.put("movies", new JSONArray().put(movie));
            } else {
                JSONObject show = new JSONObject();
                show.put("title", showTitle);
                if (year > 0) show.put("year", year);
                JSONObject ep = new JSONObject();
                ep.put("season", season);
                ep.put("number", episode);
                JSONObject entry = new JSONObject();
                entry.put("show", show);
                entry.put("episode", ep);
                item.put("episodes", new JSONArray().put(entry));
            }
            String resp = httpPost(API + "/sync/history", item.toString(), token, clientId);
            if (resp == null) return fail("History add failed");
            return "{\"ok\":true}";
        } catch (Exception e) {
            Log.e(TAG, "addToHistory failed", e);
            return fail(e.getMessage());
        }
    }

    private String fail(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", msg);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + msg + "\"}";
        }
    }

    private String httpPost(String urlStr, String body, String token) {
        return httpPost(urlStr, body, token, null);
    }

    private String httpPost(String urlStr, String body, String token, String clientId) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("trakt-api-version", "2");
            if (clientId != null && !clientId.isEmpty()) {
                conn.setRequestProperty("trakt-api-key", clientId);
            } else {
                String cid = prefs().getString(KEY_CLIENT_ID, "");
                if (!cid.isEmpty()) conn.setRequestProperty("trakt-api-key", cid);
            }
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "HTTP " + code + " for " + urlStr);
                // Read error body
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    Log.w(TAG, "Error body: " + sb.toString());
                } catch (Exception ignored) {}
                return null;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "httpPost failed: " + urlStr, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
        }
}
