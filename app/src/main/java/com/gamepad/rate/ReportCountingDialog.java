package com.gamepad.rate;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** One optional rule for the current channel. Applying it explicitly starts a fresh session. */
final class ReportCountingDialog {
    private ReportCountingDialog() {}

    static void show(Activity activity, PacketRuntime runtime, Runnable refresh) {
        if (!runtime.ready() || runtime.session.isRecording()) return;
        long generation = runtime.session.generation();
        ReportFormat current = runtime.session.reportFormat();
        LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        fields.setPadding(padding, 0, padding, 0);
        CheckBox enabled = new CheckBox(activity);
        enabled.setText("按固定长度统计摇杆报告");
        enabled.setChecked(current.enabled());
        fields.addView(enabled);
        TextView lengthLabel = new TextView(activity);
        lengthLabel.setText("每份报告长度（字节）");
        fields.addView(lengthLabel);
        EditText length = new EditText(activity);
        length.setInputType(InputType.TYPE_CLASS_NUMBER);
        length.setSingleLine(true);
        length.setText(String.format(java.util.Locale.ROOT, "%d", current.enabled() ? current.bytesPerReport : 5));
        fields.addView(length);
        TextView prefixLabel = new TextView(activity);
        prefixLabel.setText("每份报告头（十六进制，可留空）");
        fields.addView(prefixLabel);
        EditText prefix = new EditText(activity);
        prefix.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        prefix.setSingleLine(true);
        prefix.setText(current.enabled() ? current.prefixHex() : "14 01");
        fields.addView(prefix);
        length.setEnabled(enabled.isChecked()); prefix.setEnabled(enabled.isChecked());
        enabled.setOnCheckedChangeListener((button, checked) -> { length.setEnabled(checked); prefix.setEnabled(checked); });
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("摇杆报告计数（合包/拆包）")
                .setMessage("仅当前通道生效，切换后关闭。应用会清空本轮，请先导出。无报告头时须保证首字节对齐报告起点。")
                .setView(fields).setNegativeButton("取消", null).setPositiveButton("应用并清空", null).create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            ReportFormat format;
            try {
                format = enabled.isChecked() ? ReportFormat.fixed(Integer.parseInt(length.getText().toString().trim()),
                        prefix.getText().toString()) : ReportFormat.OFF;
            } catch (IllegalArgumentException error) {
                String message = error instanceof NumberFormatException ? "请输入有效的报告长度" : error.getMessage();
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); return;
            }
            if (runtime.session.generation() != generation || !runtime.ready() || runtime.session.isRecording()) {
                Toast.makeText(activity, "通道或记录状态已变化，请暂停后重新设置", Toast.LENGTH_LONG).show();
                dialog.dismiss(); return;
            }
            runtime.cancelAutoStart();
            // Cancel any queued BLE start command before clearing a paused/stopped measurement.
            if (runtime.usbMode) runtime.usbClient.stop("修改报告规则");
            else runtime.bleClient.stop("修改报告规则");
            if (!runtime.session.configureReports(format, generation)) {
                Toast.makeText(activity, "状态已变化，规则未应用", Toast.LENGTH_LONG).show();
            }
            dialog.dismiss(); refresh.run();
        }));
        dialog.show();
    }

    static void render(Activity activity, PacketRateSession.ViewData data, int buttonId, int summaryId, boolean ready) {
        android.widget.Button button = activity.findViewById(buttonId);
        button.setEnabled(ready && !data.recording);
        button.setText(data.reportFormat.enabled() ? activity.getString(R.string.report_counting_on, data.reportFormat.bytesPerReport)
                : activity.getString(R.string.report_counting_off));
        TextView summary = activity.findViewById(summaryId);
        summary.setVisibility(data.reportFormat.enabled() ? View.VISIBLE : View.GONE);
        if (data.reportFormat.enabled()) summary.setText(activity.getString(R.string.report_summary, data.reports,
                data.reportHz < 0 ? "—" : Integer.toString(data.reportHz), data.mergedPackets, data.splitReports,
                data.pendingReportBytes, data.skippedReportBytes, data.discardedFragmentBytes));
    }
}
