package com.gamepad.rate;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;

/** Apply palette in place: changing theme never recreates or clears a measurement session. */
public abstract class ThemedActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        Appearance.selectTheme(this);
        super.onCreate(state);
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
