package com.gamepad.rate;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;

/** Apply palette in place: changing theme never recreates or clears a measurement session. */
public abstract class ThemedActivity extends Activity {
    private android.widget.LinearLayout navigation;
    private Runnable afterOverlayPermission;
    private static final int OVERLAY_PERMISSION = 201;

    @Override public void setContentView(int layout) {
        android.widget.LinearLayout shell = new android.widget.LinearLayout(this);
        shell.setOrientation(android.widget.LinearLayout.VERTICAL);
        shell.setTag("page");
        android.view.View content = getLayoutInflater().inflate(layout, shell, false);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, 0);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), 0);
            return insets;
        });
        shell.addView(content, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
        navigation = new android.widget.LinearLayout(this);
        navigation.setPadding(dp(12), dp(6), dp(12), dp(6));
        String[] labels = {"BLE", "USB", "我的"};
        Class<?>[] targets = {BleActivity.class, UsbActivity.class, ProfileActivity.class};
        int selected = this instanceof BleActivity ? 0 : this instanceof UsbActivity ? 1 : 2;
        for (int i = 0; i < labels.length; i++) {
            android.widget.Button button = new android.widget.Button(this);
            button.setText(labels[i]);
            button.setTag(i == selected ? "nav_selected" : "nav");
            button.setSelected(i == selected);
            button.setContentDescription(labels[i] + (i == selected ? "，当前页面" : ""));
            final Class<?> target = targets[i];
            button.setOnClickListener(view -> {
                if (getClass() == target) return;
                startActivity(new android.content.Intent(this, target)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
            });
            android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(0, dp(50), 1);
            params.setMargins(dp(3), 0, dp(3), 0);
            navigation.addView(button, params);
        }
        shell.addView(navigation, new android.widget.LinearLayout.LayoutParams(-1, -2));
        navigation.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                navigation.setPadding(dp(12) + bars.left, dp(6), dp(12) + bars.right, dp(6) + bars.bottom);
            } else navigation.setPadding(dp(12) + insets.getSystemWindowInsetLeft(), dp(6),
                    dp(12) + insets.getSystemWindowInsetRight(), dp(6) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        super.setContentView(shell);
        navigation.requestApplyInsets();
        content.requestApplyInsets();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    protected void withOverlayPermission(Runnable action) {
        if (android.provider.Settings.canDrawOverlays(this)) { action.run(); return; }
        afterOverlayPermission = action;
        try { startActivityForResult(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + getPackageName())), OVERLAY_PERMISSION); }
        catch (RuntimeException error) {
            afterOverlayPermission = null;
            android.widget.Toast.makeText(this, "请在系统设置中允许悬浮窗", android.widget.Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OVERLAY_PERMISSION) {
            Runnable action = afterOverlayPermission;
            afterOverlayPermission = null;
            if (action != null && android.provider.Settings.canDrawOverlays(this)) action.run();
            else android.widget.Toast.makeText(this, "未开启悬浮窗权限", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
    @Override protected void onCreate(Bundle state) {
        Appearance.selectTheme(this);
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    @Override protected void onResume() {
        super.onResume();
        Appearance.apply(this);
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        Appearance.apply(this);
    }
}
