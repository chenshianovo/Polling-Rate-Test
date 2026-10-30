package com.gamepad.rate;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BleActivity extends ThemedActivity {
    private static final int CONNECT_PERMISSION = 81, EXPORT_CSV = 82;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private PacketRuntime runtime;
    private PacketRateSession session;
    private BleGattClient client;
    private TextView deviceName, connection, channelName, rate, status, timer, statistics, hex, services;
    private Button chooseDevice, chooseChannel, disconnect, startStop, duration, clear, export;
    private IntervalChartView chart;
    private int seconds = 30;
    private boolean exporting;
    private PacketRateSession.ExportData pendingExport;
    private String shownServices = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            ui.postDelayed(this, 100);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setContentView(R.layout.activity_ble);
        View root = findViewById(R.id.ble_root);
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
        deviceName = findViewById(R.id.ble_device_name);
        connection = findViewById(R.id.ble_connection);
        channelName = findViewById(R.id.ble_channel_name);
        rate = findViewById(R.id.ble_rate);
        status = findViewById(R.id.ble_status);
        timer = findViewById(R.id.ble_timer);
        statistics = findViewById(R.id.ble_statistics);
        hex = findViewById(R.id.ble_hex);
        services = findViewById(R.id.ble_services);
        chooseDevice = findViewById(R.id.ble_choose_device);
        chooseChannel = findViewById(R.id.ble_choose_channel);
        disconnect = findViewById(R.id.ble_disconnect);
        startStop = findViewById(R.id.ble_start_stop);
        duration = findViewById(R.id.ble_duration);
        clear = findViewById(R.id.ble_clear);
        export = findViewById(R.id.ble_export);
        chart = findViewById(R.id.ble_chart);
        runtime = PacketRuntime.get(this, false);
        session = runtime.session;
        client = runtime.bleClient;
        findViewById(R.id.overlay_button).setOnClickListener(view -> RateOverlayService.toggle(this, runtime));
        chooseDevice.setOnClickListener(view -> showDevices());
        chooseChannel.setOnClickListener(view -> showChannels());
        disconnect.setOnClickListener(view -> client.disconnect("已关闭本 App 的 GATT 连接"));
        startStop.setOnClickListener(view -> {
            if (session.isRecording()) client.stop("已手动停止");
            else client.start(seconds);
        });
        clear.setOnClickListener(view -> {
            client.stop("已清空");
            session.clear();
            render();
        });
        duration.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("测试时长")
                .setItems(new String[]{"10 秒", "30 秒", "60 秒", "手动停止"}, (dialog, which) -> {
                    seconds = new int[]{10, 30, 60, 0}[which];
                    render();
                }).show());
        export.setOnClickListener(view -> chooseExport());
        findViewById(R.id.ble_settings).setOnClickListener(view -> {
            try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
            catch (ActivityNotFoundException exception) { toast("没有可打开的系统蓝牙设置"); }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        runtime.visible = true;
        runtime.focus(hasWindowFocus());
        ui.removeCallbacks(refresh);
        ui.post(refresh);
    }

    @Override protected void onPause() {
        runtime.pause();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (runtime != null) runtime.focus(hasFocus);
    }

    @Override protected void onDestroy() {
        // Receiver ownership belongs to PacketRuntime while a foreground overlay is active.
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission") // Guarded by runtime check, with revocation caught below.
    private void showDevices() {
        if (!hasConnectPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, CONNECT_PERMISSION);
            return;
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) { toast("此设备没有可用的蓝牙适配器"); return; }
        try {
            if (!adapter.isEnabled()) { toast("请先在系统设置中打开蓝牙"); return; }
            Map<String, DeviceChoice> choices = new LinkedHashMap<>();
            for (BluetoothDevice device : manager.getConnectedDevices(BluetoothProfile.GATT)) {
                choices.put(device.getAddress(), new DeviceChoice(device, true));
            }
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if (device.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC) continue;
                if (!choices.containsKey(device.getAddress())) choices.put(device.getAddress(), new DeviceChoice(device, false));
            }
            if (choices.isEmpty()) {
                new AlertDialog.Builder(this).setTitle("没有可选 BLE 设备")
                        .setMessage("系统未报告 GATT 已连接设备，也没有可用的 BLE 配对记录。请先在系统设置中配对，或用设备原 App 建立 BLE 连接后再刷新。本页面不扫描附近设备，也不能列出所有经典蓝牙连接。")
                        .setPositiveButton("知道了", null).show();
                return;
            }
            List<DeviceChoice> list = new ArrayList<>(choices.values());
            String[] labels = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                DeviceChoice choice = list.get(i);
                String name = choice.device.getName();
                labels[i] = (choice.connected ? "[GATT 已连接] " : "[仅已配对，连接待确认] ")
                        + (name == null || name.isEmpty() ? "未命名设备" : name) + "\n" + choice.device.getAddress();
            }
            new AlertDialog.Builder(this).setTitle("选择设备 · 新连接会清空本轮")
                    .setItems(labels, (dialog, which) -> client.connect(list.get(which).device)).show();
        } catch (SecurityException exception) {
            toast("蓝牙权限不可用，请在系统应用权限中允许“附近设备”");
        } catch (IllegalStateException exception) {
            toast("蓝牙服务尚未就绪，请稍后重试");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CONNECT_PERMISSION) {
            if (hasConnectPermission()) showDevices();
            else toast("需要附近设备连接权限，才能列出设备并读取 BLE 服务");
        }
    }

    private void showChannels() {
        BleGattClient.State state = client.state();
        if (!state.connected || state.busy || state.channels.isEmpty()) return;
        List<BleGattClient.Channel> choices = state.channels;
        String[] labels = new String[choices.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = choices.get(i).label;
        new AlertDialog.Builder(this).setTitle("选择数据通道 · 切换会清空本轮")
                .setItems(labels, (dialog, which) -> client.select(choices.get(which).key)).show();
    }

    private void render() {
        if (client == null) return;
        ((Button) findViewById(R.id.overlay_button)).setText(runtime.overlay ? "关闭悬浮窗并停止测试" : "开启透明悬浮窗");
        long now = SystemClock.elapsedRealtimeNanos();
        BleGattClient.State transport = client.state();
        PacketRateSession.ViewData data = session.view(now);
        deviceName.setText(transport.deviceName);
        connection.setText(transport.status);
        channelName.setText(transport.selected == null ? "尚未选择接收通道" : transport.selected.label);
        rate.setText(data.hz < 0 ? "—" : Integer.toString(data.hz));
        String message = data.reason;
        if (data.recording && data.count == 0) message = "等待设备数据；如需私有启动命令，本版无法自动猜测";
        else if (data.recording && now - data.previewTime > RateStatistics.SECOND_NS) message = "最近 1 秒没有收到选定通道的数据";
        else if (data.recording && data.elapsedNs < RateStatistics.SECOND_NS) message = "正在积累首个 1 秒窗口…";
        status.setText(message);
        timer.setText(String.format(Locale.US, "%s · %.1f 秒 · %,d 次接收", data.recording ? "采集中" : "已停止", data.elapsedNs / 1e9, data.count));
        statistics.setText(getString(R.string.ble_stats, number(data.averageHz), number(data.meanMs), number(data.minMs),
                number(data.maxMs), number(data.window.p95Ms), number(data.window.jitterMs), data.count, data.bytes, data.equalTimes, data.backwards));
        chart.setIntervals(data.intervals);
        hex.setText(data.previewTime < 0 ? "尚未收到数据" : "完整长度 " + data.previewLength + " 字节\n"
                + PacketRateSession.hex(data.preview) + (data.previewLength > 64 ? " …（预览截断）" : ""));
        if (!shownServices.equals(transport.services)) {
            shownServices = transport.services;
            services.setText(shownServices);
        }
        chooseDevice.setEnabled(!data.recording && !exporting);
        chooseChannel.setEnabled(transport.connected && !transport.busy && !data.recording && !exporting && !transport.channels.isEmpty());
        disconnect.setEnabled((transport.connected || transport.busy) && !exporting);
        startStop.setEnabled(!exporting && (data.recording || (transport.ready && !transport.busy)));
        startStop.setText(data.recording ? "停止测试" : data.count > 0 ? "重新测试" : "开始测试");
        duration.setEnabled(!data.recording && !exporting);
        duration.setText(seconds == 0 ? "时长：手动" : "时长：" + seconds + " 秒");
        clear.setEnabled(!exporting);
        export.setEnabled(!exporting && !data.recording && data.count > 0);
        if (data.recording) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void chooseExport() {
        if (exporting) return;
        pendingExport = session.export();
        if (pendingExport == null) return;
        exporting = true;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "ble-rate-"
                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".csv");
        try { startActivityForResult(intent, EXPORT_CSV); }
        catch (ActivityNotFoundException exception) {
            exporting = false;
            pendingExport = null;
            toast("没有可用的文件保存应用");
        }
        render();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_CSV) return;
        PacketRateSession.ExportData snapshot = pendingExport;
        pendingExport = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || snapshot == null) {
            exporting = false;
            render();
            return;
        }
        Uri uri = data.getData();
        new Thread(() -> {
            String outcome;
            try (OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
                if (stream == null) throw new IOException("无法打开输出文件");
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                    PacketCsvWriter.write(writer, snapshot, "ble_selected_characteristic_callback_rate",
                            Build.VERSION.SDK_INT, Build.MANUFACTURER + " " + Build.MODEL);
                }
                outcome = "CSV 已保存，共 " + snapshot.lengths.length + " 次接收";
            } catch (IOException | SecurityException exception) { outcome = "保存失败：" + exception.getMessage(); }
            String result = outcome;
            ui.post(() -> {
                if (isDestroyed()) return;
                exporting = false;
                toast(result);
                render();
            });
        }, "ble-csv-export").start();
    }

    private static String number(double value) { return Double.isNaN(value) ? "—" : String.format(Locale.US, "%.2f", value); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private static final class DeviceChoice {
        final BluetoothDevice device;
        final boolean connected;
        DeviceChoice(BluetoothDevice device, boolean connected) { this.device = device; this.connected = connected; }
    }
}
