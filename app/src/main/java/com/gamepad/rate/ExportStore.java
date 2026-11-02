package com.gamepad.rate;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.widget.Toast;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** All modes share a user-granted document tree; never request broad storage access. */
final class ExportStore {
    interface WriterAction { void write(BufferedWriter writer) throws IOException; }
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    static String directory(Context context) {
        return context.getSharedPreferences("export", Context.MODE_PRIVATE).getString("tree", "");
    }
    static Intent picker(Context context) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (!directory(context).isEmpty()) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(directory(context)));
        return intent;
    }
    static void remember(Context context, Intent result) {
        Uri uri = result.getData();
        if (uri == null) throw new IllegalArgumentException("未选择目录");
        if ((result.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new SecurityException("目录未授予写入权限");
        context.getContentResolver().takePersistableUriPermission(uri,
                (result.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
                        ? Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        : Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        String previous = directory(context);
        context.getSharedPreferences("export", Context.MODE_PRIVATE).edit().putString("tree", uri.toString()).apply();
        if (!previous.isEmpty() && !previous.equals(uri.toString())) {
            for (android.content.UriPermission grant : context.getContentResolver().getPersistedUriPermissions()) {
                if (!grant.getUri().toString().equals(previous)) continue;
                try { context.getContentResolver().releasePersistableUriPermission(grant.getUri(),
                        (grant.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
                                | (grant.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0)); }
                catch (SecurityException ignored) { /* Already revoked by provider. */ }
            }
        }
    }
    static void packet(Context context, PacketRuntime runtime) {
        runtime.pauseRecording();
        if (runtime.data().count == 0) { toast(context, "暂无记录"); return; }
        if (directory(context).isEmpty()) {
            context.startActivity(new Intent(context, ExportActivity.class).putExtra("usb", runtime.usbMode)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        packetSnapshot(context, runtime.session.export(), runtime.usbMode);
    }
    static void packetSnapshot(Context context, PacketRateSession.ExportData data, boolean usb) {
        if (data == null) { toast(context, "暂无可导出的记录"); return; }
        save(context, usb ? "usb-rate" : "ble-rate", writer -> PacketCsvWriter.write(writer, data,
                usb ? "usb_interrupt_in_nonempty_completion_rate" : "ble_selected_characteristic_callback_rate",
                Build.VERSION.SDK_INT, Build.MANUFACTURER + " " + Build.MODEL), null);
    }
    static void save(Context context, String prefix, WriterAction action, Runnable completed) {
        Context app = context.getApplicationContext();
        Handler ui = new Handler(Looper.getMainLooper());
        if (!BUSY.compareAndSet(false, true)) {
            toast(app, "正在导出，请稍候");
            if (completed != null) completed.run();
            return;
        }
        String tree = directory(app);
        WORKER.execute(() -> {
            Uri output = null;
            String result;
            String name = prefix + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date())
                    + "-" + UUID.randomUUID().toString().substring(0, 6) + ".csv";
            try {
                if (tree.isEmpty()) throw new IOException("请在“我的”中设置导出目录");
                Uri treeUri = Uri.parse(tree);
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
                output = DocumentsContract.createDocument(app.getContentResolver(), parent, "text/csv", name);
                if (output == null) throw new IOException("无法创建文件，请重新选择导出目录");
                try (OutputStream stream = app.getContentResolver().openOutputStream(output, "wt")) {
                    if (stream == null) throw new IOException("无法写入文件");
                    try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                        action.write(writer);
                    }
                }
                result = "已导出：" + name;
            } catch (Exception exception) {
                if (output != null) try { DocumentsContract.deleteDocument(app.getContentResolver(), output); }
                catch (Exception ignored) { /* Only the newly created partial file is eligible for cleanup. */ }
                result = "导出失败，请检查目录权限或剩余空间：" + exception.getMessage();
            } finally { BUSY.set(false); }
            String message = result;
            ui.post(() -> { toast(app, message); if (completed != null) completed.run(); });
        });
    }
    private static void toast(Context context, String message) { Toast.makeText(context, message, Toast.LENGTH_LONG).show(); }
}
