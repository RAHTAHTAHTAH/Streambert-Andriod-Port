package com.streambert.mobile;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native HiAnime resolver. Scrapes HiAnime.to via its Ajax endpoints using
 * HttpURLConnection (no external deps), bypassing WebView CORS limits.
 *
 * Flow:
 *   1. Search HiAnime by title -> first anime slug/id
 *   2. Fetch episode list for that anime -> find matching episode id
 *   3. Fetch servers for that episode -> pick first available server
 *   4. Fetch source link for that server -> embed URL or direct m3u8
 */
public class BridgeAnime {

    private static final String TAG = "BridgeAnime";
    private static final String BASE = "https://hianime.to";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private final MainActivity activity;

    public BridgeAnime(MainActivity activity) {
        this.activity = activity;
    }

    @android.webkit.JavascriptInterface
    public String resolveHiAnime(String paramsJson) {
        String title = "";
        int seasonNumber = 1;
        int episodeNumber = 1;
        boolean isMovie = false;
        String translationType = "sub";
        try {
            JSONObject p = new JSONObject(paramsJson == null ? "{}" : paramsJson);
            title = p.optString("title", "");
            seasonNumber = p.optInt("seasonNumber", 1);
            episodeNumber = p.optInt("episodeNumber", 1);
            isMovie = p.optBoolean("isMovie", false);
            translationType = p.optString("translationType", "sub");
        } catch (Exception e) {
            Log.w(TAG, "bad params", e);
        }

        try {
            // Step 1: search
            String searchUrl = BASE + "/search?keyword=" + URLEncoder.encode(title, "UTF-8");
            String searchHtml = httpGet(searchUrl, null);
            if (searchHtml == null) return fail("Search failed");

            // Find first anime watch link: /watch/{slug}
            Pattern watchPat = Pattern.compile("href=\"/watch/([^\"]+)\"");
            Matcher m = watchPat.matcher(searchHtml);
            if (!m.find()) {
                // try anime link
                Pattern animePat = Pattern.compile("href=\"/anime/([^\"]+)\"");
                m = animePat.matcher(searchHtml);
                if (!m.find()) return fail("No anime found for: " + title);
            }
            String animeSlug = m.group(1);
            // strip query
            int q = animeSlug.indexOf('?');
            if (q > 0) animeSlug = animeSlug.substring(0, q);

            // Step 2: episode list
            String epListUrl = BASE + "/ajax/v2/episode/list/" + animeSlug;
            String epJson = httpGet(epListUrl, "XMLHttpRequest");
            if (epJson == null) return fail("Episode list failed");
            JSONObject epObj = new JSONObject(epJson);
            String epHtml = epObj.optString("html", "");
            if (epHtml.isEmpty()) return fail("No episodes");

            // Find episode id matching episodeNumber
            // Episode links look like: /watch/{slug}?ep={id}  or data-id="{id}"
            // We'll collect all episode entries: number + id
            Pattern epPat = Pattern.compile("href=\"/watch/[^\"]*\\?ep=(\\d+)\"[^>]*>\\s*(?:<[^>]*>)*\\s*(\\d+)");
            // Fallback: data-id
            if (!epPat.matcher(epHtml).find()) {
                epPat = Pattern.compile("data-id=\"(\\d+)\"[^>]*>\\s*(?:<[^>]*>)*\\s*(\\d+)");
            }
            Matcher epm = epPat.matcher(epHtml);
            String episodeId = null;
            while (epm.find()) {
                String id = epm.group(1);
                String numStr = epm.group(2);
                try {
                    int num = Integer.parseInt(numStr.trim());
                    if (num == episodeNumber) {
                        episodeId = id;
                        break;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            // If not found by number, try first episode
            if (episodeId == null) {
                Matcher epm2 = epPat.matcher(epHtml);
                if (epm2.find()) episodeId = epm2.group(1);
            }
            if (episodeId == null) return fail("Episode " + episodeNumber + " not found");

            // Step 3: servers
            String serversUrl = BASE + "/ajax/v2/episode/servers?episodeId=" + episodeId;
            String srvJson = httpGet(serversUrl, "XMLHttpRequest");
            if (srvJson == null) return fail("Servers failed");
            JSONObject srvObj = new JSONObject(srvJson);
            String srvHtml = srvObj.optString("html", "");

            // Find first server data-id
            Pattern srvPat = Pattern.compile("data-id=\"(\\d+)\"");
            Matcher sm = srvPat.matcher(srvHtml);
            if (!sm.find()) return fail("No servers");
            String serverDataId = sm.group(1);

            // Step 4: sources
            String srcUrl = BASE + "/ajax/v2/episode/sources?id=" + serverDataId;
            String srcJson = httpGet(srcUrl, "XMLHttpRequest");
            if (srcJson == null) return fail("Sources failed");
            JSONObject srcObj = new JSONObject(srcJson);
            String link = srcObj.optString("link", "");
            if (link.isEmpty()) {
                link = srcObj.optString("url", "");
            }
            if (link.isEmpty()) return fail("No stream link");

            // Determine if direct
            boolean isDirect = link.contains(".m3u8") || link.contains(".mp4");

            // Subtitle
            String subtitle = null;
            JSONArray tracks = srcObj.optJSONArray("tracks");
            if (tracks != null && tracks.length() > 0) {
                for (int i = 0; i < tracks.length(); i++) {
                    JSONObject t = tracks.optJSONObject(i);
                    if (t != null && "sub".equalsIgnoreCase(t.optString("kind", ""))) {
                        subtitle = t.optString("file", null);
                        break;
                    }
                }
                if (subtitle == null) {
                    JSONObject t = tracks.optJSONObject(0);
                    if (t != null) subtitle = t.optString("file", null);
                }
            }

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("url", link);
            result.put("referer", BASE + "/");
            result.put("subtitle", subtitle);
            result.put("isDirectMp4", isDirect);
            return result.toString();

        } catch (Exception e) {
            Log.e(TAG, "resolve failed", e);
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

    private String httpGet(String urlStr, String requestedWith) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "text/html,application/json,*/*");
            if (requestedWith != null) {
                conn.setRequestProperty("X-Requested-With", requestedWith);
            }
            conn.setRequestProperty("Referer", BASE + "/");
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "HTTP " + code + " for " + urlStr);
                return null;
            }
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "httpGet failed: " + urlStr, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
