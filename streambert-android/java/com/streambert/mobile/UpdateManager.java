package com.streambert.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Built-in updater for the sideloaded APK.
 *
 * <p>Streambert Mobile is distributed as an APK, not through Play Store, so
 * Android does not update it. This class closes that gap: it reads a release
 * description from {@link #RELEASE_URL}, compares it with the installed
 * package, downloads a newer APK into the app cache over HTTPS, verifies the
 * SHA-256 digest the release publishes, then verifies package name, version
 * and signing certificate, and only then hands the file to the system
 * installer through {@link UpdateFileProvider}.</p>
 *
 * <p>The digest check is what makes the update path trustworthy: the signing
 * certificate is compared against the installed app so Android accepts the
 * upgrade, but the release keystore ships with this project, so the certificate
 * alone cannot prove the file is the one that was published. The published SHA-256
 * can, because a file cannot be altered without changing its hash.</p>
 *
 * <p>Automatic checks run once per {@link #CHECK_INTERVAL_MS} when the app is
 * opened; a manual check from Settings uses the same path.</p>
 */
public final class UpdateManager {

    /** Automatic-check preference, last check time and the downloaded APK. */
    static final String PREFS = "streambert_updates";
    private static final String KEY_AUTO = "autoCheck";
    private static final String KEY_LAST_CHECK = "lastCheck";
    private static final String KEY_PENDING = "pendingApk";

    /**
     * Release source. Either a GitHub "latest release" object (the default,
     * looking for the first .apk asset) or the small JSON manifest documented
     * in README.md. Point this at wherever the APKs are published.
     */
    static final String RELEASE_URL =
            "https://api.github.com/repos/RAHTAHTAHTAH/Streambert-Android-Port/releases/latest";

    /** A background check runs at most this often, also across restarts. */
    private static final long CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L;

    private static final String APK_NAME = "Streambert-Mobile.apk";

    /** Parsed release description; immutable once built. */
    static final class Release {
        String versionName = "";
        int versionCode = 0;
        long sizeBytes = 0L;
        /** Published SHA-256 of the APK asset; empty when the source gives none. */
        String sha256 = "";
        String apkUrl = "";
        String notes = "";
        String pageUrl = "";
    }

    private interface Progress {
        void onProgress(int percent, long done, long total);
    }

    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean checking = false;
    private boolean downloading = false;
    private boolean installLaunched = false;
    private boolean awaitingPermission = false;
    private volatile boolean cancelled = false;

    UpdateManager(Activity activity) {
        this.activity = activity;
    }

    // ------------------------------------------------------------------
    // Preferences
    // ------------------------------------------------------------------

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean isAutoCheckEnabled() {
        return prefs().getBoolean(KEY_AUTO, true);
    }

    void setAutoCheckEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_AUTO, enabled).apply();
    }

    /** JSON state for the Settings card. */
    String stateJson() {
        JSONObject out = new JSONObject();
        try {
            PackageInfo info = installedPackage();
            out.put("versionName", info == null ? "" : info.versionName);
            out.put("versionCode", info == null ? 0 : info.versionCode);
            out.put("autoCheck", isAutoCheckEnabled());
            out.put("lastCheck", prefs().getLong(KEY_LAST_CHECK, 0L));
            out.put("canInstall", canInstallPackages());
            File pending = pendingFile();
            out.put("pendingUpdate", pending != null && pending.isFile());
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Checking
    // ------------------------------------------------------------------

    /** Runs a throttled background check when the app is opened. */
    void maybeAutoCheck() {
        if (!isAutoCheckEnabled()) return;
        long last = prefs().getLong(KEY_LAST_CHECK, 0L);
        if (last > 0L && System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;
        ui.postDelayed(() -> check(false), 2500L);
    }

    void check(final boolean manual) {
        if (checking) return;
        checking = true;
        if (manual) toast("Checking for updates\u2026");

        new Thread(() -> {
            Release release = null;
            String failure = null;
            try {
                release = fetchRelease();
            } catch (Exception e) {
                failure = checkErrorText(e);
            }
            final Release found = release;
            final String error = failure;
            ui.post(() -> {
                checking = false;
                prefs().edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
                if (error != null) {
                    if (manual) showError(error);
                    return;
                }
                if (found == null) {
                    if (manual) toast("Streambert is up to date.");
                    return;
                }
                showUpdateDialog(found);
            });
        }, "streambert-update-check").start();
    }

    private Release fetchRelease() throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(RELEASE_URL).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/vnd.github+json, application/json");
            conn.setRequestProperty("User-Agent", "Streambert-Mobile/" + installedVersionName());
            int code = conn.getResponseCode();
            if (code == 404) return null; // no release published yet
            if (code < 200 || code >= 300) {
                throw new IOException("server returned HTTP " + code);
            }
            return parseRelease(readBody(conn.getInputStream(), 128 * 1024));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Returns the release when it is newer than the installed build. */
    private Release parseRelease(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        Release release = new Release();

        JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            // GitHub release object.
            release.versionName = cleanVersion(root.optString("tag_name",
                    root.optString("name", "")));
            release.notes = root.optString("body", "");
            release.pageUrl = root.optString("html_url", "");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String name = asset.optString("name", "");
                if (!name.toLowerCase(Locale.US).endsWith(".apk")) continue;
                release.apkUrl = asset.optString("browser_download_url", "");
                release.sizeBytes = asset.optLong("size", 0L);
                // GitHub records "sha256:<hex>" for every uploaded asset.
                release.sha256 = asset.optString("digest", "");
                break;
            }
        } else {
            // Plain manifest: {versionName, versionCode, apkUrl, notes, pageUrl}.
            release.versionName = cleanVersion(root.optString("versionName",
                    root.optString("version", "")));
            release.versionCode = root.optInt("versionCode", 0);
            release.apkUrl = root.optString("apkUrl", root.optString("url", ""));
            release.sha256 = root.optString("sha256", root.optString("digest", ""));
            release.notes = root.optString("notes", root.optString("changelog", ""));
            release.pageUrl = root.optString("pageUrl", root.optString("htmlUrl", ""));
        }

        if (release.apkUrl.isEmpty()) return null;
        if (!isHttpsUrl(release.apkUrl)) return null;

        PackageInfo local = installedPackage();
        if (local == null) return null;

        boolean newer = release.versionCode > 0
                ? release.versionCode > local.versionCode
                : compareVersions(release.versionName, local.versionName) > 0;
        return newer ? release : null;
    }

    private static boolean isHttpsUrl(String value) {
        String lower = value.toLowerCase(Locale.US);
        // HTTPS only: an update must never arrive over a channel that can be
        // silently rewritten in transit.
        return lower.startsWith("https://");
    }

    private static String readBody(InputStream in, int limit) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > limit) break;
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static String checkErrorText(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) message = "network error";
        return "Could not check for updates (" + message + "). Try again later.";
    }

    // ------------------------------------------------------------------
    // Version helpers
    // ------------------------------------------------------------------

    /** "v2.9.2-mobile" -> "2.9.2-mobile". */
    static String cleanVersion(String value) {
        String version = value == null ? "" : value.trim();
        if (version.startsWith("v") || version.startsWith("V")) version = version.substring(1);
        return version.trim();
    }

    static int compareVersions(String left, String right) {
        int[] a = versionNumbers(left);
        int[] b = versionNumbers(right);
        int size = Math.max(a.length, b.length);
        for (int i = 0; i < size; i++) {
            int l = i < a.length ? a[i] : 0;
            int r = i < b.length ? b[i] : 0;
            if (l != r) return l < r ? -1 : 1;
        }
        return 0;
    }

    /** Numeric segments of a version string ("2.9.1-mobile" -> [2, 9, 1]). */
    private static int[] versionNumbers(String value) {
        if (value == null) return new int[0];
        StringBuilder digits = new StringBuilder();
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (digits.length() > 0) {
                count++;
                digits.append('.');
            }
        }
        if (digits.length() == 0) return new int[0];

        String[] parts = digits.toString().split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                numbers[i] = 0;
            }
        }
        return numbers;
    }

    private String installedVersionName() {
        PackageInfo info = installedPackage();
        return info == null || info.versionName == null ? "unknown" : info.versionName;
    }

    private PackageInfo installedPackage() {
        try {
            return activity.getPackageManager().getPackageInfo(
                    activity.getPackageName(), PackageManager.GET_SIGNATURES);
        } catch (Exception e) {
            return null;
        }
    }

    private static String signatureHash(PackageInfo info) {
        Signature[] signatures = info == null ? null : info.signatures;
        if (signatures == null || signatures.length == 0) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Signature signature : signatures) digest.update(signature.toByteArray());
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format(Locale.US, "%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    void downloadAndInstall(final Release release) {
        if (downloading || release == null || release.apkUrl.isEmpty()) return;
        downloading = true;
        installLaunched = false;
        cancelled = false;
        clearPending(); // a stale APK must never be installed by accident

        final File dir = new File(activity.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            downloading = false;
            showError("Could not create a folder for the update.");
            return;
        }
        final File target = new File(dir, APK_NAME);

        final ProgressBar bar = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        final TextView label = new TextView(activity);
        label.setPadding(dp(20), 0, dp(20), 0);
        label.setText("Starting download\u2026");

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, 0);
        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Downloading update")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setCancelable(false)
                .create();
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
            cancelled = true;
            dialog.dismiss();
            toast("Update cancelled.");
        });

        new Thread(() -> {
            String failure = null;
            try {
                download(release, target, (percent, done, total) -> ui.post(() -> {
                    if (percent >= 0) {
                        bar.setProgress(percent);
                        label.setText(total > 0
                                ? humanSize(done) + " of " + humanSize(total)
                                : humanSize(done) + " downloaded");
                    } else {
                        label.setText(humanSize(done) + " downloaded");
                    }
                }));
                failure = verifyIntegrity(release, target);
                if (failure == null) failure = verifyApk(target);
                if (failure == null) storePending(target);
            } catch (Exception e) {
                failure = cancelled ? "Update cancelled." : ("The update could not be downloaded ("
                        + (e.getMessage() == null ? "network error" : e.getMessage()) + ").");
            }
            final String error = failure;
            ui.post(() -> {
                downloading = false;
                dialog.dismiss();
                if (error != null) {
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                    clearPending();
                    if (!"Update cancelled.".equals(error)) showError(error);
                    return;
                }
                confirmInstall(release);
            });
        }, "streambert-update-download").start();
    }

    private void download(Release release, File target, Progress progress) throws Exception {
        HttpURLConnection conn = null;
        File tmp = new File(target.getParentFile(), APK_NAME + ".part");
        try {
            conn = (HttpURLConnection) new URL(release.apkUrl).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Streambert-Mobile/" + installedVersionName());
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("server returned HTTP " + code);
            }
            long total = release.sizeBytes > 0L
                    ? release.sizeBytes
                    : Math.max(0, conn.getContentLength());

            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buffer = new byte[16384];
                long done = 0L;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancelled) throw new IOException("cancelled");
                    out.write(buffer, 0, read);
                    done += read;
                    int percent = total > 0L ? (int) Math.min(100L, done * 100L / total) : -1;
                    progress.onProgress(percent, done, total);
                }
                out.flush();
            } finally {
                try { in.close(); } catch (IOException ignored) {}
                try { out.close(); } catch (IOException ignored) {}
            }

            if (!tmp.renameTo(target)) {
                throw new IOException("could not save the file");
            }
        } finally {
            if (conn != null) conn.disconnect();
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private static String humanSize(long bytes) {
        if (bytes <= 0L) return "0 KB";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%d KB", bytes / 1024L);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * Cryptographic integrity check: the bytes written to disk must hash to the
     * SHA-256 the release publishes. Runs before the package checks so a
     * corrupted download, a truncated transfer or a swapped file is thrown away
     * without ever reaching the installer.
     */
    private String verifyIntegrity(Release release, File apk) {
        String expected = Digests.normalize(release.sha256);
        if (expected.isEmpty()) {
            return "This release does not publish a SHA-256 digest, so the update could "
                    + "not be verified and was discarded.";
        }
        if (release.sizeBytes > 0L && apk.length() != release.sizeBytes) {
            return "The update is incomplete (" + apk.length() + " of "
                    + release.sizeBytes + " bytes), so it was discarded.";
        }
        try {
            if (!Digests.matches(expected, apk)) {
                return "The update failed its integrity check (SHA-256 mismatch), so it "
                        + "was discarded.";
            }
        } catch (Exception e) {
            return "The update's integrity could not be checked, so it was discarded.";
        }
        return null;
    }

    /**
     * Rejects anything that Android would refuse to install over this build:
     * wrong package, not newer, or signed with another key.
     */
    private String verifyApk(File apk) {
        PackageManager pm = activity.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(
                apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        if (archive == null) return "The downloaded file is not a valid APK.";
        if (!activity.getPackageName().equals(archive.packageName)) {
            return "The downloaded APK is for a different app.";
        }
        PackageInfo installed = installedPackage();
        if (installed == null) return "Could not read the installed app version.";
        if (archive.versionCode <= installed.versionCode) {
            return "The downloaded APK is not newer than the installed version.";
        }
        String localKey = signatureHash(installed);
        String remoteKey = signatureHash(archive);
        // Fail closed: an APK whose signer cannot be read is not installable.
        if (localKey == null || remoteKey == null) {
            return "The update's signing key could not be read, so it was discarded.";
        }
        if (!localKey.equals(remoteKey)) {
            return "The downloaded APK is signed with a different key, so Android "
                    + "will not install it over this build.";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Install
    // ------------------------------------------------------------------

    private void confirmInstall(Release release) {
        String label = release.versionName.isEmpty() ? "The new version" : release.versionName;
        new AlertDialog.Builder(activity)
                .setTitle("Update ready")
                .setMessage("Streambert " + label + " was downloaded. Android will ask you "
                        + "to confirm the installation.")
                .setPositiveButton("Install", (dialog, which) -> installPending())
                .setNegativeButton("Later", null)
                .show();
    }

    private boolean canInstallPackages() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        return activity.getPackageManager().canRequestPackageInstalls();
    }

    /** Installs the downloaded APK, requesting the install permission first. */
    void installPending() {
        File apk = pendingFile();
        if (apk == null || !apk.isFile()) {
            clearPending();
            toast("The update file is missing. Check for updates again.");
            return;
        }

        if (!canInstallPackages()) {
            awaitingPermission = true;
            new AlertDialog.Builder(activity)
                    .setTitle("Allow installs")
                    .setMessage("Android needs your permission before Streambert can install "
                            + "updates. Turn on \"Allow from this source\", then come back to "
                            + "Streambert to finish the installation.")
                    .setPositiveButton("Open settings", (dialog, which) -> {
                        try {
                            Intent intent = new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivity(intent);
                        } catch (Exception e) {
                            toast("Open Settings > Apps > Streambert and allow installs.");
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        Uri uri = Uri.parse("content://" + UpdateFileProvider.AUTHORITY + "/" + apk.getName());
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            awaitingPermission = false;
            installLaunched = true;
            activity.startActivity(intent);
        } catch (Exception e) {
            installLaunched = false;
            toast("No app installer was found on this device.");
        }
    }

    /**
     * Called when the app comes back to the foreground. Drops a leftover APK
     * that has already been installed, and finishes an install that was
     * waiting for the "install unknown apps" permission.
     */
    void resumePendingInstall() {
        if (installLaunched) return;
        File apk = pendingFile();
        if (apk == null || !apk.isFile()) {
            if (apk != null) clearPending();
            return;
        }

        PackageInfo installed = installedPackage();
        PackageInfo archive = installed == null ? null : activity.getPackageManager()
                .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (installed != null && archive != null
                && archive.versionCode <= installed.versionCode) {
            // The install went through; the cached APK is no longer needed.
            clearPending();
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            return;
        }

        // Only continue by itself when the user sent us to the permission
        // screen; a cancelled install must not pop the installer every start.
        if (!awaitingPermission || !canInstallPackages()) return;
        installPending();
    }

    // ------------------------------------------------------------------
    // Pending APK bookkeeping
    // ------------------------------------------------------------------

    private File pendingFile() {
        String path = prefs().getString(KEY_PENDING, null);
        return path == null ? null : new File(path);
    }

    private void storePending(File apk) {
        prefs().edit().putString(KEY_PENDING, apk.getAbsolutePath()).apply();
    }

    private void clearPending() {
        prefs().edit().remove(KEY_PENDING).apply();
    }

    // ------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------

    private void showUpdateDialog(final Release release) {
        String notes = release.notes == null ? "" : release.notes.trim();
        if (notes.length() > 900) notes = notes.substring(0, 900) + "\u2026";

        String label = release.versionName.isEmpty()
                ? "A new version"
                : "Streambert " + release.versionName;
        StringBuilder message = new StringBuilder();
        message.append(label).append(" is available.\n\nYou have ")
                .append(installedVersionName()).append('.');
        if (!notes.isEmpty()) {
            message.append("\n\nWhat's new:\n").append(notes);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage(message.toString())
                .setPositiveButton("Update now", (dialog, which) -> downloadAndInstall(release))
                .setNegativeButton("Later", null);
        if (!release.pageUrl.isEmpty()) {
            builder.setNeutralButton("Release notes",
                    (dialog, which) -> openExternal(release.pageUrl));
        }
        builder.show();
    }

    private void showError(String message) {
        new AlertDialog.Builder(activity)
                .setTitle("Update")
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void openExternal(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
