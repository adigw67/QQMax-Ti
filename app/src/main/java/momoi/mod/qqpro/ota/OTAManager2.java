package momoi.mod.qqpro.ota;

import android.Manifest;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * OTAManager2 - Over-The-Air Update Manager for Android Applications
 *
 * Features:
 * - GitLab Release API integration
 * - Semantic version comparison (e.g., 4.1.2 vs 4.0.9)
 * - Dual download modes: in-app download or external browser
 * - Customizable for different projects
 *
 * Requirements:
 * 1. Add permissions to AndroidManifest.xml:
 *    <uses-permission android:name="android.permission.INTERNET" />
 *    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
 *    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
 *
 * 2. Add FileProvider to AndroidManifest.xml (for in-app download mode):
 *    <provider
 *        android:name="androidx.core.content.FileProvider"
 *        android:authorities="${applicationId}.provider"
 *        android:exported="false"
 *        android:grantUriPermissions="true">
 *        <meta-data
 *            android:name="android.support.FILE_PROVIDER_PATHS"
 *            android:resource="@xml/provider_paths" />
 *    </provider>
 *
 * 3. Create res/xml/provider_paths.xml:
 *    <?xml version="1.0" encoding="utf-8"?>
 *    <paths xmlns:android="http://schemas.android.com/apk/res/android">
 *        <external-files-path name="external_files" path="."/>
 *    </paths>
 *
 * Usage:
 *    OTAManager2 otaManager = new OTAManager2(context);
 *    otaManager.checkUpdate(false); // Check only on WiFi
 *    otaManager.checkUpdate(true);  // Force check on any connection
 */
public class OTAManager2 {

    // ============================================================================
    // CONFIGURATION - Customize these values for your project
    // ============================================================================

    /**
     * GitLab project owner/username
     */
    private static final String GITLAB_OWNER = "ailife8881";

    /**
     * GitLab project repository name
     */
    private static final String GITLAB_REPO = "qqmax";

    /**
     * GitLab API base URL (usually no need to change)
     */
    private static final String GITLAB_API_BASE = "https://gitlab.com/api/v4";

    /**
     * GitLab Personal Access Token (optional, for private repos)
     * Leave as null for public repositories
     */
    private static final String GITLAB_TOKEN = null;

    /**
     * Download mode:
     * - DOWNLOAD_MODE_IN_APP: Download APK in-app and install (requires REQUEST_INSTALL_PACKAGES)
     * - DOWNLOAD_MODE_BROWSER: Open download link in external browser (no permission needed)
     */
    private static final DownloadMode DOWNLOAD_MODE = DownloadMode.DOWNLOAD_MODE_IN_APP;

    /**
     * APK file name pattern in GitLab release assets
     * Use regex pattern to match the APK file
     */
    private static final String APK_FILE_PATTERN = ".*\\.apk$";

    /**
     * Downloaded APK file name (for in-app download mode)
     */
    private static final String DOWNLOAD_FILE_NAME = "OTA.apk";

    /**
     * FileProvider authority suffix (appended to package name)
     */
    private static final String PROVIDER_PATH = ".fileprovider";

    /**
     * Enable debug logging
     */
    private static final boolean DEBUG = true;

    /**
     * Check update only on WiFi (can be overridden by force parameter)
     */
    private static final boolean WIFI_ONLY = false;

    /**
     * SharedPreferences key for storing update check enabled state
     */
    private static final String PREFS_NAME = "OTAManager2Prefs";
    private static final String PREF_UPDATE_ENABLED = "update_check_enabled";

    // ============================================================================
    // END OF CONFIGURATION
    // ============================================================================

    private static final String TAG = "OTAManager2";
    private static final String MIME_TYPE = "application/vnd.android.package-archive";

    private Context context;
    private Handler mainHandler;

    // When true (auto check on launch), suppress informational/error toasts; only the
    // "update available" dialog is shown. Set false for a user-initiated (forced) check.
    private boolean silent = false;

    // Release information
    private String latestVersion;
    private String changelog;
    private String downloadUrl;

    /**
     * Download mode enumeration
     */
    public enum DownloadMode {
        DOWNLOAD_MODE_IN_APP,    // Download in app and install
        DOWNLOAD_MODE_BROWSER    // Open in external browser
    }

    /**
     * Constructor
     * @param context Application or Activity context
     */
    public OTAManager2(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Check if update checking is enabled
     * @return true if enabled, false if user disabled it
     */
    public boolean isUpdateCheckEnabled() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_UPDATE_ENABLED, true);
    }

    /**
     * Enable or disable update checking
     * @param enabled true to enable, false to disable
     */
    public void setUpdateCheckEnabled(boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_UPDATE_ENABLED, enabled)
                .apply();
        logDebug("Update check " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Check for updates
     * @param force Force check even on mobile data (ignores WIFI_ONLY and enabled settings)
     */
    public void checkUpdate(boolean force) {
        // A forced (manual) check shows all feedback; an auto check on launch stays silent.
        this.silent = !force;
        // Check if update checking is enabled
        if (!force && !isUpdateCheckEnabled()) {
            logDebug("Update check disabled by user");
            return;
        }

        // Check network connectivity
        if (!force && WIFI_ONLY) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || ni.getType() != ConnectivityManager.TYPE_WIFI) {
                logDebug("Not on WiFi, skipping update check");
                return;
            }
        }

        // Build GitLab API URL
        String projectPath = GITLAB_OWNER + "/" + GITLAB_REPO;
        String encodedPath = projectPath.replace("/", "%2F");
        String apiUrl = GITLAB_API_BASE + "/projects/" + encodedPath + "/releases/permalink/latest";

        logDebug("Checking for updates at: " + apiUrl);

        // Make API request in background thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(apiUrl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);

                    // Add authorization header if token is provided
                    if (GITLAB_TOKEN != null && !GITLAB_TOKEN.isEmpty()) {
                        connection.setRequestProperty("PRIVATE-TOKEN", GITLAB_TOKEN);
                    }

                    int responseCode = connection.getResponseCode();
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        logError("API request failed with code: " + responseCode);
                        info("更新服务器无响应");
                        return;
                    }

                    // Read response
                    InputStream inputStream = connection.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    parseReleaseInfo(response.toString());

                } catch (Exception e) {
                    logError("API request failed: " + e.getMessage());
                    info("检查更新失败: " + e.getMessage());
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }).start();
    }

    /**
     * Parse GitLab release API response
     */
    private void parseReleaseInfo(String jsonResponse) {
        try {
            JSONObject release = new JSONObject(jsonResponse);

            // Get version name from tag_name
            latestVersion = release.getString("tag_name");

            // Remove leading 'v' if present (e.g., v4.1.0 -> 4.1.0)
            if (latestVersion.startsWith("v") || latestVersion.startsWith("V")) {
                latestVersion = latestVersion.substring(1);
            }

            // Get changelog from description
            changelog = release.optString("description", "No changelog available");
            if (changelog.isEmpty()) {
                changelog = "No changelog available";
            } else {
                // Clean up markdown formatting from changelog
                changelog = cleanMarkdown(changelog);
            }

            // Get APK download URL from assets
            JSONObject assets = release.optJSONObject("assets");
            if (assets != null) {
                JSONArray links = assets.optJSONArray("links");
                if (links != null) {
                    // Find APK file in links
                    for (int i = 0; i < links.length(); i++) {
                        JSONObject link = links.getJSONObject(i);
                        String name = link.optString("name", "");
                        String url = link.optString("url", "");
                        // Check both name and URL for APK pattern
                        if (name.matches(APK_FILE_PATTERN) || url.matches(APK_FILE_PATTERN)) {
                            downloadUrl = url;
                            break;
                        }
                    }
                }
            }

            // If no APK found in links, try sources
            if (downloadUrl == null) {
                JSONArray sources = assets != null ? assets.optJSONArray("sources") : null;
                if (sources != null) {
                    for (int i = 0; i < sources.length(); i++) {
                        JSONObject source = sources.getJSONObject(i);
                        String format = source.optString("format", "");
                        if (format.equals("apk")) {
                            downloadUrl = source.getString("url");
                            break;
                        }
                    }
                }
            }

            if (downloadUrl == null) {
                logError("No APK file found in release assets");
                info("获取下载链接失败");
                return;
            }

            logDebug("Latest version: " + latestVersion);
            logDebug("Download URL: " + downloadUrl);

            // Compare versions
            compareAndPromptUpdate();

        } catch (JSONException e) {
            logError("JSON parsing error: " + e.getMessage());
            info("获取更新信息失败");
        }
    }

    /**
     * Compare current version with latest version and prompt update if needed
     */
    private void compareAndPromptUpdate() {
        try {
            // Get current version name
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            String currentVersion = pInfo.versionName;

            logDebug("Current version: " + currentVersion);

            // Compare versions
            int comparison = compareVersions(currentVersion, latestVersion);

            if (comparison < 0) {
                // Current version is older, show update dialog
                logDebug("Update available: " + currentVersion + " -> " + latestVersion);
                showUpdateDialog();
            } else if (comparison > 0) {
                // Current version is newer (dev build?)
                logDebug("Running newer version than latest release");
                info("当前为预览版");
            } else {
                // Same version
                logDebug("Already running the latest version");
                info("已是最新版本");
            }

        } catch (PackageManager.NameNotFoundException e) {
            logError("Failed to get package info: " + e.getMessage());
            info("获取更新信息失败");
        }
    }

    /**
     * Clean markdown formatting from text
     * Removes markdown links, bold, italic, and other formatting
     *
     * @param text Markdown formatted text
     * @return Plain text
     */
    private String cleanMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // Remove markdown links: [text](url) -> text
        text = text.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");

        // Remove bold: **text** or __text__ -> text
        text = text.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
        text = text.replaceAll("__([^_]+)__", "$1");

        // Remove italic: *text* or _text_ -> text
        text = text.replaceAll("\\*([^*]+)\\*", "$1");
        text = text.replaceAll("_([^_]+)_", "$1");

        // Remove inline code: `code` -> code
        text = text.replaceAll("`([^`]+)`", "$1");

        // Remove headers: ## Header -> Header
        text = text.replaceAll("^#{1,6}\\s+", "");
        text = text.replaceAll("\\n#{1,6}\\s+", "\n");

        // Clean up excessive newlines
        text = text.replaceAll("\\n{3,}", "\n\n");

        return text.trim();
    }

    /**
     * Compare two version strings (semantic versioning)
     *
     * @param version1 First version (e.g., "4.0.9")
     * @param version2 Second version (e.g., "4.1.2")
     * @return -1 if version1 < version2, 0 if equal, 1 if version1 > version2
     */
    private int compareVersions(String version1, String version2) {
        // Remove non-numeric prefixes if any
        version1 = version1.replaceAll("^[^0-9]+", "");
        version2 = version2.replaceAll("^[^0-9]+", "");

        // Split by dots
        String[] parts1 = version1.split("\\.");
        String[] parts2 = version2.split("\\.");

        // Compare each part from most significant to least significant
        int maxLength = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < maxLength; i++) {
            int v1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int v2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;

            if (v1 < v2) {
                return -1;
            } else if (v1 > v2) {
                return 1;
            }
        }

        return 0; // Versions are equal
    }

    /**
     * Parse a version part to integer, handling non-numeric suffixes
     * Examples: "1" -> 1, "2beta" -> 2, "3-rc1" -> 3
     */
    private int parseVersionPart(String part) {
        try {
            // Extract leading digits
            StringBuilder digits = new StringBuilder();
            for (char c : part.toCharArray()) {
                if (Character.isDigit(c)) {
                    digits.append(c);
                } else {
                    break;
                }
            }
            return digits.length() > 0 ? Integer.parseInt(digits.toString()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Show update dialog with changelog. Uses our own watch-styled dialog ([UpdateDialog]) instead
     * of the framework {@link android.app.AlertDialog}, which renders with oversized margins /
     * clipped buttons on the round watch (forced tiny compile SDK + overridden display DPI).
     */
    private void showUpdateDialog() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    UpdateDialog.show(
                        context,
                        latestVersion,
                        changelog,
                        new Runnable() {
                            @Override
                            public void run() {
                                initiateDownload();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                setUpdateCheckEnabled(false);
                                showToast("已关闭更新提醒，可在设置中重新开启");
                            }
                        }
                    );
                } catch (Throwable t) {
                    logError("Failed to show update dialog: " + t);
                }
            }
        });
    }

    /**
     * Initiate download based on configured mode
     */
    private void initiateDownload() {
        if (DOWNLOAD_MODE == DownloadMode.DOWNLOAD_MODE_IN_APP) {
            downloadInApp();
        } else {
            openInBrowser();
        }
    }

    /**
     * Download APK in-app using DownloadManager
     */
    private void downloadInApp() {
        try {
            String destination = context.getExternalFilesDir(null).toString() + "/" + DOWNLOAD_FILE_NAME;
            File file = new File(destination);
            if (file.exists()) {
                file.delete();
            }

            DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            Uri downloadUri = Uri.parse(downloadUrl);
            DownloadManager.Request request = new DownloadManager.Request(downloadUri);
            request.setMimeType(MIME_TYPE);
            request.setTitle("Downloading Update");
            request.setDescription("Downloading version " + latestVersion);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(context, null, DOWNLOAD_FILE_NAME);

            // Register broadcast receiver for download completion
            setupDownloadCompleteReceiver(destination);

            // Enqueue download
            downloadManager.enqueue(request);
            showToast("Downloading update...");

            logDebug("Download started: " + downloadUrl);
        } catch (Throwable t) {
            // DownloadManager can reject the destination path (e.g. SecurityException
            // "Unsupported path" when running in a cloned/secondary user profile),
            // or fail for other reasons. Don't crash — report failure and offer browser fallback.
            logError("In-app download failed: " + t);
            showToast("下载失败，请重试或在浏览器中下载");
            try {
                openInBrowser();
            } catch (Throwable t2) {
                logError("Browser fallback also failed: " + t2);
            }
        }
    }

    /**
     * Setup broadcast receiver to install APK when download completes
     */
    private void setupDownloadCompleteReceiver(final String destination) {
        BroadcastReceiver onComplete = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                logDebug("Download complete, installing APK");

                try {
                    File apkFile = new File(destination);

                    if (!apkFile.exists()) {
                        showToast("Downloaded file is missing");
                        return;
                    }

                    Uri apkUri;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        // Use FileProvider for Android N+
                        apkUri = FileProvider.getUriForFile(
                            context,
                            context.getPackageName() + PROVIDER_PATH,
                            apkFile
                        );
                    } else {
                        apkUri = Uri.fromFile(apkFile);
                    }

                    Intent install = new Intent(Intent.ACTION_VIEW);
                    install.setDataAndType(apkUri, MIME_TYPE);
                    install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    context.startActivity(install);
                    context.unregisterReceiver(this);

                } catch (Exception e) {
                    logError("Failed to install APK: " + e.getMessage());
                    showToast("Installation failed: " + e.getMessage());
                }
            }
        };

        // Register receiver
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(onComplete, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(onComplete, filter);
        }
    }

    /**
     * Open download URL in external browser
     */
    private void openInBrowser() {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(browserIntent);
            showToast("Downloading in browser...");
            logDebug("Opened in browser: " + downloadUrl);
        } catch (Exception e) {
            logError("Failed to open browser: " + e.getMessage());
            showToast("Failed to download");
        }
    }

    /**
     * Show toast message on main thread (via the project's logging-aware toast helper).
     */
    private void showToast(final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                momoi.mod.qqpro.util.Utils.INSTANCE.toast(context, message, true);
            }
        });
    }

    /**
     * Informational/error toast that is suppressed during a silent (auto, on-launch) check.
     */
    private void info(final String message) {
        if (silent) {
            logDebug("(silent) suppressed toast: " + message);
            return;
        }
        showToast(message);
    }

    /**
     * Log debug message
     */
    private void logDebug(String message) {
        if (DEBUG) {
            Log.d(TAG, message);
        }
    }

    /**
     * Log error message
     */
    private void logError(String message) {
        Log.e(TAG, message);
    }
}