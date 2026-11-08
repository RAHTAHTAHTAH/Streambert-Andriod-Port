package com.streambert.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    static final String APP_HOST = "streambert.local";
    private static final String PREFS = "streambert_secure";

    private WebView web;
    private SharedPreferences securePrefs;
    private long lastBackPress = 0L;
    private BroadcastReceiver downloadReceiver;
    private ValueCallback<Uri[]> fileChooserCallback;
    private static final int REQUEST_FILE_CHOOSER = 9001;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AdBlock.load(this);
        securePrefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(20, 20, 20));
        window.setNavigationBarColor(Color.rgb(20, 20, 20));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) return null;

                if (APP_HOST.equalsIgnoreCase(uri.getHost())) {
                    return serveAsset(uri.getPath());
                }
                if (!request.isForMainFrame() && AdBlock.isBlocked(uri.toString())) {
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Could not load the app interface.", Toast.LENGTH_LONG).show());
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                notifyDownloadsChanged();
            }
        });

        // The renderer uses window.confirm() (e.g. before deleting a
        // download). Without a WebChromeClient those dialogs are silently
        // dismissed and confirm() returns false, so provide native ones.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message,
                                     final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok,
                                (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message,
                                       final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok,
                                (d, w) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel,
                                (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                                      String defaultValue, final JsPromptResult result) {
                final EditText input = new EditText(MainActivity.this);
                input.setText(defaultValue == null ? "" : defaultValue);
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok,
                                (d, w) -> result.confirm(input.getText().toString()))
                        .setNegativeButton(android.R.string.cancel,
                                (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                    ValueCallback<Uri[]> filePathCallback,
                    WebChromeClient.FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = filePathCallback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, REQUEST_FILE_CHOOSER);
                } catch (Exception e) {
                    fileChooserCallback = null;
                    return false;
                }
                return true;
            }
        });

        registerDownloadReceiver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        if (savedInstanceState != null && web.restoreState(savedInstanceState) != null) {
            // restored
        } else {
            web.loadUrl("https://" + APP_HOST + "/index.html");
        }
    }

    private boolean handleNavigation(Uri uri) {
        if (uri == null) return true;
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
            if (APP_HOST.equalsIgnoreCase(uri.getHost())) return false;
            openExternal(uri);
            return true;
        }
        // Ignore intent://, market://, mailto: and other opaque schemes.
        return true;
    }

    private void openExternal(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Download list refresh
    // ------------------------------------------------------------------

    private void registerDownloadReceiver() {
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                notifyDownloadsChanged();
            }
        };
        IntentFilter filter = new IntentFilter(DownloadService.ACTION_DOWNLOAD_FINISHED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(downloadReceiver, filter);
            }
        } catch (Exception e) {
            downloadReceiver = null;
        }
    }

    /** Ask the renderer to re-read the native download list. */
    private void notifyDownloadsChanged() {
        if (web == null) return;
        web.post(() -> {
            if (web == null) return;
            web.evaluateJavascript(
                    "(function(){try{window.__streambertDownloadsChanged&&window.__streambertDownloadsChanged();}catch(e){}})()",
                    null);
        });
    }

    private static String normalizeTitle(String value) {
        if (value == null) return "";
        String text = value.trim().toLowerCase(Locale.US);
        // Saved files look like "Title-1793847002310.mp4"; strip the numeric
        // suffix and the extension so they can be compared with the title.
        text = text.replaceAll("\\.(mp4|m4ts|ts|m3u8|webm|mkv|mov|avi)$", "");
        text = text.replaceAll("-\\d{10,}$", "");
        // Punctuation (including the "_" the downloader uses for ":") is a
        // word separator, so "Avatar_" and "Avatar:" compare equal.
        text = text.replaceAll("[^a-z0-9]+", " ");
        return text.trim().replaceAll("\\s+", " ");
    }

    private static String sanitizeTitle(String value) {
        String name = value == null ? "video" : value;
        name = name.replaceAll("[\\\\/:*?\"<>|]", " ").trim();
        if (name.isEmpty()) name = "video";
        if (name.length() > 80) name = name.substring(0, 80);
        return name;
    }

    private WebResourceResponse serveAsset(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";
        if (path.contains("..")) return notFound();
        // Strip query-ish leftovers (path already excludes query).
        String assetPath = "www" + path;
        try {
            InputStream stream = getAssets().open(assetPath);
            String mime = mimeType(path);
            String encoding = isText(mime) ? "utf-8" : null;
            WebResourceResponse response = new WebResourceResponse(mime, encoding, stream);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            response.setResponseHeaders(headers);
            return response;
        } catch (IOException e) {
            return notFound();
        }
    }

    private static boolean isText(String mime) {
        return mime.startsWith("text/")
                || mime.contains("javascript")
                || mime.contains("json")
                || mime.contains("svg");
    }

    private static String mimeType(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".bmp")) return "image/bmp";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".otf")) return "font/otf";
        if (p.endsWith(".mp4")) return "video/mp4";
        if (p.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        return "application/octet-stream";
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream("Not found".getBytes()));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        notifyDownloadsChanged();
    }

    @Override
    protected void onDestroy() {
        if (downloadReceiver != null) {
            try {
                unregisterReceiver(downloadReceiver);
            } catch (Exception ignored) {
            }
            downloadReceiver = null;
        }
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        // The renderer is a single-page app, so WebView history never moves.
        // Ask the page to close a modal / leave a detail page first.
        web.evaluateJavascript(
                "(function(){try{return !!(window.__streambertBack && window.__streambertBack());}catch(e){return false;}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String handled) {
                        if ("true".equals(handled)) return;
                        confirmExit();
                    }
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILE_CHOOSER && fileChooserCallback != null) {
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[] { data.getData() };
                }
            }
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void confirmExit() {
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2000) {
            super.onBackPressed();
        } else {
            lastBackPress = now;
            Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show();
        }
    }

    /** JS bridge exposed as window.Android */
    final class Bridge {

        @JavascriptInterface
        public String getVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "2.7.3-mobile";
            }
        }

        @JavascriptInterface
        public String startSeasonDownload(final String json) {
            if (json == null || json.trim().isEmpty()) {
                return "{\"ok\":false,\"reason\":\"empty\"}";
            }

            String show = "";
            int season = 1;
            int count = 0;
            try {
                JSONObject root = new JSONObject(json);
                show = root.optString("show", "");
                season = root.optInt("season", 1);
                JSONArray episodes = root.optJSONArray("episodes");
                count = episodes == null ? 0 : episodes.length();
            } catch (Exception e) {
                return "{\"ok\":false,\"reason\":\"bad_args\"}";
            }
            if (count == 0) return "{\"ok\":false,\"reason\":\"empty\"}";

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 46));
                return "{\"ok\":false,\"reason\":\"permission\"}";
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 47));
            }

            final String payload = json;
            final int episodeCount = count;
            String label = show.trim().isEmpty()
                    ? "Season " + season
                    : show.trim() + " \u2013 Season " + season;
            final String message = "Queue " + episodeCount
                    + (episodeCount == 1 ? " episode" : " episodes")
                    + " of " + label + "?\n\n"
                    + "Episodes download one at a time in the background. "
                    + "This can use a lot of mobile data.";

            runOnUiThread(() -> {
                try {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Download season")
                            .setMessage(message)
                            .setPositiveButton("Download", (dialog, which) -> {
                                Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
                                intent.putExtra(PlayerActivity.EXTRA_SEASON_QUEUE, payload);
                                try {
                                    startActivity(intent);
                                } catch (Exception e) {
                                    Toast.makeText(MainActivity.this,
                                            "Could not open the season downloader",
                                            Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "Could not start the season download", Toast.LENGTH_SHORT).show();
                }
            });

            return "{\"ok\":true,\"count\":" + count + "}";
        }

        @JavascriptInterface
        public void openPlayer(final String url, final String userAgent, final String title) {
            if (url == null) return;
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return;

            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putExtra(PlayerActivity.EXTRA_URL, url);
                i.putExtra(PlayerActivity.EXTRA_UA, userAgent);
                i.putExtra(PlayerActivity.EXTRA_TITLE, title);
                try {
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Could not open player", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void openUrl(final String url) {
            if (url == null) return;
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return;
            runOnUiThread(() -> openExternal(uri));
        }

        @JavascriptInterface
        public void toast(final String msg) {
            if (msg == null) return;
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        // -- downloads ---------------------------------------------------

        /** JSON array of the video files in Download/Streambert. */
        @JavascriptInterface
        public String listDownloads() {
            try {
                return DownloadIndex.listJson(MainActivity.this);
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public boolean deleteDownload(String filePath) {
            try {
                return DownloadIndex.delete(MainActivity.this, filePath);
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean deleteAllDownloads() {
            boolean ok = true;
            try {
                for (DownloadIndex.Entry entry : DownloadIndex.list(MainActivity.this)) {
                    if (!DownloadIndex.delete(MainActivity.this, entry.path)) ok = false;
                }
            } catch (Exception e) {
                ok = false;
            }
            return ok;
        }

        @JavascriptInterface
        public long getDownloadsSize() {
            try {
                return DownloadIndex.totalBytes(MainActivity.this);
            } catch (Exception e) {
                return 0L;
            }
        }

        @JavascriptInterface
        public boolean fileExists(String path) {
            try {
                return DownloadIndex.exists(MainActivity.this, path);
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void shareText(final String subject, final String text) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    if (subject != null) i.putExtra(Intent.EXTRA_SUBJECT, subject);
                    if (text != null) i.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, "Export backup"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Could not share file",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public boolean openPath(String path) {
            Uri uri;
            try {
                uri = DownloadIndex.uriFor(MainActivity.this, path);
            } catch (Exception e) {
                uri = null;
            }
            if (uri == null) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "File not found", Toast.LENGTH_SHORT).show());
                return false;
            }
            final Uri finalUri = uri;
            final String finalMime = DownloadIndex.mimeForPath(path);

            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MainActivity.this, VideoPlayerActivity.class);
                    i.putExtra(VideoPlayerActivity.EXTRA_URI, finalUri.toString());
                    i.putExtra(VideoPlayerActivity.EXTRA_TITLE, path);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    // Fall back to the system player.
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(finalUri, finalMime);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(intent);
                    } catch (Exception ex) {
                        Toast.makeText(MainActivity.this,
                                "No video player found", Toast.LENGTH_LONG).show();
                    }
                }
            });
            return true;
        }

        @JavascriptInterface
        public void showInFolder() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "Saved in Download/Streambert", Toast.LENGTH_LONG).show();
                }
            });
        }

        /**
         * Starts a download of the stream the player last captured. The
         * expected title guards against downloading a previously watched
         * title from a different page.
         */
        @JavascriptInterface
        public String downloadCurrent(String expectedTitle) {
            SharedPreferences prefs = getSharedPreferences(
                    PlayerActivity.PREFS_STREAM, MODE_PRIVATE);
            String url = prefs.getString(PlayerActivity.KEY_URL, null);
            long capturedAt = prefs.getLong(PlayerActivity.KEY_AT, 0L);
            String storedTitle = prefs.getString(PlayerActivity.KEY_TITLE, "");

            if (url == null
                    || System.currentTimeMillis() - capturedAt > 12L * 60L * 60L * 1000L) {
                return "{\"ok\":false,\"reason\":\"no_stream\"}";
            }
            if (expectedTitle != null && !expectedTitle.trim().isEmpty()
                    && !normalizeTitle(storedTitle).equals(normalizeTitle(expectedTitle))) {
                return "{\"ok\":false,\"reason\":\"no_stream\"}";
            }

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 44));
                return "{\"ok\":false,\"reason\":\"permission\"}";
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 45));
            }

            boolean hls = prefs.getBoolean(PlayerActivity.KEY_HLS, false);
            String title = prefs.getString(PlayerActivity.KEY_TITLE, "Streambert");
            String userAgent = prefs.getString(PlayerActivity.KEY_UA, null);
            String referer = prefs.getString(PlayerActivity.KEY_REFERER, "");

            // Do not create duplicates when the user taps download again for a
            // title that is already saved.
            try {
                String wanted = normalizeTitle(sanitizeTitle(title));
                for (DownloadIndex.Entry entry : DownloadIndex.list(MainActivity.this)) {
                    if (normalizeTitle(entry.name).equals(wanted)) {
                        return "{\"ok\":false,\"reason\":\"exists\"}";
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                if (hls) {
                    Intent service = new Intent(MainActivity.this, DownloadService.class);
                    service.putExtra(DownloadService.EXTRA_URL, url);
                    service.putExtra(DownloadService.EXTRA_UA, userAgent);
                    service.putExtra(DownloadService.EXTRA_TITLE, title);
                    service.putExtra(DownloadService.EXTRA_REFERER, referer);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(service);
                    } else {
                        startService(service);
                    }
                } else {
                    String name = sanitizeTitle(title) + "-"
                            + System.currentTimeMillis() + ".mp4";
                    DownloadManager.Request request =
                            new DownloadManager.Request(Uri.parse(url));
                    request.setTitle(title);
                    request.setDescription("Downloading with Streambert");
                    request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, "Streambert/" + name);
                    if (userAgent != null) {
                        request.addRequestHeader("User-Agent", userAgent);
                    }
                    if (referer != null && !referer.isEmpty()) {
                        request.addRequestHeader("Referer", referer);
                    }
                    DownloadManager manager =
                            (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (manager == null) {
                        return "{\"ok\":false,\"reason\":\"unavailable\"}";
                    }
                    manager.enqueue(request);
                }
                return "{\"ok\":true}";
            } catch (Exception e) {
                return "{\"ok\":false,\"reason\":\"error\"}";
            }
        }

        @JavascriptInterface
        public String secureGet(String key) {
            if (key == null) return null;
            return securePrefs.getString("k_" + key, null);
        }

        @JavascriptInterface
        public void secureSet(String key, String value) {
            if (key == null) return;
            if (value == null || value.isEmpty()) {
                securePrefs.edit().remove("k_" + key).apply();
            } else {
                securePrefs.edit().putString("k_" + key, value).apply();
            }
        }

        @JavascriptInterface
        public void clearCache() {
            runOnUiThread(() -> {
                if (web != null) {
                    web.clearCache(true);
                    web.clearFormData();
                }
            });
        }

        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(() -> {
                finishAffinity();
            });
        }
    }
}
