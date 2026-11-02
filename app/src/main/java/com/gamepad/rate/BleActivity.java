package com.gamepad.rate;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BleActivity extends ThemedActivity {
    private static final int CONNECT_PERMISSION = 81;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private PacketRuntime runtime;
    private PacketRateSession session;
    private BleGattClient client;
    private TextView deviceName, connection, channelName, rate, status, timer, statistics, hex, services, linkInfo;
    private Button chooseDevice, chooseChannel, disconnect, startStop, duration, clear, export;
    private IntervalChartView chart;
    private int seconds;
    private String shownServices = "";
    private String shownLinkInfo = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            ui.postDelayed(this, 100);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ble);
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
        linkInfo = findViewById(R.id.ble_link_info);
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
        seconds = runtime.seconds;
        client = runtime.bleClient;
        findViewById(R.id.overlay_button).setOnClickListener(view -> RateOverlayService.toggle(this, runtime));
        chooseDevice.setOnClickListener(view -> withOverlayPermission(this::showDevices));
        chooseChannel.setOnClickListener(view -> showChannels());
        disconnect.setOnClickListener(view -> runtime.disconnect("已断开"));
        startStop.setOnClickListener(view -> {
            if (session.isRecording()) runtime.pauseRecording();
            else runtime.startOrResume();
        });
        clear.setOnClickListener(view -> {
            runtime.cancelAutoStart(); client.stop("已清空");
            session.clear();
            render();
        });
        duration.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("测试时长")
                .setItems(new String[]{"10 秒", "30 秒", "60 秒", "手动停止"}, (dialog, which) -> {
                    seconds = new int[]{10, 30, 60, 0}[which]; runtime.seconds = seconds;
                    render();
                }).show());
        export.setOnClickListener(view -> chooseExport());
        findViewById(R.id.ble_refresh_info).setOnClickListener(view -> client.refreshLinkInfo());
        findViewById(R.id.ble_mtu).setOnClickListener(view -> showMtuChoices());
        findViewById(R.id.ble_settings).setOnClickListener(view -> managePairing());
    }

    private void managePairing() {
        BleGattClient.State state = client.state();
        String device = state.address.isEmpty() ? "" : state.deviceName + "\n\n";
        new AlertDialog.Builder(this).setTitle("管理配对")
                .setMessage(device + "普通应用不能直接删除系统配对。请在系统蓝牙设置中选择设备，点击取消配对。")
                .setPositiveButton("打开蓝牙设置", (dialog, which) -> {
                    runtime.disconnect("进入系统管理配对，已停止本 App 连接");
                    try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
                    catch (ActivityNotFoundException error) { toast("没有可打开的系统蓝牙设置"); }
                }).setNegativeButton("取消", null).show();
    }

    private void showMtuChoices() {
        BleGattClient.State state = client.state();
        if (!state.connected || state.busy || session.isRecording()) return;
        new AlertDialog.Builder(this).setTitle("协商 ATT MTU")
                .setMessage("这是主动协商，会改变连接参数，ATT MTU 不等于 LL 包长。Android 14 起，首次请求由系统按 517 发起，最终以设备回调为准。")
                .setPositiveButton("选择请求值", (dialog, which) -> new AlertDialog.Builder(this)
                        .setTitle("请求 ATT MTU")
                        .setItems(new String[]{"23", "185", "247", "517"}, (picker, index) ->
                                client.requestMtu(new int[]{23, 185, 247, 517}[index]))
                        .setNegativeButton("取消", null).show())
                .setNegativeButton("取消", null).show();
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
                    .setItems(labels, (dialog, which) -> { runtime.armAutoStart(); client.connect(list.get(which).device); }).show();
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
                .setItems(labels, (dialog, which) -> withOverlayPermission(() -> {
                    String key = choices.get(which).key;
                    BleGattClient.State current = client.state();
                    if (current.ready && current.selected != null && current.selected.key.equals(key)) RateOverlayService.open(this, runtime, true);
                    else { runtime.armAutoStart(); client.select(key); }
                })).show();
    }

    private void render() {
        if (client == null) return;
        ((Button) findViewById(R.id.overlay_button)).setText(runtime.overlay ? "关闭悬浮窗" : "打开悬浮窗");
        runtime.autoStart(this);
        long now = SystemClock.elapsedRealtimeNanos();
        BleGattClient.State transport = client.state();
        PacketRateSession.ViewData data = session.view(now);
        deviceName.setText(transport.deviceName);
        connection.setText(transport.status);
        channelName.setText(transport.selected == null ? "尚未选择接收通道" : transport.selected.label);
        rate.setText(data.hz < 0 ? "—" : Integer.toString(data.hz));
        String message = data.reason;
        if (data.recording && data.count == 0) message = "等待数据";
        else if (data.recording && now - data.previewTime > RateStatistics.SECOND_NS) message = "暂无新数据";
        else if (data.recording && data.elapsedNs < RateStatistics.SECOND_NS) message = "记录中";
        status.setText(message);
        timer.setText(String.format(Locale.US, "%s · %.1f 秒 · %,d 次接收", data.recording ? "记录中" : session.isPaused() ? "已暂停" : "已停止", data.elapsedNs / 1e9, data.count));
        statistics.setText(getString(R.string.ble_stats, number(data.averageHz), number(data.meanMs), number(data.minMs),
                number(data.maxMs), number(data.window.p95Ms), number(data.window.jitterMs), data.count, data.bytes, data.equalTimes, data.backwards));
        chart.setIntervals(data.intervals);
        hex.setText(data.previewTime < 0 ? "尚未收到数据" : "完整长度 " + data.previewLength + " 字节\n"
                + PacketRateSession.hex(data.preview) + (data.previewLength > 64 ? " …" : ""));
        if (!shownServices.equals(transport.services)) {
            shownServices = transport.services;
            services.setText(shownServices);
        }
        if (!shownLinkInfo.equals(transport.connectionDetails)) {
            shownLinkInfo = transport.connectionDetails;
            linkInfo.setText(shownLinkInfo);
        }
        boolean canReadInfo = transport.connected && !transport.busy && !data.recording;
        findViewById(R.id.ble_refresh_info).setEnabled(canReadInfo);
        findViewById(R.id.ble_mtu).setEnabled(canReadInfo);
        chooseDevice.setEnabled(!data.recording);
        chooseChannel.setEnabled(transport.connected && !transport.busy && !data.recording && !transport.channels.isEmpty());
        disconnect.setEnabled((transport.connected || transport.busy));
        startStop.setEnabled((data.recording || (transport.ready && !transport.busy)));
        startStop.setText(data.recording ? "暂停" : session.isPaused() ? "继续" : data.count > 0 ? "重新开始" : "开始");
        duration.setEnabled(!data.recording && !session.isPaused());
        duration.setText(seconds == 0 ? "时长：手动" : "时长：" + seconds + " 秒");
        clear.setEnabled(true);
        export.setEnabled(data.count > 0);
        if (data.recording) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void chooseExport() { ExportStore.packet(this, runtime); render(); }

    private static String number(double value) { return Double.isNaN(value) ? "—" : String.format(Locale.US, "%.2f", value); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private static final class DeviceChoice {
        final BluetoothDevice device;
        final boolean connected;
        DeviceChoice(BluetoothDevice device, boolean connected) { this.device = device; this.connected = connected; }
    }
}
