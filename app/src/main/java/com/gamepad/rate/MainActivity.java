package com.gamepad.rate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
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
import java.util.TreeSet;

public final class MainActivity extends ThemedActivity implements InputManager.InputDeviceListener {
    private static final int SAVE_CSV = 40;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RateStatistics stats = new RateStatistics();
    private final List<InputDevice> devices = new ArrayList<>();
    private final TreeSet<Integer> pressed = new TreeSet<>();
    private InputManager inputManager;
    private TextView deviceName, deviceDetails, rate, status, timer, statistics, axes, buttons;
    private Button startStop, selectDevice, durationButton, clearButton, exportButton;
    private IntervalChartView chart;
    private StickView sticks;
    private int selectedId = -1;
    private int durationSeconds = 30;
    private int keyPresses;
    private boolean recording;
    private boolean exporting;
    private long startMs, endMs, deadlineMs;
    private long callbacks;
    private String state = "先在系统蓝牙设置中配对手柄，再选择设备。";
    private String lastKey = "尚无按键";
    private String sessionDevice = "";
    private String sessionIdentity = "";
    private String sessionStarted = "";
    private float lx, ly, rx, ry;
    private boolean hasLeft, hasRight;
    private int[] axisCodes = new int[0];
    private float[] axisValues = new float[0];
    private String[] axisNames = new String[0];
    private boolean hasAxisSample;
    private ExportData pendingExport;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            if (recording && deadlineMs > 0 && now >= deadlineMs) {
                finish("定时测试完成", deadlineMs);
            }
            render();
            handler.postDelayed(this, 100); // UI refresh rate is independent of sample counting.
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 统一使用内容延伸到系统栏下方、再手动应用 inset 的布局策略。
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
        Appearance.bind(this);
        deviceName = findViewById(R.id.device_name);
        deviceDetails = findViewById(R.id.device_details);
        rate = findViewById(R.id.rate);
        status = findViewById(R.id.status);
        timer = findViewById(R.id.timer);
        statistics = findViewById(R.id.statistics);
        axes = findViewById(R.id.axes);
        buttons = findViewById(R.id.buttons);
        startStop = findViewById(R.id.start_stop);
        selectDevice = findViewById(R.id.select_device);
        durationButton = findViewById(R.id.duration);
        clearButton = findViewById(R.id.clear);
        exportButton = findViewById(R.id.export);
        chart = findViewById(R.id.chart);
        sticks = findViewById(R.id.sticks);
        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        findViewById(R.id.open_usb).setOnClickListener(view -> startActivity(new Intent(this, UsbActivity.class)));
        findViewById(R.id.open_ble).setOnClickListener(view -> startActivity(new Intent(this, BleActivity.class)));
        ((TextView) findViewById(R.id.precision)).setText(Build.VERSION.SDK_INT >= 34
                ? "时间戳：纳秒单位（Android 14+）；单位精度不代表硬件测量准确度。"
                : "时间戳：毫秒精度（Android 8–13）。高频事件可能出现相同时间戳，间隔和抖动会受量化影响。建议用 Android 14+。" );
        startStop.setOnClickListener(view -> {
            if (recording) finish("已手动停止", SystemClock.uptimeMillis()); else startMeasurement();
            render();
        });
        selectDevice.setOnClickListener(view -> showDevices());
        durationButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("测试时长")
                .setItems(new String[]{"10 秒", "30 秒", "60 秒", "手动停止"}, (dialog, which) -> {
                    durationSeconds = new int[]{10, 30, 60, 0}[which];
                    render();
                }).show());
        clearButton.setOnClickListener(view -> {
            clearMeasurement();
            state = "已清空，持续转动摇杆后开始新一轮。";
            render();
        });
        exportButton.setOnClickListener(view -> chooseExport());
        findViewById(R.id.bluetooth_settings).setOnClickListener(view -> {
            try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
            catch (ActivityNotFoundException exception) { toast("此设备没有可打开的蓝牙设置页面"); }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        inputManager.registerInputDeviceListener(this, handler);
        refreshDevices();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        if (recording) finish("应用离开前台，测试已停止", SystemClock.uptimeMillis());
        handler.removeCallbacks(refresh);
        inputManager.unregisterInputDeviceListener(this);
        clearInputPreview();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && recording) finish("窗口失去焦点，测试已停止", SystemClock.uptimeMillis());
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static boolean isController(InputDevice device) {
        return device != null && !device.isVirtual() && (device.supportsSource(InputDevice.SOURCE_GAMEPAD)
                || device.supportsSource(InputDevice.SOURCE_JOYSTICK));
    }

    private void refreshDevices() {
        devices.clear();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (isController(device)) devices.add(device);
        }
        boolean stillPresent = false;
        for (InputDevice device : devices) if (device.getId() == selectedId) stillPresent = true;
        if (!stillPresent) {
            if (recording) finish("手柄断开，测试已停止", SystemClock.uptimeMillis());
            selectedId = devices.isEmpty() ? -1 : devices.get(0).getId();
            clearInputPreview();
        }
        updateDeviceLabel();
        render();
    }

    private void updateDeviceLabel() {
        InputDevice device = InputDevice.getDevice(selectedId);
        deviceName.setText(device == null ? "未发现手柄" : device.getName());
        deviceDetails.setText(device == null ? "请切换手柄至 Android / HID 模式并完成系统配对。"
                : String.format(Locale.US, "VID %04X · PID %04X · ID %d\n系统输入设备；此接口不确认蓝牙 / USB 连接类型。",
                device.getVendorId(), device.getProductId(), device.getId()));
        List<InputDevice.MotionRange> ranges = new ArrayList<>();
        if (device != null) for (InputDevice.MotionRange range : device.getMotionRanges()) {
            if ((range.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) ranges.add(range);
        }
        axisCodes = new int[ranges.size()];
        axisValues = new float[ranges.size()];
        axisNames = new String[ranges.size()];
        hasAxisSample = false;
        for (int i = 0; i < ranges.size(); i++) {
            axisCodes[i] = ranges.get(i).getAxis();
            axisNames[i] = MotionEvent.axisToString(axisCodes[i]).replace("AXIS_", "");
        }
    }

    private void showDevices() {
        refreshDevices();
        if (devices.isEmpty()) { toast("尚未识别到手柄，请先在蓝牙设置中连接"); return; }
        final List<InputDevice> choices = new ArrayList<>(devices);
        String[] names = new String[choices.size()];
        for (int i = 0; i < names.length; i++) names[i] = choices.get(i).getName() + " · ID " + choices.get(i).getId();
        new AlertDialog.Builder(this).setTitle("选择手柄（切换会清空本轮数据）")
                .setItems(names, (dialog, which) -> {
                    int id = choices.get(which).getId();
                    if (!isController(InputDevice.getDevice(id))) { toast("该设备已断开"); return; }
                    if (id != selectedId) {
                        clearMeasurement();
                        clearInputPreview();
                        selectedId = id;
                        state = "设备已选择，持续转动摇杆并开始测试。";
                    }
                    updateDeviceLabel();
                    render();
                }).show();
    }

    private void startMeasurement() {
        InputDevice device = InputDevice.getDevice(selectedId);
        if (!isController(device)) { refreshDevices(); toast("请先连接并选择手柄"); return; }
        clearMeasurement();
        sessionDevice = device.getName();
        sessionIdentity = String.format(Locale.US, "VID=%04X PID=%04X input_id=%d", device.getVendorId(), device.getProductId(), selectedId);
        sessionStarted = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date());
        startMs = SystemClock.uptimeMillis();
        deadlineMs = durationSeconds == 0 ? 0 : startMs + durationSeconds * 1000L;
        recording = true;
        state = "正在采集，请持续转动摇杆。";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void finish(String reason, long timeMs) {
        if (!recording) return;
        recording = false;
        endMs = deadlineMs > 0 ? Math.min(timeMs, deadlineMs) : timeMs;
        state = reason;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void clearMeasurement() {
        finish("已清空", SystemClock.uptimeMillis());
        stats.clear();
        startMs = endMs = deadlineMs = callbacks = 0;
        keyPresses = 0;
        sessionDevice = sessionIdentity = sessionStarted = "";
    }

    private void clearInputPreview() {
        pressed.clear();
        lx = ly = rx = ry = 0;
        hasLeft = hasRight = false;
        hasAxisSample = false;
        lastKey = "尚无按键";
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getDeviceId() != selectedId || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)
                || event.getActionMasked() != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(event);
        updateAxes(event);
        if (recording) {
            long deliveryMs = SystemClock.uptimeMillis();
            callbacks++;
            // 一个回调可能包含多个采样：先记录历史，再记录当前，不能只数回调次数。
            for (int i = 0; i < event.getHistorySize(); i++) {
                long timestamp = Build.VERSION.SDK_INT >= 34 ? event.getHistoricalEventTimeNanos(i)
                        : event.getHistoricalEventTime(i) * 1_000_000L;
                recordSample(timestamp, deliveryMs, true);
            }
            long timestamp = Build.VERSION.SDK_INT >= 34 ? event.getEventTimeNanos() : event.getEventTime() * 1_000_000L;
            recordSample(timestamp, deliveryMs, false);
            if (recording && deadlineMs > 0 && deliveryMs >= deadlineMs) finish("定时测试完成", deadlineMs);
        }
        return true;
    }

    private void recordSample(long timestamp, long deliveryMs, boolean historical) {
        if (!recording || timestamp < startMs * 1_000_000L) return;
        if (deadlineMs > 0 && timestamp >= deadlineMs * 1_000_000L) return;
        stats.add(timestamp, deliveryMs, historical);
        if (stats.full()) finish("已达到 250000 个采样上限，请导出后重新测试", deliveryMs);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean controllerKey = KeyEvent.isGamepadButton(key) || key == KeyEvent.KEYCODE_BACK
                || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_CENTER;
        if (event.getDeviceId() != selectedId || selectedId < 0 || !controllerKey) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (pressed.add(key) && event.getRepeatCount() == 0 && recording) keyPresses++;
            lastKey = KeyEvent.keyCodeToString(key) + " 按下";
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            pressed.remove(key);
            lastKey = KeyEvent.keyCodeToString(key) + " 松开";
        }
        // 按键连发不是摇杆报告；消费手柄按键，避免误触开始、清空等界面按钮。
        return true;
    }

    private void updateAxes(MotionEvent event) {
        InputDevice device = event.getDevice();
        if (device == null) return;
        hasLeft = hasAxis(device, MotionEvent.AXIS_X) && hasAxis(device, MotionEvent.AXIS_Y);
        int rightX = MotionEvent.AXIS_Z;
        int rightY = MotionEvent.AXIS_RZ;
        if (!hasAxis(device, rightX) || !hasAxis(device, rightY)) {
            rightX = MotionEvent.AXIS_RX;
            rightY = MotionEvent.AXIS_RY;
        }
        hasRight = hasAxis(device, rightX) && hasAxis(device, rightY);
        lx = normalizedAxis(event, device, MotionEvent.AXIS_X);
        ly = normalizedAxis(event, device, MotionEvent.AXIS_Y);
        rx = hasRight ? normalizedAxis(event, device, rightX) : 0;
        ry = hasRight ? normalizedAxis(event, device, rightY) : 0;
        // No formatting or UI drawing in the high-frequency input path.
        for (int i = 0; i < axisCodes.length; i++) axisValues[i] = event.getAxisValue(axisCodes[i]);
        hasAxisSample = true;
    }

    private static boolean hasAxis(InputDevice device, int axis) {
        return device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null;
    }

    private static float normalizedAxis(MotionEvent event, InputDevice device, int axis) {
        InputDevice.MotionRange range = device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK);
        if (range == null || range.getRange() <= 0) return 0;
        return 2 * (event.getAxisValue(axis) - range.getMin()) / range.getRange() - 1;
    }

    private void render() {
        long now = SystemClock.uptimeMillis();
        long displayedNow = recording ? now : endMs;
        long elapsed = startMs == 0 ? 0 : Math.max(0, displayedNow - startMs);
        rate.setText(startMs == 0 || elapsed < 1000 ? "—" : Integer.toString(stats.recentCount(displayedNow * 1_000_000L)));
        String message = state;
        if (recording) {
            if (stats.size() == 0) message = "等待摇杆采样 · 请持续转动摇杆";
            else if (now * 1_000_000L - stats.lastTime() > 500_000_000L) message = "暂无新采样 · 请继续转动摇杆";
            else if (elapsed < 1000) message = "正在积累首个 1 秒窗口…";
        }
        status.setText(message);
        timer.setText(String.format(Locale.US, "%s · %.1f 秒 · %,d 个采样\n%s",
                recording ? "采集中" : "已停止", elapsed / 1000.0, stats.size(),
                sessionDevice.isEmpty() ? "点击开始后记录本轮数据" : "本轮设备：" + sessionDevice));
        RateStatistics.Window window = stats.window();
        statistics.setText("全程平均  " + number(stats.averageHz(), " Hz")
                + "\n平均间隔  " + number(stats.meanMs(), " ms")
                + "\n最小 / 最大  " + number(stats.minMs(), "") + " / " + number(stats.maxMs(), " ms")
                + "\nP95 / 抖动  " + number(window.p95Ms, "") + " / " + number(window.jitterMs, " ms")
                + "\n历史采样  " + stats.historyCount() + "  ·  回调 " + callbacks
                + "\n相同时间戳  " + stats.equalTimes() + "  ·  逆序忽略 " + stats.backwards());
        chart.setIntervals(stats.recentIntervals(160));
        sticks.setAxes(lx, ly, rx, ry, hasLeft, hasRight);
        StringBuilder axisText = new StringBuilder();
        if (hasAxisSample) for (int i = 0; i < axisCodes.length; i++) {
            if (i > 0) axisText.append("  ");
            axisText.append(axisNames[i]).append('=').append(String.format(Locale.US, "%.3f", axisValues[i]));
        }
        axes.setText(axisText.length() == 0 ? "等待摇杆输入" : axisText.toString());
        buttons.setText("本轮按下 " + keyPresses + " 次 · 当前按住 " + pressed.size() + " 个\n" + lastKey);
        startStop.setText(recording ? "停止测试" : stats.size() > 0 ? "重新测试" : "开始测试");
        startStop.setEnabled(!exporting && (recording || selectedId >= 0));
        selectDevice.setEnabled(!recording && !exporting);
        durationButton.setEnabled(!recording && !exporting);
        durationButton.setText(durationSeconds == 0 ? "时长：手动" : "时长：" + durationSeconds + " 秒");
        clearButton.setEnabled(!exporting);
        exportButton.setEnabled(!recording && !exporting && stats.size() > 0);
    }

    private static String number(double value, String unit) {
        return Double.isNaN(value) ? "—" : String.format(Locale.US, "%.2f%s", value, unit);
    }

    private void chooseExport() {
        if (recording || exporting || stats.size() == 0) return;
        String metadata = "# measurement,android_joystick_events_not_bluetooth_packets\r\n"
                + "# payload,unavailable_in_android_input_events_use_ble_or_usb_mode\r\n"
                + "# device," + csvText(sessionDevice) + "\r\n"
                + "# identity," + csvText(sessionIdentity) + "\r\n"
                + "# started," + csvText(sessionStarted) + "\r\n"
                + "# android_sdk," + Build.VERSION.SDK_INT + "\r\n"
                + "# phone," + csvText(Build.MANUFACTURER + " " + Build.MODEL) + "\r\n"
                + "# timestamp_unit,ns\r\n# timestamp_resolution," + (Build.VERSION.SDK_INT >= 34 ? "ns_unit_not_accuracy_guarantee" : "ms") + "\r\n"
                + "# session_duration_ms," + (endMs - startMs) + "\r\n"
                + "# average_hz," + number(stats.averageHz(), "") + "\r\n"
                + "# callback_count," + callbacks + "\r\n"
                + "# historical_samples," + stats.historyCount() + "\r\n"
                + "# equal_timestamps," + stats.equalTimes() + "\r\n"
                + "# rejected_backwards," + stats.backwards() + "\r\n"
                + "# button_presses_excluded_from_rate," + keyPresses + "\r\n"
                + "# stop_reason," + csvText(state) + "\r\n";
        pendingExport = new ExportData(stats.snapshot(), metadata);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "gamepad-rate-"
                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".csv");
        exporting = true;
        render();
        try { startActivityForResult(intent, SAVE_CSV); }
        catch (ActivityNotFoundException exception) {
            exporting = false;
            pendingExport = null;
            toast("没有可用的文件保存应用");
            render();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SAVE_CSV) return;
        ExportData export = pendingExport;
        pendingExport = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || export == null) {
            exporting = false;
            render();
            return;
        }
        Uri uri = data.getData();
        new Thread(() -> {
            String outcome;
            try (OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
                if (stream == null) throw new IOException("无法打开文件");
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                    writer.write('\uFEFF');
                    writer.write(export.metadata);
                    writer.write("sample,event_uptime_ns,relative_ms,interval_ms,historical,callback_uptime_ms\r\n");
                    RateStatistics.Snapshot samples = export.samples;
                    for (int i = 0; i < samples.times.length; i++) {
                        writer.write(Integer.toString(i + 1));
                        writer.write("," + samples.times[i]);
                        writer.write("," + milliseconds(samples.times[i] - samples.times[0]));
                        writer.write("," + (i == 0 ? "" : milliseconds(samples.times[i] - samples.times[i - 1])));
                        writer.write("," + (samples.historical[i] ? "1" : "0") + "," + samples.deliveriesMs[i] + "\r\n");
                    }
                }
                outcome = "CSV 已保存，共 " + export.samples.times.length + " 个采样";
            } catch (IOException | SecurityException exception) {
                outcome = "保存失败：" + exception.getMessage();
            }
            String result = outcome;
            handler.post(() -> {
                if (isDestroyed()) return;
                exporting = false;
                toast(result);
                render();
            });
        }, "csv-export").start();
    }

    private static String milliseconds(long nanos) {
        return String.format(Locale.US, "%.6f", nanos / 1_000_000.0);
    }

    private static String csvText(String text) {
        String value = text.replace('\r', ' ').replace('\n', ' ');
        // Treat device-supplied names as literal text when a spreadsheet opens the CSV.
        if (!value.isEmpty() && "=+-@\t".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    @Override public void onInputDeviceAdded(int deviceId) { refreshDevices(); }
    @Override public void onInputDeviceRemoved(int deviceId) {
        if (deviceId == selectedId && recording) finish("手柄断开，测试已停止", SystemClock.uptimeMillis());
        refreshDevices();
    }
    @Override public void onInputDeviceChanged(int deviceId) {
        if (deviceId == selectedId) {
            if (recording) finish("设备配置变化，测试已停止", SystemClock.uptimeMillis());
            clearInputPreview();
        }
        refreshDevices();
    }

    private static final class ExportData {
        final RateStatistics.Snapshot samples;
        final String metadata;
        ExportData(RateStatistics.Snapshot samples, String metadata) {
            this.samples = samples;
            this.metadata = metadata;
        }
    }
}
