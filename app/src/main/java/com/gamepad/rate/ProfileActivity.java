package com.gamepad.rate;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

public final class ProfileActivity extends ThemedActivity {
    private UpdateManager updates;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_profile);
        Appearance.bind(this);
        updates = new UpdateManager(this);
        findViewById(R.id.export_directory).setOnClickListener(view -> {
            try { startActivityForResult(ExportStore.picker(this), 301); }
            catch (RuntimeException error) { Toast.makeText(this, "无法打开目录选择器", Toast.LENGTH_LONG).show(); }
        });
        findViewById(R.id.check_update).setOnClickListener(view -> updates.check());
        findViewById(R.id.open_standard).setOnClickListener(view -> startActivity(new Intent(this, MainActivity.class)));
        try { ((TextView) findViewById(R.id.app_version)).setText(getString(R.string.app_version_label,
                getPackageManager().getPackageInfo(getPackageName(), 0).versionName)); }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
    }
    @Override protected void onResume() {
        super.onResume();
        String directory = ExportStore.directory(this);
        ((TextView) findViewById(R.id.directory_value)).setText(directory.isEmpty() ? "未设置" : android.net.Uri.decode(directory));
        if (updates != null) updates.onResume();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 301 && result == RESULT_OK && data != null) {
            try { ExportStore.remember(this, data); }
            catch (RuntimeException error) { Toast.makeText(this, "目录授权失败：" + error.getMessage(), Toast.LENGTH_LONG).show(); }
        }
    }
    @Override protected void onDestroy() { if (updates != null) updates.destroy(); super.onDestroy(); }
}
