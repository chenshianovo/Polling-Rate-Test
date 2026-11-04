package com.gamepad.rate;

/** Counts fixed-length logical reports across deliveries, with bounded framing state. */
final class ReportStreamDecoder {
    private final int length;
    private final byte[] prefix;
    private final int[] fallback;
    private int buffered;
    private long position, reportStart;

    ReportStreamDecoder(ReportFormat format) {
        if (!format.enabled()) throw new IllegalArgumentException("报告计数未开启");
        length = format.bytesPerReport;
        prefix = format.prefixBytes();
        // Prefix fallback keeps resynchronization linear, even for long repeated headers.
        fallback = new int[prefix.length];
        for (int i = 1, matched = 0; i < prefix.length; i++) {
            while (matched > 0 && prefix[i] != prefix[matched]) matched = fallback[matched - 1];
            if (prefix[i] == prefix[matched]) matched++;
            fallback[i] = matched;
        }
    }

    Result accept(byte[] payload) {
        long deliveryStart = position;
        int previousPending = buffered, reports = 0, splitReports = 0;
        for (byte value : payload) {
            if (buffered < prefix.length) {
                while (buffered > 0 && value != prefix[buffered]) buffered = fallback[buffered - 1];
                if (value == prefix[buffered]) buffered++;
                if (buffered == prefix.length) reportStart = position - prefix.length + 1;
            } else {
                if (buffered == 0) reportStart = position; // No header: the user guarantees alignment.
                buffered++;
            }
            position++;
            if (buffered == length) {
                reports++;
                if (reportStart < deliveryStart) splitReports++;
                buffered = 0;
            }
        }
        // Account for old partial headers abandoned during this delivery as well as new noise.
        int skipped = payload.length + previousPending - reports * length - buffered;
        byte status = skipped > 0 ? ReportFormat.RESYNCED : payload.length == 0 ? ReportFormat.EMPTY
                : reports > 0 ? (buffered > 0 ? ReportFormat.MATCHED_PENDING : ReportFormat.MATCHED) : ReportFormat.WAITING;
        return new Result(reports, splitReports, buffered, skipped, status);
    }

    int pendingBytes() { return buffered; }

    int discardPending() {
        int discarded = buffered;
        buffered = 0;
        position = reportStart = 0;
        return discarded;
    }

    static final class Result {
        final int reports, splitReports, pendingBytes, skippedBytes;
        final byte status;
        Result(int reports, int splitReports, int pendingBytes, int skippedBytes, byte status) {
            this.reports = reports; this.splitReports = splitReports; this.pendingBytes = pendingBytes;
            this.skippedBytes = skippedBytes; this.status = status;
        }
    }
}
