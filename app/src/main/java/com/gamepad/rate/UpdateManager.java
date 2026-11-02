package com.gamepad.rate;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.TextView;
import android.view.View;
import android.util.Log;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit check/download/install flow. No silent installation or self-reported version trust. */
final class UpdateManager {
    private static final String REPO = "https://github.com/chenshianovo/Polling-Rate-Test/";
    private static final String API = "https://api.github.com/repos/chenshianovo/Polling-Rate-Test/releases?per_page=100";
    private static final long MAX_APK = 64L * 1024 * 1024;
    private final ProfileActivity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;
    private volatile HttpURLConnection connection;
    private boolean busy, waitingPermission;
    private File pendingApk;

    UpdateManager(ProfileActivity activity) {
        this.activity = activity;
        activity.findViewById(R.id.open_release).setOnClickListener(view -> {
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO + "releases"))); }
            catch (ActivityNotFoundException error) { status("没有可打开发布页的浏览器"); }
        });
    }
    private void ui(Runnable action) { activity.runOnUiThread(() -> { if (!destroyed && !activity.isFinishing()) action.run(); }); }
    private void status(String text) { ((TextView) activity.findViewById(R.id.update_status)).setText(text); }
    private void busy(boolean value, String message) {
        busy = value;
        activity.findViewById(R.id.check_update).setEnabled(!value);
        activity.findViewById(R.id.open_release).setVisibility(View.GONE);
        status(message);
    }
    private void failed(String stage, Exception error) {
        Log.w("GamepadRateUpdate", stage, error);
        busy(false, stage + "：" + UpdateErrors.describe(error));
        activity.findViewById(R.id.open_release).setVisibility(View.VISIBLE);
    }
    void check() {
        if (busy) return;
        busy(true, "正在检查更新…");
        worker.execute(() -> {
            try {
                PackageInfo installed = installed();
                JSONArray releases = new JSONArray(new String(read(API, 2 * 1024 * 1024), StandardCharsets.UTF_8));
                JSONObject best = null, asset = null;
                long bestVersion = version(installed.versionName);
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject release = releases.getJSONObject(i);
                    long number = version(release.optString("tag_name"));
                    if (release.optBoolean("draft") || number <= bestVersion) continue;
                    JSONArray assets = release.optJSONArray("assets");
                    if (assets == null) continue;
                    for (int j = 0; j < assets.length(); j++) {
                        JSONObject candidate = assets.getJSONObject(j);
                        if (candidate.optString("name").startsWith("GamepadRate-")
                                && candidate.optString("name").endsWith(".apk")
                                && candidate.optString("state").equals("uploaded")) {
                            best = release; asset = candidate; bestVersion = number; break;
                        }
                    }
                }
                JSONObject release = best, apk = asset;
                ui(() -> {
                    busy(false, release == null ? "已是最新版本" : "发现新版本");
                    if (release == null) return;
                    String version = release.optString("tag_name") + (release.optBoolean("prerelease") ? "（测试版）" : "");
                    new AlertDialog.Builder(activity).setTitle("更新至 " + version)
                            .setMessage(release.optString("body", "有新版本可安装"))
                            .setPositiveButton("下载更新", (dialog, which) -> download(apk))
                            .setNegativeButton("暂不更新", null).show();
                });
            } catch (Exception error) { ui(() -> failed("检查失败", error)); }
        });
    }
    // Current releases use vMAJOR.MINOR.PATCH; unrecognized tags are not guessed.
    static long version(String value) {
        if (value == null || !value.matches("v?[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}")) return -1;
        String[] parts = value.replaceFirst("^v", "").split("\\.");
        return Long.parseLong(parts[0]) * 100_000_000L + Long.parseLong(parts[1]) * 10_000L + Long.parseLong(parts[2]);
    }
    private void download(JSONObject asset) {
        if (busy) return;
        busy(true, "正在下载…");
        worker.execute(() -> {
            File file = null;
            try {
                String url = asset.getString("browser_download_url");
                String expected = asset.optString("digest");
                long size = asset.getLong("size");
                if (!url.startsWith(REPO + "releases/download/") || !expected.matches("sha256:[0-9a-fA-F]{64}")
                        || size <= 0 || size > MAX_APK) throw new IllegalStateException("发布附件缺少有效校验信息");
                File directory = new File(activity.getCacheDir(), "updates");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建下载目录");
                file = new File(directory, UUID.randomUUID() + ".apk");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                HttpURLConnection http = open(url);
                try (InputStream input = http.getInputStream(); FileOutputStream output = new FileOutputStream(file)) {
                    byte[] buffer = new byte[32 * 1024];
                    long total = 0;
                    int lastPercent = -1, count;
                    while ((count = input.read(buffer)) != -1) {
                        if (destroyed || Thread.currentThread().isInterrupted()) throw new java.io.IOException("下载已取消");
                        total += count;
                        if (total > size) throw new java.io.IOException("附件大小不匹配");
                        digest.update(buffer, 0, count); output.write(buffer, 0, count);
                        int percent = (int) (total * 100 / size);
                        if (percent != lastPercent) { lastPercent = percent; ui(() -> status("正在下载 " + percent + "%")); }
                    }
                    if (total != size || !hex(digest.digest()).equalsIgnoreCase(expected.substring(7)))
                        throw new java.io.IOException("安装包校验失败");
                    output.getFD().sync();
                } finally { http.disconnect(); }
                verifyPackage(file);
                File ready = file;
                if (destroyed) { file.delete(); return; }
                ui(() -> { pendingApk = ready; busy(false, "校验通过，等待安装"); install(); });
            } catch (Exception error) {
                if (file != null) file.delete();
                ui(() -> failed("更新失败", error));
            }
        });
    }
    private PackageInfo installed() throws PackageManager.NameNotFoundException {
        return activity.getPackageManager().getPackageInfo(activity.getPackageName(), signatureFlags());
    }
    private static int signatureFlags() { return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES; }
    private static Set<String> signers(PackageInfo info) {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        Set<String> values = new HashSet<>();
        if (signatures != null) for (Signature signature : signatures) values.add(signature.toCharsString());
        return values;
    }
    private void verifyPackage(File file) throws Exception {
        PackageInfo current = installed();
        PackageInfo next = activity.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), signatureFlags());
        if (next == null || !activity.getPackageName().equals(next.packageName)) throw new IllegalStateException("包名不匹配");
        long currentCode = Build.VERSION.SDK_INT >= 28 ? current.getLongVersionCode() : current.versionCode;
        long nextCode = Build.VERSION.SDK_INT >= 28 ? next.getLongVersionCode() : next.versionCode;
        if (nextCode <= currentCode) throw new IllegalStateException("安装包版本没有升级");
        if (next.applicationInfo != null && next.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
            throw new IllegalStateException("新版本不支持当前安卓系统");
        if (signers(current).isEmpty() || !signers(current).equals(signers(next)))
            throw new IllegalStateException("签名不同，无法覆盖安装；请使用同一签名发布更新");
    }
    private void install() {
        if (pendingApk == null || destroyed) return;
        try {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(activity).setTitle("允许安装更新")
                        .setMessage("需要在系统中允许此应用安装更新包。")
                        .setPositiveButton("去设置", (dialog, which) -> {
                            try {
                                waitingPermission = true;
                                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + activity.getPackageName())));
                            } catch (RuntimeException error) { waitingPermission = false; status("无法打开安装权限设置"); }
                        }).setNegativeButton("取消", (dialog, which) -> status("已取消安装，可重新检查更新")).show();
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", pendingApk);
            activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            status("已打开系统安装界面");
            pendingApk = null;
        } catch (RuntimeException error) { status("无法启动安装：" + error.getMessage()); }
    }
    void onResume() {
        if (!waitingPermission) return;
        waitingPermission = false;
        if (activity.getPackageManager().canRequestPackageInstalls()) install();
        else status("未授予安装权限，可重新检查更新");
    }
    void destroy() {
        destroyed = true;
        HttpURLConnection active = connection;
        if (active != null) active.disconnect();
        worker.shutdownNow();
    }
    private HttpURLConnection open(String address) throws Exception {
        URL url = new URL(address);
        for (int redirects = 0; redirects < 6; redirects++) {
            if (destroyed) throw new java.io.IOException("操作已取消");
            String host = url.getHost();
            if (!url.getProtocol().equals("https") || !Arrays.asList("api.github.com", "github.com",
                    "release-assets.githubusercontent.com", "objects.githubusercontent.com").contains(host))
                throw new java.io.IOException("更新地址不受支持");
            HttpURLConnection http = (HttpURLConnection) url.openConnection();
            connection = http;
            http.setConnectTimeout(15000); http.setReadTimeout(20000); http.setInstanceFollowRedirects(false);
            http.setRequestProperty("User-Agent", "GamepadRate-Android");
            int status;
            try { status = http.getResponseCode(); }
            catch (Exception error) { http.disconnect(); throw error; }
            if (status >= 300 && status < 400) {
                String location = http.getHeaderField("Location"); http.disconnect();
                if (location == null) throw new java.io.IOException("更新地址重定向失败");
                url = new URL(url, location); continue;
            }
            if (status != 200) { http.disconnect(); throw new UpdateErrors.HttpFailure(status); }
            return http;
        }
        throw new java.io.IOException("重定向次数过多");
    }
    private byte[] read(String url, int limit) throws Exception {
        HttpURLConnection http = open(url);
        try (InputStream input = http.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (destroyed || output.size() + count > limit) throw new java.io.IOException("更新信息超出限制");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally { http.disconnect(); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return value.toString();
    }
}
