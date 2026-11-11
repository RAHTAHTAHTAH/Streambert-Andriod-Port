package com.streambert.mobile;

import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

public class BridgePlayer {

    private final MainActivity activity;

    public BridgePlayer(MainActivity activity) {
        this.activity = activity;
    }

    @android.webkit.JavascriptInterface
    public void openPlayer(final String url, final String userAgent, final String title) {
        if (url == null) return;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return;

        activity.runOnUiThread(() -> {
            Intent i = new Intent(activity, PlayerActivity.class);
            i.putExtra(PlayerActivity.EXTRA_URL, url);
            i.putExtra(PlayerActivity.EXTRA_UA, userAgent);
            i.putExtra(PlayerActivity.EXTRA_TITLE, title);
            try {
                activity.startActivity(i);
            } catch (Exception e) {
                Toast.makeText(activity, "Could not open player", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
