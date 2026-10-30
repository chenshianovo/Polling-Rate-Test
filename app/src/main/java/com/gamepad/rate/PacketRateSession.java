package com.gamepad.rate;

import java.util.Arrays;

/** One BLE characteristic or USB endpoint; arrival times use elapsedRealtimeNanos. */
final class PacketRateSession {
    static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private final RateStatistics stats = new RateStatistics();
    private final int[] lengths = new int[RateStatistics.CAPACITY];
    private final byte[][] payloads = new byte[RateStatistics.CAPACITY][];
    private final int payloadLimit;
    private boolean recording;
    private long startNs = -1, endNs, deadlineNs, bytes;
    private String device = "", channel = "", started = "";
    private String reason = "选择设备和数据通道后开始测试";
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
        startNs = -1;
        endNs = deadlineNs = bytes = 0;
        device = channel = started = "";
        reason = "已清空，点击开始记录新一轮";
    }

    synchronized void channelChanged() {
        clear();
        preview = new byte[0];
        previewLength = 0;
        previewTime = -1;
    }

    synchronized void start(long nowNs, int seconds, String device, String channel, String started) {
        clear();
        this.device = device;
        this.channel = channel;
        this.started = started;
        startNs = nowNs;
        deadlineNs = seconds == 0 ? 0 : nowNs + seconds * RateStatistics.SECOND_NS;
        recording = true;
        reason = "正在记录选定通道的数据";
    }

    synchronized void accept(long timestampNs, byte[] value) {
        previewTime = timestampNs;
        previewLength = value.length;
        preview = Arrays.copyOf(value, Math.min(64, value.length));
        if (!recording || timestampNs < startNs) return;
        if (deadlineNs > 0 && timestampNs >= deadlineNs) {
            stop("定时测试完成", deadlineNs);
            return;
        }
        int index = stats.size();
        // Reject backwards samples before the memory check, preserving exact row alignment.
        if (index > 0 && timestampNs < stats.lastTime()) {
            stats.add(timestampNs, timestampNs / 1_000_000L, false);
            return;
        }
        if (value.length > payloadLimit - bytes) {
            stop("完整 payload 存储已满，请导出后重测；本条未纳入统计", timestampNs);
            return;
        }
        if (stats.add(timestampNs, timestampNs / 1_000_000L, false)) {
            lengths[index] = value.length;
            payloads[index] = value.clone();
            bytes += value.length;
        }
        if (stats.full()) stop("达到 250000 次接收上限，请导出后重测", timestampNs);
        else if (bytes == payloadLimit) stop("达到完整 payload 存储上限，请导出后重测", timestampNs);
    }

    synchronized void stop(String reason, long nowNs) {
        if (!recording) return;
        recording = false;
        endNs = Math.max(startNs, deadlineNs > 0 ? Math.min(deadlineNs, nowNs) : nowNs);
        this.reason = reason;
    }

    synchronized boolean isRecording() { return recording; }

    synchronized ViewData view(long nowNs) {
        if (recording && deadlineNs > 0 && nowNs >= deadlineNs) stop("定时测试完成", deadlineNs);
        long end = recording ? nowNs : endNs;
        long elapsed = startNs < 0 ? 0 : Math.max(0, end - startNs);
        int hz = startNs < 0 || elapsed < RateStatistics.SECOND_NS ? -1 : stats.recentCount(end);
        return new ViewData(recording, stats.size(), hz, elapsed, bytes, reason, stats.averageHz(),
                stats.meanMs(), stats.minMs(), stats.maxMs(), stats.window(), stats.recentIntervals(160),
                stats.equalTimes(), stats.backwards(), preview.clone(), previewLength, previewTime);
    }

    synchronized ExportData export() {
        if (recording || stats.size() == 0) return null;
        byte[][] copy = new byte[stats.size()][];
        for (int i = 0; i < copy.length; i++) copy[i] = payloads[i].clone();
        return new ExportData(stats.snapshot(), Arrays.copyOf(lengths, stats.size()), copy, device, channel,
                started, reason, startNs, endNs, bytes, stats.averageHz(), stats.backwards());
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
        ViewData(boolean recording, int count, int hz, long elapsedNs, long bytes, String reason,
                 double averageHz, double meanMs, double minMs, double maxMs, RateStatistics.Window window,
                 double[] intervals, long equalTimes, long backwards, byte[] preview, int previewLength, long previewTime) {
            this.recording = recording; this.count = count; this.hz = hz; this.elapsedNs = elapsedNs;
            this.bytes = bytes; this.reason = reason; this.averageHz = averageHz; this.meanMs = meanMs;
            this.minMs = minMs; this.maxMs = maxMs; this.window = window; this.intervals = intervals;
            this.equalTimes = equalTimes; this.backwards = backwards; this.preview = preview;
            this.previewLength = previewLength; this.previewTime = previewTime;
        }
    }

    static final class ExportData {
        final RateStatistics.Snapshot samples;
        final int[] lengths;
        final byte[][] payloads;
        final String device, channel, started, reason;
        final long startNs, endNs, bytes, backwards;
        final double averageHz;
        ExportData(RateStatistics.Snapshot samples, int[] lengths, byte[][] payloads, String device, String channel,
                   String started, String reason, long startNs, long endNs, long bytes, double averageHz, long backwards) {
            this.samples = samples; this.lengths = lengths; this.payloads = payloads; this.device = device; this.channel = channel;
            this.started = started; this.reason = reason; this.startNs = startNs; this.endNs = endNs;
            this.bytes = bytes; this.averageHz = averageHz; this.backwards = backwards;
        }
    }
}
