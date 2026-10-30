package com.gamepad.rate;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class UsbActivity extends ThemedActivity {
    private static final int EXPORT_CSV = 92;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private PacketRuntime runtime;
    private PacketRateSession session;
    private final String permissionAction = "com.gamepad.rate.USB_PERMISSION." + UUID.randomUUID();
    private UsbManager manager;
    private UsbHostClient client;
    private UsbDevice selectedDevice, pendingDevice;
    private PendingIntent permissionIntent;
    private boolean resumed, permissionReturned, exporting;
    private int seconds = 30;
    private PacketRateSession.ExportData pendingExport;
    private TextView deviceName, connection, channelName, rate, status, timer, statistics, hex;
    private Button chooseDevice, chooseChannel, disconnect, startStop, duration, clear, export;
    private IntervalChartView chart;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); ui.postDelayed(this, 100); }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (permissionAction.equals(intent.getAction())) {
                permissionReturned = true;
                finishPermission();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                UsbDevice device = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                        : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    client.detached(device);
                    if (selectedDevice != null && selectedDevice.getDeviceName().equals(device.getDeviceName())) selectedDevice = null;
                    if (pendingDevice != null && pendingDevice.getDeviceName().equals(device.getDeviceName())) pendingDevice = null;
                }
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setContentView(R.layout.activity_usb);
        View root = findViewById(R.id.usb_root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
        Appearance.bind(this);
        manager = (UsbManager) getSystemService(USB_SERVICE);
        runtime = PacketRuntime.get(this, true);
        session = runtime.session;
        client = runtime.usbClient;
        findViewById(R.id.overlay_button).setOnClickListener(view -> RateOverlayService.toggle(this, runtime));
        deviceName = findViewById(R.id.usb_device_name); connection = findViewById(R.id.usb_connection);
        channelName = findViewById(R.id.usb_channel_name); rate = findViewById(R.id.usb_rate);
        status = findViewById(R.id.usb_status); timer = findViewById(R.id.usb_timer);
        statistics = findViewById(R.id.usb_statistics); hex = findViewById(R.id.usb_hex);
        chooseDevice = findViewById(R.id.usb_choose_device); chooseChannel = findViewById(R.id.usb_choose_channel);
        disconnect = findViewById(R.id.usb_disconnect); startStop = findViewById(R.id.usb_start_stop);
        duration = findViewById(R.id.usb_duration); clear = findViewById(R.id.usb_clear);
        export = findViewById(R.id.usb_export); chart = findViewById(R.id.usb_chart);
        chooseDevice.setOnClickListener(view -> showDevices());
        chooseChannel.setOnClickListener(view -> { if (selectedDevice != null) showChannels(selectedDevice); });
        disconnect.setOnClickListener(view -> client.disconnect("USB 接口已释放；系统输入未恢复时请重新插拔"));
        startStop.setOnClickListener(view -> { if (session.isRecording()) client.stop("已手动停止"); else client.start(seconds); });
        clear.setOnClickListener(view -> { client.stop("已清空"); session.clear(); render(); });
        duration.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("测试时长")
                .setItems(new String[]{"10 秒", "30 秒", "60 秒", "手动停止"}, (dialog, which) -> {
                    seconds = new int[]{10, 30, 60, 0}[which]; render();
                }).show());
        export.setOnClickListener(view -> chooseExport());
        IntentFilter filter = new IntentFilter(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        runtime.visible = true;
        runtime.focus(hasWindowFocus());
        ui.removeCallbacks(refresh); ui.post(refresh);
        finishPermission();
    }
    @Override protected void onPause() {
        resumed = false;
        runtime.pause();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.removeCallbacks(refresh);
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (runtime != null) runtime.focus(focus);
        if (focus) finishPermission();
    }
    @Override protected void onDestroy() {
        unregisterReceiver(receiver);
        if (permissionIntent != null) permissionIntent.cancel();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void showDevices() {
        if (manager == null || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            toast("此手机未声明 USB Host 支持"); return;
        }
        List<UsbDevice> devices = new ArrayList<>(manager.getDeviceList().values());
        if (devices.isEmpty()) { toast("没有 USB 设备，请检查 OTG 开关、数据线和供电"); return; }
        String[] labels = new String[devices.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = UsbHostClient.label(devices.get(i)) + "\n" + devices.get(i).getDeviceName();
        new AlertDialog.Builder(this).setTitle("选择 USB 设备")
                .setItems(labels, (dialog, which) -> requestDevice(devices.get(which))).show();
    }
    private void requestDevice(UsbDevice device) {
        if (manager.hasPermission(device)) { showChannels(device); return; }
        pendingDevice = device; permissionReturned = false;
        if (permissionIntent != null) permissionIntent.cancel();
        // Immutable callback: permission is verified through UsbManager, not extras in the broadcast.
        permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(permissionAction).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try { manager.requestPermission(device, permissionIntent); }
        catch (RuntimeException exception) { pendingDevice = null; toast("USB 授权请求失败：" + exception.getMessage()); }
    }
    private void finishPermission() {
        if (!resumed || !hasWindowFocus() || !permissionReturned || pendingDevice == null) return;
        UsbDevice device = pendingDevice;
        pendingDevice = null; permissionReturned = false;
        if (manager.hasPermission(device) && manager.getDeviceList().containsKey(device.getDeviceName())) showChannels(device);
        else toast("USB 访问未授权或设备已拔出");
    }
    private void showChannels(UsbDevice device) {
        List<UsbHostClient.Channel> channels = UsbHostClient.channels(device);
        if (channels.isEmpty()) { toast("未发现支持的 Interrupt IN 端点；暂不支持 Bulk、非默认 Alt 或高带宽多事务端点"); return; }
        String[] labels = new String[channels.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = channels.get(i).label;
        new AlertDialog.Builder(this).setTitle("选择端点 · 连接后清空本轮")
                .setItems(labels, (dialog, which) -> new AlertDialog.Builder(this).setTitle("连接 USB 接收端点")
                        .setMessage("将接管所选接口，期间系统手柄输入可能暂停。断开后会释放；如未恢复，请重新插拔。\n\n" + labels[which])
                        .setPositiveButton("连接并接管接口", (confirm, button) -> {
                            selectedDevice = device; client.connect(device, channels.get(which));
                        }).setNegativeButton("取消", null).show()).show();
    }

    private void render() {
        ((Button) findViewById(R.id.overlay_button)).setText(runtime.overlay ? "关闭悬浮窗并停止测试" : "开启透明悬浮窗");
        long now = SystemClock.elapsedRealtimeNanos();
        UsbHostClient.State transport = client.state();
        PacketRateSession.ViewData data = session.view(now);
        deviceName.setText(transport.device); connection.setText(transport.status);
        channelName.setText(transport.channel.isEmpty() ? "尚未选择端点" : transport.channel);
        rate.setText(data.hz < 0 ? "—" : Integer.toString(data.hz));
        status.setText(data.recording && (data.count == 0 || now - data.previewTime > RateStatistics.SECOND_NS)
                ? "等待 USB 数据 · 请操作手柄；需要私有初始化的设备可能无数据" : data.reason);
        timer.setText(String.format(Locale.US, "%s · %.1f 秒 · %,d 次接收", data.recording ? "采集中" : "已停止", data.elapsedNs / 1e9, data.count));
        statistics.setText(getString(R.string.ble_stats, number(data.averageHz), number(data.meanMs), number(data.minMs), number(data.maxMs),
                number(data.window.p95Ms), number(data.window.jitterMs), data.count, data.bytes, data.equalTimes, data.backwards));
        chart.setIntervals(data.intervals);
        hex.setText(data.previewTime < 0 ? "尚未收到数据" : "完整长度 " + data.previewLength + " 字节\n"
                + PacketRateSession.hex(data.preview) + (data.previewLength > 64 ? " …（预览截断，CSV 保留完整内容）" : ""));
        chooseDevice.setEnabled(!data.recording && !exporting);
        chooseChannel.setEnabled(selectedDevice != null && !data.recording && !exporting);
        disconnect.setEnabled(transport.ready || transport.busy);
        startStop.setEnabled(!exporting && (data.recording || transport.ready));
        startStop.setText(data.recording ? "停止测试" : data.count > 0 ? "重新测试" : "开始测试");
        duration.setEnabled(!data.recording && !exporting);
        duration.setText(seconds == 0 ? "时长：手动" : "时长：" + seconds + " 秒");
        clear.setEnabled(!exporting); export.setEnabled(!exporting && !data.recording && data.count > 0);
        if (data.recording) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void chooseExport() {
        if (exporting) return;
        pendingExport = session.export(); if (pendingExport == null) return;
        exporting = true;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv")
                .putExtra(Intent.EXTRA_TITLE, "usb-rate-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".csv");
        try { startActivityForResult(intent, EXPORT_CSV); }
        catch (ActivityNotFoundException exception) { exporting = false; pendingExport = null; toast("没有可用的文件保存应用"); }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_CSV) return;
        PacketRateSession.ExportData snapshot = pendingExport; pendingExport = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || snapshot == null) { exporting = false; return; }
        Uri uri = data.getData();
        new Thread(() -> {
            String outcome;
            try (OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
                if (stream == null) throw new IOException("无法打开输出文件");
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                    PacketCsvWriter.write(writer, snapshot, "usb_interrupt_in_nonempty_completion_rate", Build.VERSION.SDK_INT,
                            Build.MANUFACTURER + " " + Build.MODEL);
                }
                outcome = "CSV 已保存，共 " + snapshot.lengths.length + " 次接收，包含完整 payload";
            } catch (IOException | SecurityException exception) { outcome = "保存失败：" + exception.getMessage(); }
            String result = outcome;
            ui.post(() -> { if (!isDestroyed()) { exporting = false; toast(result); render(); } });
        }, "usb-csv-export").start();
    }
    private static String number(double value) { return Double.isNaN(value) ? "—" : String.format(Locale.US, "%.2f", value); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
