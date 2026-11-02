package com.gamepad.rate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.TextView;

final class Appearance {
    private Appearance() {}
    static void selectTheme(Activity activity) {
        int choice = activity.getSharedPreferences("appearance", Context.MODE_PRIVATE).getInt("theme", 0);
        boolean dark = choice == 2 || (choice == 0 && (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        activity.setTheme(dark ? R.style.AppTheme : R.style.LightAppTheme);
    }
    static int color(Context context, int attr) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }
    static void bind(Activity activity) {
        View button = activity.findViewById(R.id.theme_button);
        if (button != null) button.setOnClickListener(view -> {
            int current = activity.getSharedPreferences("appearance", Context.MODE_PRIVATE).getInt("theme", 0);
            new AlertDialog.Builder(activity).setTitle("背景与主题")
                    .setSingleChoiceItems(new String[]{"跟随系统", "白天模式", "黑夜模式"}, current, (dialog, which) -> {
                        activity.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putInt("theme", which).apply();
                        dialog.dismiss();
                        apply(activity);
                    }).setNegativeButton("取消", null).show();
        });
        apply(activity);
    }
    static void apply(Activity activity) {
        selectTheme(activity);
        View root = activity.findViewById(android.R.id.content);
        int background = color(activity, R.attr.pageBackground);
        root.setBackgroundColor(background);
        activity.getWindow().setStatusBarColor(background);
        activity.getWindow().setNavigationBarColor(background);
        boolean light = Color.red(background) > 128;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(light ?
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            View decor = activity.getWindow().getDecorView();
            int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 27) mask |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility((decor.getSystemUiVisibility() & ~mask) | (light ? mask : 0));
        }
        recolor(root);
    }
    private static void recolor(View view) {
        Context context = view.getContext();
        String tag = view.getTag() instanceof String ? (String) view.getTag() : "";
        if ("page".equals(tag)) view.setBackgroundColor(color(context, R.attr.pageBackground));
        if (view instanceof CollapsibleCard) {
            GradientDrawable card = new GradientDrawable();
            card.setColor(color(context, R.attr.cardBackground));
            card.setCornerRadius(18 * context.getResources().getDisplayMetrics().density);
            card.setStroke(Math.max(1, (int) context.getResources().getDisplayMetrics().density), color(context, R.attr.lineColor));
            view.setBackground(card);
        }
        if (view instanceof TextView) {
            int attr = "accent".equals(tag) ? R.attr.accentColor : "warning".equals(tag) ? R.attr.warningColor
                    : "primary".equals(tag) || view instanceof Button ? R.attr.primaryText : R.attr.secondaryText;
            TextView text = (TextView) view;
            int normal = color(context, attr);
            text.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                    new int[]{(normal & 0x00ffffff) | 0x66000000, normal}));
            if (view instanceof Button) {
                Button button = (Button) view;
                float density = context.getResources().getDisplayMetrics().density;
                boolean action = "action".equals(tag) || "nav_selected".equals(tag), header = "card_header".equals(tag);
                int fill = action ? color(context, R.attr.accentColor) : header || "nav".equals(tag)
                        ? Color.TRANSPARENT : color(context, R.attr.buttonBackground);
                if (action) normal = Color.green(fill) > 160 ? 0xFF102B26 : Color.WHITE;
                button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                        new int[]{(normal & 0x00ffffff) | 0x66000000, normal}));
                GradientDrawable shape = new GradientDrawable();
                shape.setColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                        new int[]{header ? Color.TRANSPARENT : color(context, R.attr.buttonBackground), fill}));
                shape.setCornerRadius(12 * density);
                button.setBackgroundTintList(null);
                button.setBackground(new android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf((normal & 0x00ffffff) | 0x22000000), shape, null));
                button.setStateListAnimator(null);
                button.setAllCaps(false); button.setTextSize(14);
                button.setMinHeight(Math.round(48 * density)); button.setMinimumHeight(Math.round(48 * density));
                button.setMinWidth(0); button.setMinimumWidth(0);
                button.setPadding(Math.round(12 * density), Math.round(8 * density), Math.round(12 * density), Math.round(8 * density));
                if (!header && !tag.startsWith("nav") && button.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) button.getLayoutParams();
                    margins.topMargin = Math.round(4 * density); margins.bottomMargin = Math.round(4 * density);
                    margins.leftMargin = Math.round(3 * density); margins.rightMargin = Math.round(3 * density);
                }
            }
        }
        view.invalidate();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) recolor(group.getChildAt(i));
        }
    }
}
