package com.streambert.mobile;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

public class BridgeCore {

    private final MainActivity activity;

    public BridgeCore(MainActivity activity) {
        this.activity = activity;
    }

    @android.webkit.JavascriptInterface
    public String getVersion() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "2.7.3-mobile";
        }
    }

    @android.webkit.JavascriptInterface
    public boolean isLowEndDevice() {
        return DeviceProfile.isLowEnd(activity);
    }

    @android.webkit.JavascriptInterface
    public void toast(final String msg) {
        if (msg == null) return;
        activity.runOnUiThread(() -> Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show());
    }

    @android.webkit.JavascriptInterface
    public void openUrl(final String url) {
        if (url == null) return;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return;
        activity.runOnUiThread(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, uri);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(i);
            } catch (Exception ignored) {
            }
        });
    }

    @android.webkit.JavascriptInterface
    public void clearCache() {
        activity.runOnUiThread(() -> {
            if (activity.web != null) {
                activity.web.clearCache(true);
                activity.web.clearFormData();
            }
        });
    }

    @android.webkit.JavascriptInterface
    public void exitApp() {
        activity.runOnUiThread(() -> activity.finishAffinity());
    }
}
