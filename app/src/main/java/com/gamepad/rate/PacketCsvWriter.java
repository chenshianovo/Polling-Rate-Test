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
        writer.write("# measurement," + measurement + "\r\n# clock,elapsedRealtimeNanos_not_hid_uptime\r\n");
        writer.write("# device," + text(data.device) + "\r\n# channel," + text(data.channel) + "\r\n");
        writer.write("# started," + text(data.started) + "\r\n# stop_reason," + text(data.reason) + "\r\n");
        writer.write("# android_sdk," + sdk + "\r\n# phone," + text(phone) + "\r\n");
        writer.write("# duration_ms," + millis(data.endNs - data.startNs) + "\r\n");
        writer.write("# paused_ms," + millis(data.pausedNs) + "\r\n# active_duration_ms," + millis(data.endNs - data.startNs - data.pausedNs) + "\r\n");
        writer.write("# segments,blank_interval_marks_first_sample_or_resume_boundary\r\n");
        writer.write("# average_hz," + (Double.isNaN(data.averageHz) ? "" : String.format(Locale.US, "%.6f", data.averageHz)) + "\r\n");
        writer.write("# total_bytes," + data.bytes + "\r\n# rejected_backwards," + data.backwards + "\r\n");
        writer.write("# payload,full_received_bytes_hex_no_protocol_decoding\r\n");
        boolean ble = !data.connections.isEmpty();
        if (ble) {
            writer.write("# ble_info_schema,1\r\n# ble_info_clock,elapsedRealtimeNanos\r\n");
            writer.write("# ble_info_unknown,empty_numeric_value_with_explicit_status_or_source\r\n");
            writer.write("# ble_info_columns,id,observed_elapsed_realtime_ns,key,value\r\n");
            for (int id = 0; id < data.connections.size(); id++) {
                ConnectionMetadata info = data.connections.get(id);
                for (Map.Entry<String, String> entry : info.values.entrySet()) {
                    writer.write("# ble_info," + id + "," + info.observedNs + "," + text(entry.getKey())
                            + "," + text(entry.getValue()) + "\r\n");
                }
            }
        }
        writer.write("sample,callback_elapsed_realtime_ns,relative_ms,interval_ms,payload_bytes,payload_hex"
                + (ble ? ",ble_connection_info_id" : "") + "\r\n");
        long[] times = data.samples.times;
        for (int i = 0; i < times.length; i++) {
            writer.write((i + 1) + "," + times[i] + "," + millis(times[i] - times[0]) + ","
                    + (data.samples.segmentStarts[i] ? "" : millis(times[i] - times[i - 1])) + "," + data.lengths[i] + ",\"");
            if (data.payloads[i].length != 0) writer.write(PacketRateSession.hex(data.payloads[i]));
            writer.write("\"" + (ble ? "," + data.connectionIds[i] : "") + "\r\n");
        }
    }

    static String text(String value) {
        String clean = value.replace('\r', ' ').replace('\n', ' ');
        String trimmed = clean.trim();
        if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) clean = "'" + clean;
        return "\"" + clean.replace("\"", "\"\"") + "\"";
    }
    private static String millis(long ns) { return String.format(Locale.US, "%.6f", ns / 1_000_000.0); }
}
