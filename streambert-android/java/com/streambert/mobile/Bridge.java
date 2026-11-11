package com.streambert.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public class Bridge {

    private final MainActivity activity;
    private final BridgeCore core;
    private final BridgePlayer player;
    private final BridgeDownloads downloads;
    private final BridgeSecurity security;
    private final BridgeUpdater updater;

    public Bridge(MainActivity activity) {
        this.activity = activity;
        this.core = new BridgeCore(activity);
        this.player = new BridgePlayer(activity);
        this.downloads = new BridgeDownloads(activity);
        this.security = new BridgeSecurity(activity);
        this.updater = new BridgeUpdater(activity);
    }

    // -- core -------------------------------------------------------------
    @android.webkit.JavascriptInterface
    public String getVersion() { return core.getVersion(); }
    @android.webkit.JavascriptInterface
    public boolean isLowEndDevice() { return core.isLowEndDevice(); }
    @android.webkit.JavascriptInterface
    public void toast(String msg) { core.toast(msg); }
    @android.webkit.JavascriptInterface
    public void openUrl(String url) { core.openUrl(url); }
    @android.webkit.JavascriptInterface
    public void clearCache() { core.clearCache(); }
    @android.webkit.JavascriptInterface
    public void exitApp() { core.exitApp(); }

    // -- player -----------------------------------------------------------
    @android.webkit.JavascriptInterface
    public void openPlayer(String url, String userAgent, String title) {
        player.openPlayer(url, userAgent, title);
    }

    // -- downloads --------------------------------------------------------
    @android.webkit.JavascriptInterface
    public String listDownloads() { return downloads.listDownloads(); }
    @android.webkit.JavascriptInterface
    public boolean deleteDownload(String filePath) { return downloads.deleteDownload(filePath); }
    @android.webkit.JavascriptInterface
    public boolean deleteAllDownloads() { return downloads.deleteAllDownloads(); }
    @android.webkit.JavascriptInterface
    public long getDownloadsSize() { return downloads.getDownloadsSize(); }
    @android.webkit.JavascriptInterface
    public boolean fileExists(String path) { return downloads.fileExists(path); }
    @android.webkit.JavascriptInterface
    public void shareText(String subject, String text) { downloads.shareText(subject, text); }
    @android.webkit.JavascriptInterface
    public boolean openPath(String path) { return downloads.openPath(path); }
    @android.webkit.JavascriptInterface
    public void showInFolder() { downloads.showInFolder(); }
    @android.webkit.JavascriptInterface
    public String downloadCurrent(String expectedTitle) { return downloads.downloadCurrent(expectedTitle); }
    @android.webkit.JavascriptInterface
    public String startSeasonDownload(String json) { return downloads.startSeasonDownload(json); }

    // -- security ---------------------------------------------------------
    @android.webkit.JavascriptInterface
    public String secureGet(String key) { return security.secureGet(key); }
    @android.webkit.JavascriptInterface
    public void secureSet(String key, String value) { security.secureSet(key, value); }
    @android.webkit.JavascriptInterface
    public String hashSecret(String secret) { return security.hashSecret(secret); }
    @android.webkit.JavascriptInterface
    public boolean verifySecret(String candidate, String stored) { return security.verifySecret(candidate, stored); }
    @android.webkit.JavascriptInterface
    public boolean isHashedSecret(String stored) { return security.isHashedSecret(stored); }

    // -- updater ----------------------------------------------------------
    @android.webkit.JavascriptInterface
    public String getUpdateState() { return updater.getUpdateState(); }
    @android.webkit.JavascriptInterface
    public void checkForUpdates() { updater.checkForUpdates(); }
    @android.webkit.JavascriptInterface
    public boolean isAutoUpdateCheck() { return updater.isAutoUpdateCheck(); }
    @android.webkit.JavascriptInterface
    public void setAutoUpdateCheck(boolean enabled) { updater.setAutoUpdateCheck(enabled); }
    @android.webkit.JavascriptInterface
    public void installPendingUpdate() { updater.installPendingUpdate(); }
}
