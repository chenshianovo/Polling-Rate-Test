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
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Transparent, touchable only within its small bounds; never captures global controller input. */
public final class RateOverlayService extends Service {
    private static final String STOP = "com.gamepad.rate.STOP_OVERLAY", CHANNEL = "rate_overlay";
    private static final int NOTIFICATION = 120;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private PacketRuntime runtime;
    private WindowManager manager;
    private LinearLayout window;
    private TextView text;
    private WindowManager.LayoutParams params;
    private boolean receiverRegistered;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { stopSelf(); }
    };
    private final Runnable update = new Runnable() {
        @Override public void run() {
            if (runtime == null || !Settings.canDrawOverlays(RateOverlayService.this)) { stopSelf(); return; }
            PacketRateSession.ViewData data = runtime.data(); // Also applies timed-test deadline in the background.
            String mode = runtime.usbMode ? "USB" : "BLE";
            text.setText(getString(R.string.overlay_value, mode, data.hz < 0 ? "—" : Integer.toString(data.hz),
                    data.recording ? "采集中" : "已停止", data.count));
            text.setContentDescription("回报率 " + text.getText() + "，拖动可移动窗口");
            ui.postDelayed(this, 200);
        }
    };

    static void toggle(Activity activity, PacketRuntime runtime) {
        if (runtime.overlay) {
            activity.stopService(new Intent(activity, RateOverlayService.class));
            return;
        }
        if (!Settings.canDrawOverlays(activity)) {
            try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()))); }
            catch (android.content.ActivityNotFoundException exception) {
                Toast.makeText(activity, "请在系统设置中允许本应用显示在其他应用上层", Toast.LENGTH_LONG).show();
            }
            Toast.makeText(activity, "允许悬浮权限后返回，重新连接设备并点击开启悬浮窗", Toast.LENGTH_LONG).show();
            return;
        }
        if (!runtime.ready()) {
            Toast.makeText(activity, "请先连接设备与接收通道，再开启悬浮测试", Toast.LENGTH_LONG).show();
            return;
        }
        // Called only by a visible Activity, satisfying Android's foreground-service start restrictions.
        runtime.overlay = true;
        runtime.focus(true);
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !activity.getPreferences(Context.MODE_PRIVATE).getBoolean("asked_notification", false)) {
            activity.getPreferences(Context.MODE_PRIVATE).edit().putBoolean("asked_notification", true).apply();
            activity.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 94);
        }
        try { activity.startForegroundService(new Intent(activity, RateOverlayService.class).putExtra("usb", runtime.usbMode)); }
        catch (RuntimeException exception) {
            runtime.endOverlay();
            Toast.makeText(activity, "无法启动悬浮测试：" + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        manager = (WindowManager) getSystemService(WINDOW_SERVICE);
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
            if (!Settings.canDrawOverlays(this) || !runtime.ready()) throw new IllegalStateException("悬浮权限或设备连接不可用");
            Notification notification = notification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION, notification);
            runtime.overlay = true;
            runtime.focus(true);
            if (window == null) createWindow();
            ui.removeCallbacks(update); ui.post(update);
        } catch (RuntimeException exception) {
            Toast.makeText(this, "悬浮测试启动失败：" + exception.getMessage(), Toast.LENGTH_LONG).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    private Notification notification() {
        Class<?> target = runtime.usbMode ? UsbActivity.class : BleActivity.class;
        PendingIntent open = PendingIntent.getActivity(this, runtime.usbMode ? 2 : 1,
                new Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 3, new Intent(this, RateOverlayService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_gamepad)
                .setContentTitle((runtime.usbMode ? "USB" : "BLE") + " 透明悬浮测试")
                .setContentText("保持接收连接 · 点击返回；停止后可导出 CSV")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "关闭悬浮并停止", stop).build()).build();
    }
    private void createWindow() {
        float density = getResources().getDisplayMetrics().density;
        window = new LinearLayout(this);
        window.setOrientation(LinearLayout.HORIZONTAL);
        window.setGravity(Gravity.CENTER_VERTICAL);
        window.setBackgroundColor(Color.TRANSPARENT);
        text = new DragTextView(this);
        text.setTextColor(Color.WHITE);
        text.setTextSize(18);
        text.setShadowLayer(3 * density, 0, density, Color.BLACK);
        text.setPadding((int) (6 * density), (int) (6 * density), 0, (int) (6 * density));
        window.addView(text, new LinearLayout.LayoutParams((int) (180 * density), LinearLayout.LayoutParams.WRAP_CONTENT));
        Button close = new Button(this);
        close.setText("×"); close.setTextSize(24); close.setTextColor(Color.WHITE);
        close.setShadowLayer(3 * density, 0, density, Color.BLACK);
        close.setPadding(0, 0, 0, 0); close.setMinWidth(0); close.setMinimumWidth(0);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setContentDescription("关闭悬浮窗并停止本轮测试");
        close.setOnClickListener(view -> stopSelf());
        window.addView(close, new LinearLayout.LayoutParams((int) (48 * density), (int) (48 * density)));
        params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = getSharedPreferences("overlay", MODE_PRIVATE).getInt("x", (int) (12 * density));
        params.y = getSharedPreferences("overlay", MODE_PRIVATE).getInt("y", (int) (100 * density));
        clampPosition();
        text.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int x, y;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY(); x = params.x; y = params.y; return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    params.x = x + Math.round(event.getRawX() - downX);
                    params.y = y + Math.round(event.getRawY() - downY);
                    clampPosition();
                    try { manager.updateViewLayout(window, params); } catch (RuntimeException revoked) { stopSelf(); }
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    getSharedPreferences("overlay", MODE_PRIVATE).edit().putInt("x", params.x).putInt("y", params.y).apply();
                    view.performClick(); return true;
                }
                return true;
            }
        });
        manager.addView(window, params);
    }
    private void clampPosition() {
        DisplayMetrics metrics = new DisplayMetrics();
        manager.getDefaultDisplay().getMetrics(metrics);
        int width = window == null || window.getWidth() == 0 ? (int) (228 * metrics.density) : window.getWidth();
        int height = window == null || window.getHeight() == 0 ? (int) (80 * metrics.density) : window.getHeight();
        params.x = Math.max(0, Math.min(params.x, metrics.widthPixels - width));
        params.y = Math.max(0, Math.min(params.y, metrics.heightPixels - height));
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (window != null && params != null) {
            clampPosition();
            try { manager.updateViewLayout(window, params); } catch (RuntimeException revoked) { stopSelf(); }
        }
    }
    @Override public void onTaskRemoved(Intent rootIntent) { stopSelf(); }
    @Override public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (window != null) {
            try { manager.removeView(window); } catch (IllegalArgumentException ignored) { /* Permission already revoked. */ }
            window = null;
        }
        if (receiverRegistered) unregisterReceiver(screenOff);
        if (runtime != null) runtime.endOverlay();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    private static final class DragTextView extends TextView {
        DragTextView(Context context) { super(context); }
        @Override public boolean performClick() { return super.performClick(); }
    }
}
