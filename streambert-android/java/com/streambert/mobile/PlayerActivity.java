package com.streambert.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Full-screen native player. Loads the hoster's embed page in a plain WebView
 * (the Android equivalent of Streambert's Electron <webview>) and captures the
 * underlying HLS/MP4 URL so it can be downloaded or copied.
 */
public class PlayerActivity extends Activity {

    static final String EXTRA_URL = "url";
    static final String EXTRA_UA = "ua";
    static final String EXTRA_TITLE = "title";

    // Shared with MainActivity.Bridge so the renderer's download button can
    // start a download of the stream the player last captured.
    static final String PREFS_STREAM = "streambert_last_stream";
    static final String KEY_URL = "url";
    static final String KEY_HLS = "hls";
    static final String KEY_TITLE = "title";
    static final String KEY_UA = "ua";
    static final String KEY_REFERER = "referer";
    static final String KEY_AT = "at";

    private static final String TAG = "StreambertPlayer";
    private static final String CLEAN_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private FrameLayout root;
    private WebView web;
    private LinearLayout topBar;
    private TextView downloadBtn;
    private View errorView;

    private String pageUrl;
    private String userAgent = CLEAN_UA;
    private String title = "Streambert";
    private String referer = "";

    private volatile String lastMediaUrl = null;
    private volatile boolean lastIsHls = false;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private WebChromeClient chrome;

    /** The thin app bar is hidden by default so the hoster's page owns the screen. */
    private boolean topBarVisible = false;
    private final Runnable hideBarRunnable = new Runnable() {
        @Override
        public void run() {
            setTopBarVisible(false);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AdBlock.load(this);

        Intent intent = getIntent();
        pageUrl = intent.getStringExtra(EXTRA_URL);
        String uaExtra = intent.getStringExtra(EXTRA_UA);
        if (uaExtra != null && !uaExtra.trim().isEmpty()) userAgent = uaExtra.trim();
        String titleExtra = intent.getStringExtra(EXTRA_TITLE);
        if (titleExtra != null && !titleExtra.trim().isEmpty()) title = titleExtra.trim();

        if (pageUrl == null) {
            finish();
            return;
        }

        Uri pageUri = Uri.parse(pageUrl);
        if (pageUri.getHost() != null) {
            referer = pageUri.getScheme() + "://" + pageUri.getHost() + "/";
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        buildUi();

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
        s.setUserAgentString(userAgent);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) return null;
                String url = uri.toString();

                if (!request.isForMainFrame() && AdBlock.isBlocked(url)) {
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                captureMedia(url);
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
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                injectPopupBlocker();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectPopupBlocker();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError("Could not load this player page.\n\n" +
                            (error.getDescription() == null ? "" : error.getDescription()));
                }
            }

            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                                           android.net.http.SslError error) {
                handler.cancel();
                toast("Secure connection failed");
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                toast("Player crashed - please reopen it");
                finish();
                return true;
            }
        });

        web.setWebChromeClient(chrome = new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                setTopBarVisible(false);
                hideSystemUi();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                setTopBarVisible(false);
                hideSystemUi();
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                Log.d(TAG, message.message());
                return true;
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", referer);
        web.loadUrl(pageUrl, headers);

        hideSystemUi();

        // Flash the bar once so the tap-to-show interaction is discoverable,
        // then let the hoster's page own the screen.
        setTopBarVisible(true);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // A single tap toggles the thin app bar. Returning false keeps the
        // gesture going to the hoster's page (play/pause, their own controls).
        web.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private long downAt;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        downAt = System.currentTimeMillis();
                        break;
                    case MotionEvent.ACTION_UP:
                        float dx = Math.abs(event.getX() - downX);
                        float dy = Math.abs(event.getY() - downY);
                        if (dx < dp(14) && dy < dp(14)
                                && System.currentTimeMillis() - downAt < 450) {
                            setTopBarVisible(!topBarVisible);
                        }
                        break;
                    default:
                        break;
                }
                return false;
            }
        });

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xCC000000);
        int pad = dp(8);
        topBar.setPadding(pad, pad, pad, pad);

        TextView close = makeButton("\u2715");
        close.setTextSize(20);
        close.setContentDescription("Close player");
        close.setOnClickListener(v -> finish());

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(0xFFF0F0F0);
        titleView.setTextSize(14);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setPadding(dp(12), 0, dp(12), 0);
        titleView.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        downloadBtn = makeButton("\u2913");
        downloadBtn.setTextSize(20);
        downloadBtn.setContentDescription("Download or copy stream link");
        downloadBtn.setVisibility(View.GONE);
        downloadBtn.setOnClickListener(v -> handleDownload());

        TextView browserBtn = makeButton("\u2197");
        browserBtn.setTextSize(18);
        browserBtn.setContentDescription("Open in browser");
        browserBtn.setOnClickListener(v -> openInBrowser());

        topBar.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        topBar.addView(titleView);
        topBar.addView(downloadBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));
        topBar.addView(browserBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(topBar, topParams);

        topBar.setOnApplyWindowInsetsListener((v, insets) -> {
            int left = 0, top = 0, right = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(pad + left, pad + top, pad + right, pad);
            return insets;
        });

        setContentView(root);
    }

    private TextView makeButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(0x22FFFFFF);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Shows or hides the thin app bar. It never covers a server fullscreen
     * video, and auto-hides a few seconds after being revealed.
     */
    private void setTopBarVisible(boolean visible) {
        if (topBar == null) return;
        if (customView != null) visible = false;
        topBarVisible = visible;
        topBar.removeCallbacks(hideBarRunnable);
        topBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) topBar.postDelayed(hideBarRunnable, 3500);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private boolean handleNavigation(Uri uri) {
        if (uri == null) return true;
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            if (AdBlock.isBlocked(uri.toString())) return true;
            return false;
        }
        return true;
    }

    private void captureMedia(String url) {
        if (url == null) return;
        String lower = url.toLowerCase(Locale.US);
        boolean isHls = lower.contains(".m3u8");
        boolean isVideo = lower.matches(".*\\.(mp4|m4v|webm|mkv)(\\?.*)?$");
        if (!isHls && !isVideo) return;

        synchronized (this) {
            if (url.equals(lastMediaUrl)) return;
            lastMediaUrl = url;
            lastIsHls = isHls;
        }

        // Remember the stream so the in-app download button can reuse it after
        // the player has been closed.
        getSharedPreferences(PREFS_STREAM, MODE_PRIVATE).edit()
                .putString(KEY_URL, url)
                .putBoolean(KEY_HLS, isHls)
                .putString(KEY_TITLE, title)
                .putString(KEY_UA, userAgent)
                .putString(KEY_REFERER, referer)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply();

        runOnUiThread(() -> {
            if (downloadBtn != null) downloadBtn.setVisibility(View.VISIBLE);
        });
    }

    private void handleDownload() {
        String url = lastMediaUrl;
        if (url == null) {
            toast("No stream detected yet - start playback first.");
            return;
        }

        if (lastIsHls) {
            startHlsDownload(url);
            return;
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 42);
            toast("Allow storage access, then tap download again.");
            return;
        }

        try {
            String name = sanitize(title) + "-" + System.currentTimeMillis() + ".mp4";
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(title);
            request.setDescription("Downloading with Streambert");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    "Streambert/" + name);
            request.addRequestHeader("User-Agent", userAgent);
            if (!referer.isEmpty()) request.addRequestHeader("Referer", referer);

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) {
                toast("Download manager unavailable");
                return;
            }
            dm.enqueue(request);
            toast("Download started - check your notifications.");
        } catch (Exception e) {
            toast("Download failed: " + e.getMessage());
        }
    }

    /** HLS needs a segment downloader; run it in a foreground service. */
    private void startHlsDownload(String url) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 42);
            toast("Allow storage access, then tap download again.");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 43);
        }

        Intent service = new Intent(this, DownloadService.class);
        service.putExtra(DownloadService.EXTRA_URL, url);
        service.putExtra(DownloadService.EXTRA_UA, userAgent);
        service.putExtra(DownloadService.EXTRA_TITLE, title);
        service.putExtra(DownloadService.EXTRA_REFERER, referer);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            toast("Download started - progress is in the notification.");
        } catch (Exception e) {
            toast("Download failed: " + e.getMessage());
        }
    }

    private static String sanitize(String value) {
        String name = value == null ? "video" : value;
        name = name.replaceAll("[\\\\/:*?\"<>|]", " ").trim();
        if (name.isEmpty()) name = "video";
        if (name.length() > 80) name = name.substring(0, 80);
        return name;
    }

    private void openInBrowser() {
        String url = lastMediaUrl != null ? lastMediaUrl : pageUrl;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("No browser available");
        }
    }

    private void injectPopupBlocker() {
        if (web == null) return;
        web.evaluateJavascript(
                "(function(){try{window.open=function(){return null;};}catch(e){}})();",
                null);
    }

    private void showError(String message) {
        hideError();
        TextView tv = new TextView(this);
        tv.setText(message + "\n\nTap to retry");
        tv.setTextColor(0xFFF0F0F0);
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(32), dp(32), dp(32), dp(32));
        tv.setBackgroundColor(0xEE000000);
        tv.setOnClickListener(v -> {
            hideError();
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", referer);
            web.loadUrl(pageUrl, headers);
        });
        root.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        errorView = tv;
        topBar.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        if (errorView != null) {
            root.removeView(errorView);
            errorView = null;
        }
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
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
        hideSystemUi();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            if (chrome != null) chrome.onHideCustomView();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (customView != null) {
            root.removeView(customView);
            customView = null;
        }
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
