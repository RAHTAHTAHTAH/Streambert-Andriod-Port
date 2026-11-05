package com.streambert.mobile;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Small host-based ad/tracker blocker. The pattern list is the same one used
 * by Streambert's Electron main process (assets/ad_hosts.txt).
 */
final class AdBlock {

    private static final Set<String> EXACT = new HashSet<>();
    private static final Set<String> SUFFIX = new HashSet<>();
    private static boolean loaded = false;

    private AdBlock() {}

    static synchronized void load(Context context) {
        if (loaded) return;
        loaded = true;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open("ad_hosts.txt"), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("*.")) {
                    SUFFIX.add(line.substring(2).toLowerCase(Locale.US));
                } else {
                    EXACT.add(line.toLowerCase(Locale.US));
                }
            }
        } catch (Exception ignored) {
            // Blocking is best-effort; never let it break playback.
        }
    }

    static boolean isBlocked(String url) {
        if (url == null || url.isEmpty()) return false;
        String host;
        try {
            host = Uri.parse(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase(Locale.US);

        if (EXACT.contains(host)) return true;
        for (String suffix : SUFFIX) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
        }
        return false;
    }
}
