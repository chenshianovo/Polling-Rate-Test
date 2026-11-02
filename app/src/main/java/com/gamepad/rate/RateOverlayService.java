package com.gamepad.rate;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Touches outside the compact translucent window belong to other apps. */
public final class RateOverlayService extends Service {
    private static final String STOP = "com.gamepad.rate.STOP_OVERLAY", CHANNEL = "rate_overlay";
    private static final int NOTIFICATION = 120;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private PacketRuntime runtime;
    private WindowManager manager;
    private LinearLayout window, controls;
    private DragTextView text;
    private Button start, pause, export;
    private WindowManager.LayoutParams params;
    private boolean receiverRegistered;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { stopSelf(); }
    };
    private final Runnable update = new Runnable() {
        @Override public void run() {
            if (runtime == null || !Settings.canDrawOverlays(RateOverlayService.this)) { stopSelf(); return; }
            PacketRateSession.ViewData data = runtime.data();
            String state = data.recording ? "记录中" : runtime.session.isPaused() ? "已暂停" : "已停止";
            text.setText(getString(R.string.overlay_value, runtime.usbMode ? "USB" : "BLE",
                    data.hz < 0 ? "—" : Integer.toString(data.hz), state, data.count));
            text.setContentDescription(text.getText() + (controls.getVisibility() == View.VISIBLE ? "，点击收起" : "，点击展开"));
            start.setText(runtime.session.isPaused() ? "继续" : "开始");
            enabled(start, runtime.ready() && !data.recording);
            enabled(pause, data.recording); enabled(export, data.count > 0);
            ui.postDelayed(this, 200);
        }
    };
    static void toggle(ThemedActivity activity, PacketRuntime runtime) {
        if (runtime.overlay) { activity.stopService(new Intent(activity, RateOverlayService.class)); return; }
        activity.withOverlayPermission(() -> open(activity, runtime, false));
    }
    static void open(Activity activity, PacketRuntime runtime, boolean start) {
        if (!runtime.ready() || !Settings.canDrawOverlays(activity)) {
            Toast.makeText(activity, "设备或悬浮窗权限未就绪", Toast.LENGTH_SHORT).show(); return;
        }
        // A visible Activity starts the foreground service after channel readiness is published.
        runtime.overlay = true; runtime.focus(true);
        try { activity.startForegroundService(new Intent(activity, RateOverlayService.class)
                .putExtra("usb", runtime.usbMode).putExtra("start", start)); }
        catch (RuntimeException error) {
            runtime.endOverlay(); Toast.makeText(activity, "无法开启悬浮窗：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    @Override public void onCreate() {
        super.onCreate(); manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "手柄悬浮测试", NotificationManager.IMPORTANCE_LOW));
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff, filter);
        receiverRegistered = true;
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        PacketRuntime next = PacketRuntime.get(this, intent.getBooleanExtra("usb", false));
        if (runtime != null && runtime != next) runtime.endOverlay();
        runtime = next;
        try {
            if (!Settings.canDrawOverlays(this) || !runtime.ready()) throw new IllegalStateException("权限或设备连接不可用");
            Notification notification = notification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION, notification);
            runtime.overlay = true; runtime.focus(true);
            if (window == null) createWindow();
            if (intent.getBooleanExtra("start", false)) runtime.startOrResume();
            ui.removeCallbacks(update); ui.post(update);
        } catch (RuntimeException error) {
            Toast.makeText(this, "悬浮窗启动失败：" + error.getMessage(), Toast.LENGTH_LONG).show(); stopSelf();
        }
        return START_NOT_STICKY;
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, runtime.usbMode ? 2 : 1,
                new Intent(this, runtime.usbMode ? UsbActivity.class : BleActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 3, new Intent(this, RateOverlayService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_gamepad)
                .setContentTitle((runtime.usbMode ? "USB" : "BLE") + " 悬浮测试")
                .setContentText("点击返回测试页面").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "关闭", stop).build()).build();
    }
    private void createWindow() {
        window = new LinearLayout(this); window.setOrientation(LinearLayout.VERTICAL); window.setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable background = new GradientDrawable(); background.setColor(0xB3525862);
        background.setCornerRadius(dp(18)); background.setStroke(dp(1), 0x55FFFFFF); window.setBackground(background);
        text = new DragTextView(this); text.setTextColor(Color.WHITE); text.setTextSize(18);
        text.setShadowLayer(dp(2), 0, dp(1), Color.BLACK); text.setPadding(dp(8), dp(4), dp(8), dp(4));
        text.setMinHeight(dp(60)); text.setGravity(Gravity.CENTER_VERTICAL);
        window.addView(text, new LinearLayout.LayoutParams(-1, -2));
        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); controls.setVisibility(View.GONE);
        window.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout first = row(), second = row();
        start = button(first, "开始", () -> runtime.startOrResume()); pause = button(first, "暂停", () -> runtime.pauseRecording());
        export = button(second, "导出", () -> {
            try { ExportStore.packet(this, runtime); }
            catch (RuntimeException error) { Toast.makeText(this, "请在“我的”中设置导出目录", Toast.LENGTH_LONG).show(); }
        });
        button(second, "关闭", this::stopSelf).setContentDescription("关闭悬浮窗并停止记录");
        params = new WindowManager.LayoutParams(dp(220), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = getSharedPreferences("overlay", MODE_PRIVATE).getInt("x", dp(12));
        params.y = getSharedPreferences("overlay", MODE_PRIVATE).getInt("y", dp(100));
        clampPosition();
        text.setOnClickListener(view -> {
            controls.setVisibility(controls.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); window.post(this::positionChanged);
        });
        text.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY; private int x, y; private boolean dragged;
            private final int slop = ViewConfiguration.get(RateOverlayService.this).getScaledTouchSlop();
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX(); downY = event.getRawY(); x = params.x; y = params.y; dragged = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                        if (Math.hypot(dx, dy) > slop) dragged = true;
                        if (dragged) { params.x = x + Math.round(dx); params.y = y + Math.round(dy); positionChanged(); } return true;
                    case MotionEvent.ACTION_UP:
                        if (dragged) getSharedPreferences("overlay", MODE_PRIVATE).edit().putInt("x", params.x).putInt("y", params.y).apply();
                        else view.performClick();
                        return true;
                    default: return true;
                }
            }
        });
        manager.addView(window, params);
        window.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) positionChanged();
        });
    }
    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this); controls.addView(row, new LinearLayout.LayoutParams(-1, -2)); return row;
    }
    private Button button(LinearLayout row, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setTextColor(Color.WHITE); button.setTextSize(14);
        button.setAllCaps(false); button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(0, 0, 0, 0);
        GradientDrawable background = new GradientDrawable(); background.setColor(0x6640454F); background.setCornerRadius(dp(12));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), background, null));
        button.setOnClickListener(view -> { action.run(); ui.removeCallbacks(update); ui.post(update); });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(48), 1); size.setMargins(dp(3), dp(3), dp(3), dp(3));
        row.addView(button, size); return button;
    }
    private void enabled(Button button, boolean value) { button.setEnabled(value); button.setAlpha(value ? 1 : 0.45f); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void clampPosition() {
        DisplayMetrics metrics = new DisplayMetrics(); manager.getDefaultDisplay().getMetrics(metrics);
        params.width = Math.min(dp(220), metrics.widthPixels);
        int height = Math.max(window.getHeight(), dp(76) + (controls.getVisibility() == View.VISIBLE ? dp(108) : 0));
        params.x = Math.max(0, Math.min(params.x, metrics.widthPixels - params.width));
        params.y = Math.max(0, Math.min(params.y, metrics.heightPixels - height));
    }
    private void positionChanged() {
        if (window == null) return;
        clampPosition(); try { manager.updateViewLayout(window, params); } catch (RuntimeException revoked) { stopSelf(); }
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) { super.onConfigurationChanged(configuration); positionChanged(); }
    @Override public void onTaskRemoved(Intent rootIntent) { stopSelf(); }
    @Override public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (window != null) { try { manager.removeView(window); } catch (IllegalArgumentException ignored) { } window = null; }
        if (receiverRegistered) unregisterReceiver(screenOff);
        if (runtime != null) runtime.endOverlay();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    private static final class DragTextView extends TextView {
        DragTextView(Context context) { super(context); }
        @Override public boolean performClick() { return super.performClick(); }
    }
}
