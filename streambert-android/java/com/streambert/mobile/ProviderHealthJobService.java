package com.streambert.mobile;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Background worker that periodically checks stream provider (hoster) URLs for
 * reachability. Uses Android's built-in {@link JobService} (available since
 * API 21) so no external dependencies (WorkManager/AndroidX) are required.
 *
 * Results are cached in SharedPreferences as JSON and exposed to the renderer
 * through {@link BridgeProviders}.
 */
public class ProviderHealthJobService extends JobService {

    private static final String TAG = "ProviderHealthJob";

    static final String PREFS = "streambert_provider_health";
    static final String KEY_PROVIDERS = "providers";   // JSON array of provider URLs
    static final String KEY_RESULTS = "results";     // JSON array of health results
    static final String KEY_LAST_CHECK = "last_check";

    // Default providers to monitor.
    private static final String[] DEFAULT_PROVIDERS = {
            "https://www.videasy.net/",
            "https://vidking.net/",
            "https://vidsrc.to/",
            "https://vidsrc.me/",
    };

    @Override
    public boolean onStartJob(JobParameters params) {
        // Run the network checks on a background thread.
        new Thread(() -> {
            try {
                checkProviders(getApplicationContext());
            } catch (Exception e) {
                Log.w(TAG, "Provider health check failed", e);
            } finally {
                jobFinished(params, false);
            }
        }).start();
        return true; // work is async
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Let the system reschedule if needed.
        return true;
    }

    public static void checkProviders(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        // Load provider list (or defaults).
        String providersJson = prefs.getString(KEY_PROVIDERS, null);
        JSONArray providers;
        if (providersJson != null) {
            try {
                providers = new JSONArray(providersJson);
            } catch (Exception e) {
                providers = defaultProviders();
            }
        } else {
            providers = defaultProviders();
        }

        JSONArray results = new JSONArray();
        for (int i = 0; i < providers.length(); i++) {
            String url = providers.optString(i, "");
            if (url.isEmpty()) continue;
            JSONObject entry = new JSONObject();
            try {
                entry.put("url", url);
                entry.put("reachable", isReachable(url));
                entry.put("checkedAt", System.currentTimeMillis());
            } catch (Exception e) {
                try { entry.put("reachable", false); } catch (Exception ignored) {}
            }
            results.put(entry);
        }

        prefs.edit()
                .putString(KEY_RESULTS, results.toString())
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .apply();
    }

    private static JSONArray defaultProviders() {
        JSONArray arr = new JSONArray();
        for (String p : DEFAULT_PROVIDERS) arr.put(p);
        return arr;
    }

    private static boolean isReachable(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Schedule a periodic provider health check.
     *
     * @param context Application context.
     * @param intervalMs Interval in milliseconds (minimum 15 minutes on Android).
     */
    public static void schedule(Context context, long intervalMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        android.app.job.JobScheduler scheduler =
                (android.app.job.JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;

        // Cancel any existing job.
        scheduler.cancel(1001);

        android.app.job.JobInfo.Builder builder = new android.app.job.JobInfo.Builder(
                1001, new android.content.ComponentName(context, ProviderHealthJobService.class));
        builder.setPeriodic(Math.max(intervalMs, 15 * 60 * 1000L)); // min 15 min
        builder.setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY);
        builder.setPersisted(true);

        try {
            scheduler.schedule(builder.build());
        } catch (Exception e) {
            Log.w(TAG, "Failed to schedule provider health job", e);
        }
    }

    /**
     * Cancel the periodic check.
     */
    public static void cancel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        android.app.job.JobScheduler scheduler =
                (android.app.job.JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) scheduler.cancel(1001);
    }
}
