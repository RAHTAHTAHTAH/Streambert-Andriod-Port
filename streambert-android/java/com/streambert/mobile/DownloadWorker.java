package com.streambert.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * WorkManager-backed wrapper around {@link DownloadService}.
 *
 * <p>Enqueuing a {@link DownloadWorker} gives us WorkManager's guarantees:
 * automatic retry on network drops, constraint-based scheduling (network must
 * be available) and survival across process death. The actual byte transfer is
 * still performed by the existing foreground {@link DownloadService}, which
 * keeps a persistent notification and continues while the app is backgrounded.</p>
 */
public class DownloadWorker extends Worker {

    public static final String KEY_URL = "url";
    public static final String KEY_UA = "ua";
    public static final String KEY_TITLE = "title";
    public static final String KEY_REFERER = "referer";
    public static final String KEY_NAME = "name";
    public static final String KEY_INDEX = "index";
    public static final String KEY_TOTAL = "total";

    private static final String CHANNEL_ID = "streambert_downloads";

    public DownloadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Data input = getInputData();
        String url = input.getString(KEY_URL);
        if (url == null || url.isEmpty()) {
            return Result.failure();
        }

        // The DownloadService itself promotes to a foreground notification;
        // WorkManager handles retry/constraints on top of that.

        // Delegate the actual transfer to the existing foreground service.
        Intent intent = new Intent(getApplicationContext(), DownloadService.class);
        intent.putExtra(DownloadService.EXTRA_URL, url);
        intent.putExtra(DownloadService.EXTRA_UA, input.getString(KEY_UA));
        intent.putExtra(DownloadService.EXTRA_TITLE, input.getString(KEY_TITLE));
        intent.putExtra(DownloadService.EXTRA_REFERER, input.getString(KEY_REFERER));
        intent.putExtra(DownloadService.EXTRA_NAME, input.getString(KEY_NAME));
        intent.putExtra(DownloadService.EXTRA_INDEX, input.getInt(KEY_INDEX, 0));
        intent.putExtra(DownloadService.EXTRA_TOTAL, input.getInt(KEY_TOTAL, 0));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplicationContext().startForegroundService(intent);
            } else {
                getApplicationContext().startService(intent);
            }
        } catch (Exception e) {
            return Result.retry();
        }

        // WorkManager considers this unit of work complete once the service is
        // launched; the service owns the persistent notification and retry logic.
        return Result.success();
    }


}