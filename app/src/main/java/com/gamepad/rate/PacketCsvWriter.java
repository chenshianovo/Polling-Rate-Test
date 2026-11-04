package com.gamepad.rate;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;
import java.util.Map;

/** Stream rows off the receiver thread; never truncate a recorded payload. */
final class PacketCsvWriter {
    private PacketCsvWriter() {}

    static void write(Writer writer, PacketRateSession.ExportData data, String measurement,
                      int sdk, String phone) throws IOException {
        writer.write('\uFEFF');
        metadata(writer, "csv_schema", "2");
        metadata(writer, "field_language", "中文");
        metadata(writer, "measurement", text(CsvLabels.value("measurement", measurement)));
        metadata(writer, "clock", text(CsvLabels.value("clock", "elapsedRealtimeNanos_not_hid_uptime")));
        metadata(writer, "device", text(data.device)); metadata(writer, "channel", text(data.channel));
        metadata(writer, "started", text(data.started)); metadata(writer, "stop_reason", text(data.reason));
        metadata(writer, "android_sdk", Integer.toString(sdk)); metadata(writer, "phone", text(phone));
        metadata(writer, "duration_ms", millis(data.endNs - data.startNs));
        metadata(writer, "paused_ms", millis(data.pausedNs));
        metadata(writer, "active_duration_ms", millis(data.endNs - data.startNs - data.pausedNs));
        metadata(writer, "segments", text(CsvLabels.value("segments", "blank_interval_marks_first_sample_or_resume_boundary")));
        writer.write("# 平均接收率_Hz," + (Double.isNaN(data.averageHz) ? "" : String.format(Locale.US, "%.6f", data.averageHz)) + "\r\n");
        metadata(writer, "total_bytes", Long.toString(data.bytes)); metadata(writer, "rejected_backwards", Long.toString(data.backwards));
        metadata(writer, "payload", text("每包完整接收字节，保持原样；不解码摇杆坐标"));
        boolean countReports = data.reportFormat.enabled();
        writer.write("# 接收包总数," + data.samples.times.length + "\r\n# 报告计数模式," + (countReports ? "固定长度流式拼接" : "关闭") + "\r\n");
        if (countReports) {
            writer.write("# 每报告长度_字节," + data.reportFormat.bytesPerReport + "\r\n# 每报告头_十六进制," + text(data.reportFormat.prefixHex()) + "\r\n");
            writer.write("# 摇杆报告总数," + data.reports + "\r\n# 合包完成次数," + data.mergedPackets
                    + "\r\n# 拆包完成报告数," + data.splitReports + "\r\n# 发生失配的接收次数," + data.unmatchedPackets + "\r\n");
            writer.write("# 当前待拼字节," + data.pendingReportBytes + "\r\n# 失配丢弃总字节," + data.skippedReportBytes
                    + "\r\n# 中断丢弃残片字节," + data.discardedFragmentBytes + "\r\n");
            writer.write("# 报告计数说明,按报告头和固定长度连续解析；拆包补齐才计数；暂停或停止清除未完成残片\r\n");
            writer.write("# 合包计数说明,一次接收完成至少两份报告计一次；包括补齐此前残片；不代表底层物理包数\r\n");
            writer.write("# 拆包计数说明,跨两次或更多接收拼成的完整报告数；碎片自身不计报告\r\n");
            writer.write("# 失配字节说明,本次失配丢弃字节可包含此前待拼前缀；原始数据始终完整保留\r\n");
            writer.write("# 报告计时说明,报告完成于最后片段接收时刻；不推算硬件采样时间或包内报告间隔\r\n");
        }
        boolean ble = !data.connections.isEmpty();
        if (ble) {
            metadata(writer, "ble_info_schema", "1"); metadata(writer, "ble_info_clock", "elapsedRealtimeNanos");
            metadata(writer, "ble_info_unknown", text(CsvLabels.value("ble_info_unknown", "empty_numeric_value_with_explicit_status_or_source")));
            metadata(writer, "ble_info_columns", columns("id", "observed_elapsed_realtime_ns", "key", "value"));
            for (int id = 0; id < data.connections.size(); id++) {
                ConnectionMetadata info = data.connections.get(id);
                for (Map.Entry<String, String> entry : info.values.entrySet()) {
                    writer.write("# " + CsvLabels.parameter("ble_info") + "," + id + "," + info.observedNs + "," + text(CsvLabels.parameter(entry.getKey()))
                            + "," + text(CsvLabels.value(entry.getKey(), entry.getValue())) + "\r\n");
                }
            }
        }
        writer.write("接收包序号," + columns("callback_elapsed_realtime_ns", "relative_ms", "interval_ms", "payload_bytes", "payload_hex")
                + (ble ? "," + CsvLabels.parameter("ble_connection_info_id") : "")
                + (countReports ? ",本次完成报告数,累计报告数,本次拆包完成数,接收后待拼字节,本次失配丢弃字节,报告解析状态" : "") + "\r\n");
        long[] times = data.samples.times;
        for (int i = 0; i < times.length; i++) {
            writer.write((i + 1) + "," + times[i] + "," + millis(times[i] - times[0]) + ","
                    + (data.samples.segmentStarts[i] ? "" : millis(times[i] - times[i - 1])) + "," + data.lengths[i] + ",\"");
            if (data.payloads[i].length != 0) writer.write(PacketRateSession.hex(data.payloads[i]));
            writer.write("\"" + (ble ? "," + data.connectionIds[i] : ""));
            if (countReports) {
                int count = data.cumulativeReports[i] - (i == 0 ? 0 : data.cumulativeReports[i - 1]);
                writer.write("," + count + "," + data.cumulativeReports[i] + "," + data.splitReportsByPacket[i]
                        + "," + data.pendingBytesByPacket[i] + "," + data.skippedBytesByPacket[i]
                        + "," + text(ReportFormat.statusText(data.reportStatuses[i])));
            }
            writer.write("\r\n");
        }
    }

    private static void metadata(Writer writer, String key, String value) throws IOException {
        writer.write("# " + CsvLabels.parameter(key) + "," + value + "\r\n");
    }

    private static String columns(String... keys) {
        StringBuilder labels = new StringBuilder();
        for (String key : keys) {
            if (labels.length() > 0) labels.append(',');
            labels.append(CsvLabels.parameter(key));
        }
        return labels.toString();
    }

    static String text(String value) {
        String clean = value.replace('\r', ' ').replace('\n', ' ');
        String trimmed = clean.trim();
        if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) clean = "'" + clean;
        return "\"" + clean.replace("\"", "\"\"") + "\"";
    }
    private static String millis(long ns) { return String.format(Locale.US, "%.6f", ns / 1_000_000.0); }
}
