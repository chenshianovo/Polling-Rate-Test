package com.gamepad.rate;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;

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
        writer.write("# average_hz," + (Double.isNaN(data.averageHz) ? "" : String.format(Locale.US, "%.6f", data.averageHz)) + "\r\n");
        writer.write("# total_bytes," + data.bytes + "\r\n# rejected_backwards," + data.backwards + "\r\n");
        writer.write("# payload,full_received_bytes_hex_no_protocol_decoding\r\n");
        writer.write("sample,callback_elapsed_realtime_ns,relative_ms,interval_ms,payload_bytes,payload_hex\r\n");
        long[] times = data.samples.times;
        for (int i = 0; i < times.length; i++) {
            writer.write((i + 1) + "," + times[i] + "," + millis(times[i] - times[0]) + ","
                    + (i == 0 ? "" : millis(times[i] - times[i - 1])) + "," + data.lengths[i] + ",\"");
            if (data.payloads[i].length != 0) writer.write(PacketRateSession.hex(data.payloads[i]));
            writer.write("\"\r\n");
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
