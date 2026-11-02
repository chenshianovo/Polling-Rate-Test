package com.gamepad.rate;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** A visible Activity owns the system folder picker invoked from the overlay. */
public final class ExportActivity extends Activity {
    private PacketRateSession.ExportData snapshot;
    private boolean usb;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        usb = getIntent().getBooleanExtra("usb", false);
        PacketRuntime runtime = PacketRuntime.get(this, usb);
        runtime.pauseRecording();
        snapshot = runtime.session.export();
        if (snapshot == null) { finish(); return; }
        try { startActivityForResult(ExportStore.picker(this), 1); }
        catch (RuntimeException error) { Toast.makeText(this, "无法打开目录选择器", Toast.LENGTH_LONG).show(); finish(); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1) return;
        if (result == RESULT_OK && data != null) {
            try { ExportStore.remember(this, data); ExportStore.packetSnapshot(this, snapshot, usb); }
            catch (RuntimeException error) { Toast.makeText(this, "目录授权失败：" + error.getMessage(), Toast.LENGTH_LONG).show(); }
        }
        finish();
    }
}
