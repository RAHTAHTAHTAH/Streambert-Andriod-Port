package com.streambert.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Native subtitle engine.
 *
 * Instead of the desktop's SubDL/Wyzie web scrapers (which break inside a
 * WebView), this class talks to the OpenSubtitles REST API directly from the
 * native layer and caches downloaded subtitle files locally so the player and
 * the renderer can use them.
 *
 * The OpenSubtitles API key is stored in SharedPreferences and can be set by
 * the renderer through {@link #setOpenSubtitlesApiKey(String)}. Without a key
 * the search returns an error explaining how to obtain one.
 */
public class BridgeSubtitles {

    private static final String TAG = "BridgeSubtitles";

    private static final String PREFS = "streambert_subtitles";
    private static final String KEY_API_KEY = "opensubtitles_api_key";
    private static final String CACHE_DIR = "subtitles";

    // OpenSubtitles REST API base URL.
    private static final String OS_BASE = "https://api.opensubtitles.com/api/v1";

    // User agent required by OpenSubtitles (must be registered).
    private static final String USER_AGENT = "Streambert Mobile v1.0";

    private final MainActivity activity;

    public BridgeSubtitles(MainActivity activity) {
        this.activity = activity;
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    @android.webkit.JavascriptInterface
    public void setOpenSubtitlesApiKey(final String key) {
        if (key == null) return;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply();
        activity.runOnUiThread(() ->
                Toast.makeText(activity, "Subtitle API key saved", Toast.LENGTH_SHORT).show());
    }

    @android.webkit.JavascriptInterface
    public String getOpenSubtitlesApiKey() {
        String key = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_API_KEY, "");
        if (key.isEmpty()) return "";
        // Return a masked version so the UI can show it is configured.
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    @android.webkit.JavascriptInterface
    public boolean hasOpenSubtitlesApiKey() {
        String key = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_API_KEY, "");
        return !key.isEmpty();
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    /**
     * Search OpenSubtitles for subtitles matching the given query.
     *
     * @param query    Movie/episode title or IMDB id (e.g. "tt1234567").
     * @param language ISO 639-1 language code (e.g. "en", "es", "fr").
     * @return JSON string: {"ok":true,"results":[{...}]} or {"ok":false,"error":"..."}
     */
    @android.webkit.JavascriptInterface
    public String searchSubtitles(final String query, final String language) {
        String apiKey = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_API_KEY, "");
        if (apiKey.isEmpty()) {
            return jsonError("OpenSubtitles API key not configured. Set it in Settings.");
        }
        if (query == null || query.trim().isEmpty()) {
            return jsonError("Search query is required.");
        }

        HttpURLConnection conn = null;
        try {
            StringBuilder url = new StringBuilder(OS_BASE + "/subtitles?");
            url.append("query=").append(URLEncoder.encode(query.trim(), "UTF-8"));
            if (language != null && !language.trim().isEmpty()) {
                url.append("&languages=").append(URLEncoder.encode(language.trim().toLowerCase(Locale.US), "UTF-8"));
            }

            conn = (HttpURLConnection) new URL(url.toString()).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Api-Key", apiKey);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);

            int code = conn.getResponseCode();
            if (code != 200) {
                return jsonError("OpenSubtitles returned HTTP " + code);
            }

            String body = readStream(conn.getInputStream());
            JSONObject resp = new JSONObject(body);
            JSONArray data = resp.optJSONArray("data");
            if (data == null) {
                return "{\"ok\":true,\"results\":[]}";
            }

            JSONArray results = new JSONArray();
            for (int i = 0; i < data.length() && i < 30; i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                JSONObject attrs = item.optJSONObject("attributes");
                if (attrs == null) continue;

                JSONObject result = new JSONObject();
                result.put("id", item.optString("id", ""));
                result.put("fileId", attrs.optJSONObject("files") != null
                        ? attrs.optJSONObject("files").optInt("file_id", 0) : 0);
                result.put("fileName", attrs.optString("release", ""));
                result.put("language", attrs.optString("language", ""));
                result.put("downloadCount", attrs.optInt("download_count", 0));
                result.put("rating", attrs.optDouble("ratings", 0));
                result.put("uploader", attrs.optString("uploader", ""));
                result.put("featureTitle", attrs.optString("feature_details", ""));
                // feature_details is an object in some responses
                if (attrs.has("feature_details")) {
                    JSONObject fd = attrs.optJSONObject("feature_details");
                    if (fd != null) {
                        result.put("featureTitle", fd.optString("movie_name", fd.optString("title", "")));
                        result.put("year", fd.optInt("year", 0));
                        result.put("season", fd.optInt("season_number", 0));
                        result.put("episode", fd.optInt("episode_number", 0));
                    }
                }
                results.put(result);
            }

            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("results", results);
            return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "searchSubtitles failed", e);
            return jsonError("Subtitle search failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // Download URL
    // ------------------------------------------------------------------

    /**
     * Request a download link for a subtitle file from OpenSubtitles.
     *
     * @param fileId OpenSubtitles file_id.
     * @return JSON: {"ok":true,"url":"...","fileName":"..."} or {"ok":false,"error":"..."}
     */
    @android.webkit.JavascriptInterface
    public String getSubtitleDownloadUrl(final int fileId) {
        String apiKey = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_API_KEY, "");
        if (apiKey.isEmpty()) {
            return jsonError("OpenSubtitles API key not configured.");
        }
        if (fileId <= 0) {
            return jsonError("Invalid file id.");
        }

        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("file_id", fileId);

            conn = (HttpURLConnection) new URL(OS_BASE + "/download").openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Api-Key", apiKey);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);

            java.io.OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code != 200) {
                return jsonError("OpenSubtitles download returned HTTP " + code);
            }

            String respBody = readStream(conn.getInputStream());
            JSONObject resp = new JSONObject(respBody);
            String link = resp.optString("link", "");
            String fileName = resp.optString("file_name", "subtitle.srt");

            if (link.isEmpty()) {
                return jsonError("No download link returned.");
            }

            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("url", link);
            out.put("fileName", fileName);
            return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "getSubtitleDownloadUrl failed", e);
            return jsonError("Failed to get download link: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // Download and cache
    // ------------------------------------------------------------------

    /**
     * Download a subtitle file and cache it locally.
     *
     * @param fileId   OpenSubtitles file_id.
     * @param fileName Desired file name (e.g. "Movie.en.srt").
     * @return JSON: {"ok":true,"path":"...","fileName":"..."} or {"ok":false,"error":"..."}
     */
    @android.webkit.JavascriptInterface
    public String downloadSubtitle(final int fileId, final String fileName) {
        String urlResult = getSubtitleDownloadUrl(fileId);
        try {
            JSONObject parsed = new JSONObject(urlResult);
            if (!parsed.optBoolean("ok", false)) {
                return urlResult;
            }
            String link = parsed.optString("url", "");
            String name = parsed.optString("fileName", fileName != null ? fileName : "subtitle.srt");
            if (fileName != null && !fileName.trim().isEmpty()) {
                name = fileName.trim();
            }

            // Cache directory.
            File cacheDir = new File(activity.getFilesDir(), CACHE_DIR);
            if (!cacheDir.exists()) cacheDir.mkdirs();

            File outFile = new File(cacheDir, sanitizeFileName(name));

            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(link).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);

                int code = conn.getResponseCode();
                if (code != 200) {
                    return jsonError("Download returned HTTP " + code);
                }

                InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
                out.close();
                in.close();

                JSONObject result = new JSONObject();
                result.put("ok", true);
                result.put("path", outFile.getAbsolutePath());
                result.put("fileName", outFile.getName());
                result.put("size", outFile.length());
                return result.toString();
            } finally {
                if (conn != null) conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "downloadSubtitle failed", e);
            return jsonError("Subtitle download failed: " + e.getMessage());
        }
    }

    /**
     * List cached subtitle files.
     *
     * @return JSON: {"ok":true,"files":[{"name":"...","path":"...","size":123}]}
     */
    @android.webkit.JavascriptInterface
    public String listCachedSubtitles() {
        File cacheDir = new File(activity.getFilesDir(), CACHE_DIR);
        JSONArray files = new JSONArray();
        if (cacheDir.exists() && cacheDir.isDirectory()) {
            File[] list = cacheDir.listFiles();
            if (list != null) {
                for (File f : list) {
                    if (!f.isFile()) continue;
                    try {
                        JSONObject entry = new JSONObject();
                        entry.put("name", f.getName());
                        entry.put("path", f.getAbsolutePath());
                        entry.put("size", f.length());
                        entry.put("modified", f.lastModified());
                        files.put(entry);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("files", files);
            return out.toString();
        } catch (Exception e) {
            return jsonError("Failed to list subtitles: " + e.getMessage());
        }
    }

    /**
     * Delete a cached subtitle file.
     *
     * @param fileName Name of the file to delete.
     * @return true if deleted.
     */
    @android.webkit.JavascriptInterface
    public boolean deleteSubtitle(final String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return false;
        File cacheDir = new File(activity.getFilesDir(), CACHE_DIR);
        File target = new File(cacheDir, sanitizeFileName(fileName.trim()));
        // Prevent path traversal.
        if (!target.getParentFile().equals(cacheDir)) return false;
        return target.delete();
    }

    /**
     * Get the absolute path of a cached subtitle file.
     *
     * @param fileName Name of the file.
     * @return Path string or null.
     */
    @android.webkit.JavascriptInterface
    public String getSubtitlePath(final String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return null;
        File cacheDir = new File(activity.getFilesDir(), CACHE_DIR);
        File target = new File(cacheDir, sanitizeFileName(fileName.trim()));
        if (!target.getParentFile().equals(cacheDir)) return null;
        if (!target.exists()) return null;
        return target.getAbsolutePath();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String sanitizeFileName(String name) {
        if (name == null) return "subtitle.srt";
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safe.isEmpty()) safe = "subtitle.srt";
        if (safe.length() > 120) safe = safe.substring(0, 120);
        return safe;
    }

    private static String jsonError(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", msg);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + msg.replace("\"", "'") + "\"}";
        }
    }

    private static String readStream(InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }
}
