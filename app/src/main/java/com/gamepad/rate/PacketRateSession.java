package com.gamepad.rate;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One BLE characteristic or USB endpoint; arrival times use elapsedRealtimeNanos. */
final class PacketRateSession {
    static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    static final int MAX_CONNECTION_SNAPSHOTS = 1024;
    private final List<ConnectionMetadata> connectionHistory = new ArrayList<>();
    private int[] connectionIds;
    private ReportFormat reportFormat = ReportFormat.OFF;
    private int[] cumulativeReports;
    private byte[] reportStatuses;
    private int[] splitReportsByPacket, pendingBytesByPacket, skippedBytesByPacket;
    private ReportStreamDecoder reportDecoder;
    private int reports, mergedPackets, unmatchedPackets, splitReports, skippedReportBytes, discardedFragmentBytes;
    private final RateStatistics stats = new RateStatistics();
    private final int[] lengths = new int[RateStatistics.CAPACITY];
    private final byte[][] payloads = new byte[RateStatistics.CAPACITY][];
    private final int payloadLimit;
    private boolean recording;
    private boolean paused;
    private long pauseNs, pausedNs, segmentNs, generation;
    private long startNs = -1, endNs, deadlineNs, bytes;
    private String device = "", channel = "", started = "";
    private String reason = "待开始";
    private byte[] preview = new byte[0];
    private long previewTime = -1;
    private int previewLength;

    PacketRateSession() { this(MAX_PAYLOAD_BYTES); }
    PacketRateSession(int payloadLimit) {
        if (payloadLimit <= 0) throw new IllegalArgumentException("payloadLimit must be positive");
        this.payloadLimit = payloadLimit;
    }

    synchronized void clear() {
        Arrays.fill(payloads, 0, stats.size(), null);
        stats.clear();
        recording = false;
        paused = false;
        pauseNs = pausedNs = segmentNs = 0;
        startNs = -1;
        endNs = deadlineNs = bytes = 0;
        device = channel = started = "";
        connectionHistory.clear();
        connectionIds = null;
        cumulativeReports = null;
        reportStatuses = null;
        splitReportsByPacket = pendingBytesByPacket = skippedBytesByPacket = null;
        reportDecoder = null;
        reports = mergedPackets = unmatchedPackets = splitReports = skippedReportBytes = discardedFragmentBytes = 0;
        reason = "已清空";
    }

    synchronized void channelChanged() {
        generation++;
        reportFormat = ReportFormat.OFF;
        clear();
        preview = new byte[0];
        previewLength = 0;
        previewTime = -1;
    }

    synchronized ReportFormat reportFormat() { return reportFormat; }

    synchronized boolean configureReports(ReportFormat format, long expectedGeneration) {
        if (recording || generation != expectedGeneration) return false;
        clear();
        reportFormat = format;
        return true;
    }

    synchronized void start(long nowNs, int seconds, String device, String channel, String started) {
        start(nowNs, seconds, device, channel, started, null);
    }

    synchronized void start(long nowNs, int seconds, String device, String channel, String started,
                            ConnectionMetadata connection) {
        clear();
        this.device = device;
        this.channel = channel;
        this.started = started;
        startNs = nowNs;
        segmentNs = nowNs;
        deadlineNs = seconds == 0 ? 0 : nowNs + seconds * RateStatistics.SECOND_NS;
        recording = true;
        reason = "记录中";
        if (reportFormat.enabled()) {
            cumulativeReports = new int[RateStatistics.CAPACITY];
            reportStatuses = new byte[RateStatistics.CAPACITY];
            splitReportsByPacket = new int[RateStatistics.CAPACITY];
            pendingBytesByPacket = new int[RateStatistics.CAPACITY];
            skippedBytesByPacket = new int[RateStatistics.CAPACITY];
            reportDecoder = new ReportStreamDecoder(reportFormat);
        }
        if (connection != null) {
            connectionIds = new int[RateStatistics.CAPACITY];
            connectionHistory.add(connection);
        }
    }

    synchronized void updateConnection(ConnectionMetadata connection) {
        if ((!recording && !paused) || connectionHistory.isEmpty()) return;
        ConnectionMetadata previous = connectionHistory.get(connectionHistory.size() - 1);
        if (previous.values.equals(connection.values) || connection.observedNs < previous.observedNs) return;
        if (connectionHistory.size() >= MAX_CONNECTION_SNAPSHOTS) {
            stop("蓝牙参数历史已满，请导出后重测", connection.observedNs);
            return;
        }
        connectionHistory.add(connection);
    }

    synchronized long generation() { return generation; }
    synchronized boolean isPaused() { return paused; }
    synchronized void pause(long nowNs) {
        if (!recording) return;
        if (deadlineNs > 0 && nowNs >= deadlineNs) { stop("定时测试完成", deadlineNs); return; }
        discardPendingReports();
        recording = false;
        paused = true;
        pauseNs = endNs = nowNs;
        reason = "已暂停";
    }
    synchronized boolean resume(long nowNs) {
        if (!paused || nowNs < pauseNs) return false;
        long gap = nowNs - pauseNs;
        pausedNs += gap;
        if (deadlineNs > 0) deadlineNs += gap;
        stats.beginSegment();
        segmentNs = nowNs;
        recording = true;
        paused = false;
        reason = "记录中";
        return true;
    }

    synchronized void accept(long timestampNs, byte[] value) {
        previewTime = timestampNs;
        previewLength = value.length;
        preview = Arrays.copyOf(value, Math.min(64, value.length));
        if (!recording || timestampNs < segmentNs) return;
        if (deadlineNs > 0 && timestampNs >= deadlineNs) {
            stop("定时测试完成", deadlineNs);
            return;
        }
        int index = stats.size();
        // Reject backwards samples before the memory check, preserving exact row alignment.
        if (index > 0 && timestampNs < stats.lastTime()) {
            stats.add(timestampNs, timestampNs / 1_000_000L, false);
            // This delivery leaves a hole in the recorded stream; never join across it.
            discardPendingReports();
            return;
        }
        if (value.length > payloadLimit - bytes) {
            stop("完整 payload 存储已满，请导出后重测；本条未纳入统计", timestampNs);
            return;
        }
        if (stats.add(timestampNs, timestampNs / 1_000_000L, false)) {
            lengths[index] = value.length;
            payloads[index] = value.clone();
            if (reportFormat.enabled()) {
                ReportStreamDecoder.Result result = reportDecoder.accept(value);
                reportStatuses[index] = result.status;
                reports += result.reports;
                if (result.reports > 1) mergedPackets++;
                if (result.skippedBytes > 0) unmatchedPackets++;
                splitReports += result.splitReports;
                skippedReportBytes += result.skippedBytes;
                splitReportsByPacket[index] = result.splitReports;
                pendingBytesByPacket[index] = result.pendingBytes;
                skippedBytesByPacket[index] = result.skippedBytes;
                cumulativeReports[index] = reports;
            }
            if (connectionIds != null) connectionIds[index] = connectionHistory.size() - 1;
            bytes += value.length;
        }
        if (stats.full()) stop("达到 250000 次接收上限，请导出后重测", timestampNs);
        else if (bytes == payloadLimit) stop("达到完整 payload 存储上限，请导出后重测", timestampNs);
    }

    synchronized void stop(String reason, long nowNs) {
        if (!recording && !paused) return;
        if (paused) nowNs = pauseNs;
        discardPendingReports();
        paused = false;
        recording = false;
        endNs = Math.max(startNs, deadlineNs > 0 ? Math.min(deadlineNs, nowNs) : nowNs);
        this.reason = reason;
    }

    synchronized boolean isRecording() { return recording; }

    private void discardPendingReports() {
        if (reportDecoder != null) discardedFragmentBytes += reportDecoder.discardPending();
    }

    private int pendingReportBytes() { return reportDecoder == null ? 0 : reportDecoder.pendingBytes(); }

    synchronized ViewData view(long nowNs) {
        if (recording && deadlineNs > 0 && nowNs >= deadlineNs) stop("定时测试完成", deadlineNs);
        long end = recording ? nowNs : endNs;
        long elapsed = startNs < 0 ? 0 : Math.max(0, end - startNs - pausedNs);
        int hz = startNs < 0 || end - segmentNs < RateStatistics.SECOND_NS ? -1 : stats.recentCount(end);
        int reportHz = hz < 0 || !reportFormat.enabled() ? -1
                : reportsThrough(end) - reportsThrough(end - RateStatistics.SECOND_NS);
        return new ViewData(recording, stats.size(), hz, elapsed, bytes, reason, stats.averageHz(),
                stats.meanMs(), stats.minMs(), stats.maxMs(), stats.window(), stats.recentIntervals(160),
                stats.equalTimes(), stats.backwards(), preview.clone(), previewLength, previewTime,
                reportFormat, reports, mergedPackets, unmatchedPackets, reportHz,
                splitReports, pendingReportBytes(), skippedReportBytes, discardedFragmentBytes);
    }

    private int reportsThrough(long timeNs) {
        int count = stats.samplesThrough(timeNs);
        return count == 0 ? 0 : cumulativeReports[count - 1];
    }

    synchronized ExportData export() {
        if (recording || stats.size() == 0) return null;
        byte[][] copy = new byte[stats.size()][];
        for (int i = 0; i < copy.length; i++) copy[i] = payloads[i].clone();
        return new ExportData(stats.snapshot(), Arrays.copyOf(lengths, stats.size()), copy, device, channel,
                started, reason, startNs, endNs, bytes, stats.averageHz(), stats.backwards(), pausedNs,
                Collections.unmodifiableList(new ArrayList<>(connectionHistory)),
                connectionIds == null ? new int[0] : Arrays.copyOf(connectionIds, stats.size()),
                reportFormat, cumulativeReports == null ? new int[0] : Arrays.copyOf(cumulativeReports, stats.size()),
                reportStatuses == null ? new byte[0] : Arrays.copyOf(reportStatuses, stats.size()),
                reports, mergedPackets, unmatchedPackets, splitReports, pendingReportBytes(), skippedReportBytes, discardedFragmentBytes,
                splitReportsByPacket == null ? new int[0] : Arrays.copyOf(splitReportsByPacket, stats.size()),
                pendingBytesByPacket == null ? new int[0] : Arrays.copyOf(pendingBytesByPacket, stats.size()),
                skippedBytesByPacket == null ? new int[0] : Arrays.copyOf(skippedBytesByPacket, stats.size()));
    }

    static String hex(byte[] bytes) {
        if (bytes.length == 0) return "（空数据）";
        char[] digits = "0123456789ABCDEF".toCharArray();
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) out.append(' ');
            out.append(digits[(bytes[i] & 255) >>> 4]).append(digits[bytes[i] & 15]);
        }
        return out.toString();
    }

    static final class ViewData {
        final boolean recording;
        final int count, hz, previewLength;
        final long elapsedNs, bytes, equalTimes, backwards, previewTime;
        final String reason;
        final double averageHz, meanMs, minMs, maxMs;
        final RateStatistics.Window window;
        final double[] intervals;
        final byte[] preview;
        final ReportFormat reportFormat;
        final int reports, mergedPackets, unmatchedPackets, reportHz;
        final int splitReports, pendingReportBytes, skippedReportBytes, discardedFragmentBytes;
        ViewData(boolean recording, int count, int hz, long elapsedNs, long bytes, String reason,
                 double averageHz, double meanMs, double minMs, double maxMs, RateStatistics.Window window,
                 double[] intervals, long equalTimes, long backwards, byte[] preview, int previewLength, long previewTime,
                 ReportFormat reportFormat, int reports, int mergedPackets, int unmatchedPackets, int reportHz,
                 int splitReports, int pendingReportBytes, int skippedReportBytes, int discardedFragmentBytes) {
            this.recording = recording; this.count = count; this.hz = hz; this.elapsedNs = elapsedNs;
            this.bytes = bytes; this.reason = reason; this.averageHz = averageHz; this.meanMs = meanMs;
            this.minMs = minMs; this.maxMs = maxMs; this.window = window; this.intervals = intervals;
            this.equalTimes = equalTimes; this.backwards = backwards; this.preview = preview;
            this.previewLength = previewLength; this.previewTime = previewTime;
            this.reportFormat = reportFormat; this.reports = reports; this.mergedPackets = mergedPackets;
            this.unmatchedPackets = unmatchedPackets; this.reportHz = reportHz;
            this.splitReports = splitReports; this.pendingReportBytes = pendingReportBytes;
            this.skippedReportBytes = skippedReportBytes; this.discardedFragmentBytes = discardedFragmentBytes;
        }
    }

    static final class ExportData {
        final RateStatistics.Snapshot samples;
        final int[] lengths;
        final byte[][] payloads;
        final String device, channel, started, reason;
        final long startNs, endNs, bytes, backwards, pausedNs;
        final double averageHz;
        final List<ConnectionMetadata> connections;
        final int[] connectionIds;
        final ReportFormat reportFormat;
        final int[] cumulativeReports;
        final byte[] reportStatuses;
        final int reports, mergedPackets, unmatchedPackets;
        final int splitReports, pendingReportBytes, skippedReportBytes, discardedFragmentBytes;
        final int[] splitReportsByPacket, pendingBytesByPacket, skippedBytesByPacket;
        ExportData(RateStatistics.Snapshot samples, int[] lengths, byte[][] payloads, String device, String channel,
                   String started, String reason, long startNs, long endNs, long bytes, double averageHz, long backwards, long pausedNs,
                   List<ConnectionMetadata> connections, int[] connectionIds, ReportFormat reportFormat,
                   int[] cumulativeReports, byte[] reportStatuses, int reports, int mergedPackets, int unmatchedPackets,
                   int splitReports, int pendingReportBytes, int skippedReportBytes, int discardedFragmentBytes,
                   int[] splitReportsByPacket, int[] pendingBytesByPacket, int[] skippedBytesByPacket) {
            this.samples = samples; this.lengths = lengths; this.payloads = payloads; this.device = device; this.channel = channel;
            this.started = started; this.reason = reason; this.startNs = startNs; this.endNs = endNs;
            this.bytes = bytes; this.averageHz = averageHz; this.backwards = backwards;
            this.pausedNs = pausedNs;
            this.connections = connections; this.connectionIds = connectionIds;
            this.reportFormat = reportFormat; this.cumulativeReports = cumulativeReports; this.reportStatuses = reportStatuses;
            this.reports = reports; this.mergedPackets = mergedPackets; this.unmatchedPackets = unmatchedPackets;
            this.splitReports = splitReports; this.pendingReportBytes = pendingReportBytes;
            this.skippedReportBytes = skippedReportBytes; this.discardedFragmentBytes = discardedFragmentBytes;
            this.splitReportsByPacket = splitReportsByPacket; this.pendingBytesByPacket = pendingBytesByPacket;
            this.skippedBytesByPacket = skippedBytesByPacket;
        }
    }
}
