package com.streambert.mobile;

import android.widget.Toast;

public class BridgeUpdater {

    private final MainActivity activity;

    public BridgeUpdater(MainActivity activity) {
        this.activity = activity;
    }

    @android.webkit.JavascriptInterface
    public String getUpdateState() {
        return activity.updater == null ? "{}" : activity.updater.stateJson();
    }

    @android.webkit.JavascriptInterface
    public void checkForUpdates() {
        if (activity.updater == null) return;
        activity.runOnUiThread(() -> activity.updater.check(true));
    }

    @android.webkit.JavascriptInterface
    public boolean isAutoUpdateCheck() {
        return activity.updater != null && activity.updater.isAutoCheckEnabled();
    }

    @android.webkit.JavascriptInterface
    public void setAutoUpdateCheck(final boolean enabled) {
        if (activity.updater == null) return;
        activity.updater.setAutoCheckEnabled(enabled);
    }

    @android.webkit.JavascriptInterface
    public void installPendingUpdate() {
        if (activity.updater == null) return;
        activity.runOnUiThread(() -> activity.updater.installPending());
    }
}
