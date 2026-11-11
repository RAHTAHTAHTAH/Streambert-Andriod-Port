package com.streambert.mobile;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

/**
 * One place to decide whether this is a low-end device.
 *
 * The renderer asks through window.Android.isLowEndDevice() and switches to its
 * cheap rendering profile: no backdrop blur on the bars, no transitions, fewer
 * shadows. Detection is deliberately conservative -- a device is only treated
 * as low-end when Android itself says so (isLowRamDevice) or when it has under
 * 3 GB of RAM -- so normal phones keep the full look.
 */
final class DeviceProfile {

    private static final long LOW_RAM_BYTES = 3L * 1024L * 1024L * 1024L;

    private static Boolean cached;

    private DeviceProfile() {}

    static synchronized boolean isLowEnd(Context context) {
        if (cached != null) return cached;
        cached = detect(context);
        return cached;
    }

    private static boolean detect(Context context) {
        if (context == null) return false;
        try {
            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && am.isLowRamDevice()) {
                return true;
            }
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            return info.totalMem > 0 && info.totalMem < LOW_RAM_BYTES;
        } catch (Exception ignored) {
            // Never let a probing failure change how the app looks.
            return false;
        }
    }
}
