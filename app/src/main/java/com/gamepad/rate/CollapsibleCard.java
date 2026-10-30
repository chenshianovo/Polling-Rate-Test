package com.gamepad.rate;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/** A card header stays visible while its contents collapse; collection runs independently. */
public final class CollapsibleCard extends LinearLayout {
    public CollapsibleCard(Context context, AttributeSet attrs) { super(context, attrs); }
    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        setOrientation(VERTICAL);
        String[] parts = String.valueOf(getTag()).split("\\|", 2);
        String key = parts[0];
        String title = parts.length == 2 ? parts[1] : "面板";
        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(VERTICAL);
        while (getChildCount() > 0) {
            View child = getChildAt(0);
            removeViewAt(0);
            body.addView(child);
        }
        Button header = new Button(getContext());
        header.setAllCaps(false);
        header.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        header.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("panels", Context.MODE_PRIVATE);
        boolean[] collapsed = {prefs.getBoolean(key, false)};
        Runnable update = () -> {
            body.setVisibility(collapsed[0] ? GONE : VISIBLE);
            header.setText(getContext().getString(R.string.panel_title, collapsed[0] ? "▸" : "▾", title));
            header.setContentDescription(title + (collapsed[0] ? "，已折叠，点击展开" : "，已展开，点击折叠"));
        };
        header.setOnClickListener(view -> {
            collapsed[0] = !collapsed[0];
            prefs.edit().putBoolean(key, collapsed[0]).apply();
            update.run();
        });
        update.run();
    }
}
