package com.streambert.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.pm.ApplicationInfo;
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

    // Package-private so the decoupled bridge routers can reach the WebView
    // and the updater without reflection.
    WebView web;
    UpdateManager updater;
    private boolean autoCheckStarted = false;
    private long lastBackPress = 0L;
    private BroadcastReceiver downloadReceiver;
    private ValueCallback<Uri[]> fileChooserCallback;
    private static final int REQUEST_FILE_CHOOSER = 9001;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AdBlock.load(this);
        updater = new UpdateManager(this);

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

        // Decoupled bridge routers: each domain of the native interface lives
        // in its own class and is exposed under its own name so the surface can
        // grow without one giant MainActivity.Bridge.
        // Decoupled bridge routers: each domain of the native interface lives
        // in its own class and is exposed under its own name so the surface can
        // grow without one giant MainActivity.Bridge.
        web.addJavascriptInterface(new BridgeCore(this), "AndroidCore");
        web.addJavascriptInterface(new BridgePlayer(this), "AndroidPlayer");
        web.addJavascriptInterface(new BridgeDownloads(this), "AndroidDownloads");
        web.addJavascriptInterface(new BridgeSecurity(this), "AndroidSecurity");
        web.addJavascriptInterface(new BridgeUpdater(this), "AndroidUpdater");
        web.addJavascriptInterface(new BridgeSubtitles(this), "AndroidSubtitles");
        web.addJavascriptInterface(new BridgeProviders(this), "AndroidProviders");
        // Backward-compatible aggregate facade (holds no logic of its own).
        web.addJavascriptInterface(new Bridge(this), "Android");
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

        // Schedule periodic provider health checks (1 hour interval).
        ProviderHealthJobService.schedule(this, 60 * 60 * 1000L);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // Debugging was left on for every build, which keeps the inspector
            // agent attached in release and costs memory/CPU on weak devices.
            WebView.setWebContentsDebuggingEnabled(
                    (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
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
            // Everything under /assets is content-hashed by the build, so it can
            // never go stale. Letting the WebView keep it removes a full asset
            // re-read (and a JS re-parse) from every launch, which is the most
            // noticeable cost on a slow device. The hand-written files keep
            // revalidating because their names don't change between builds.
            boolean hashed = path.startsWith("/assets/");
            headers.put("Cache-Control",
                    hashed ? "public, max-age=31536000, immutable" : "no-cache");
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
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Low-RAM devices report pressure constantly. Dropping the renderer's
        // cache here is much cheaper than having Android kill the WebView
        // process (and reload the whole app) while the user is browsing.
        if (web != null && level >= TRIM_MEMORY_RUNNING_LOW) web.freeMemory();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (web != null) web.freeMemory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        notifyDownloadsChanged();
        if (updater != null) {
            // Finish an install that was waiting for the "install unknown
            // apps" permission.
            updater.resumePendingInstall();
            if (!autoCheckStarted) {
                autoCheckStarted = true;
                updater.maybeAutoCheck();
            }
        }
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
}
