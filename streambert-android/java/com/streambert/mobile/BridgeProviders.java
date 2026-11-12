package com.streambert.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

public class BridgeProviders {

    private final MainActivity activity;

    public BridgeProviders(MainActivity activity) {
        this.activity = activity;
    }

    @android.webkit.JavascriptInterface
    public String getProviderHealth() {
        SharedPreferences prefs = activity.getSharedPreferences(
                ProviderHealthJobService.PREFS, Context.MODE_PRIVATE);
        String results = prefs.getString(ProviderHealthJobService.KEY_RESULTS, "[]");
        long lastCheck = prefs.getLong(ProviderHealthJobService.KEY_LAST_CHECK, 0);
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("results", new JSONArray(results));
            out.put("lastCheck", lastCheck);
            return out.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @android.webkit.JavascriptInterface
    public void checkProvidersNow() {
        new Thread(() -> ProviderHealthJobService.checkProviders(activity)).start();
    }

    @android.webkit.JavascriptInterface
    public String getProviders() {
        SharedPreferences prefs = activity.getSharedPreferences(
                ProviderHealthJobService.PREFS, Context.MODE_PRIVATE);
        String providers = prefs.getString(ProviderHealthJobService.KEY_PROVIDERS, null);
        if (providers == null) {
            JSONArray defaults = new JSONArray();
            for (String p : new String[]{"https://www.videasy.net/", "https://vidking.net/",
                    "https://vidsrc.to/", "https://vidsrc.me/"}) {
                defaults.put(p);
            }
            providers = defaults.toString();
        }
        return providers;
    }

    @android.webkit.JavascriptInterface
    public void setProviders(String json) {
        if (json == null) return;
        try {
            new JSONArray(json); // validate
            activity.getSharedPreferences(ProviderHealthJobService.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(ProviderHealthJobService.KEY_PROVIDERS, json).apply();
        } catch (Exception ignored) {
        }
    }

    @android.webkit.JavascriptInterface
    public void scheduleBackgroundChecks(long intervalMs) {
        ProviderHealthJobService.schedule(activity, intervalMs);
    }

    @android.webkit.JavascriptInterface
    public void cancelBackgroundChecks() {
        ProviderHealthJobService.cancel(activity);
    }
}
