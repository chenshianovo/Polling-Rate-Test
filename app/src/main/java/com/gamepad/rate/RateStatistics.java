package com.gamepad.rate;

import java.util.Arrays;

/** Pure statistics. Each session must use one consistent monotonic nanosecond clock. */
final class RateStatistics {
    static final int CAPACITY = 250_000;
    static final int WINDOW_INTERVALS = 2048;
    static final long SECOND_NS = 1_000_000_000L;

    private final long[] times = new long[CAPACITY];
    private final long[] deliveriesMs = new long[CAPACITY];
    private final boolean[] historical = new boolean[CAPACITY];
    private int size;
    private final boolean[] segmentStarts = new boolean[CAPACITY];
    private boolean nextSegment = true;
    private int intervalCount;
    private long intervalTotal;
    private long backwards;
    private long equalTimes;
    private long historyCount;
    private long minInterval = Long.MAX_VALUE;
    private long maxInterval;

    void clear() {
        size = 0;
        nextSegment = true;
        intervalCount = 0;
        intervalTotal = 0;
        backwards = 0;
        equalTimes = 0;
        historyCount = 0;
        minInterval = Long.MAX_VALUE;
        maxInterval = 0;
    }

    boolean add(long timeNs, long deliveryMs, boolean isHistorical) {
        if (size == CAPACITY) return false;
        if (size > 0) {
            long delta = timeNs - times[size - 1];
            if (delta < 0) {
                backwards++;
                return false;
            }
            // 不同采样可能被舍入到同一毫秒，所以不能直接去重。
            // 频率按总时间跨度计算，不把单个 0 间隔换算成无穷大 Hz。
            if (!nextSegment) {
                if (delta == 0) equalTimes++;
                minInterval = Math.min(minInterval, delta);
                maxInterval = Math.max(maxInterval, delta);
                intervalTotal += delta;
                intervalCount++;
            }
        }
        segmentStarts[size] = nextSegment;
        nextSegment = false;
        times[size] = timeNs;
        deliveriesMs[size] = deliveryMs;
        historical[size] = isHistorical;
        size++;
        if (isHistorical) historyCount++;
        return true;
    }

    int size() { return size; }
    int samplesThrough(long timeNs) { return upperBound(timeNs); }
    void beginSegment() { nextSegment = true; }
    boolean full() { return size == CAPACITY; }
    long backwards() { return backwards; }
    long equalTimes() { return equalTimes; }
    long historyCount() { return historyCount; }
    long lastTime() { return size == 0 ? 0 : times[size - 1]; }

    double averageHz() {
        return intervalTotal > 0 ? intervalCount * (double) SECOND_NS / intervalTotal : Double.NaN;
    }

    double meanMs() {
        return intervalCount > 0 ? intervalTotal / 1_000_000.0 / intervalCount : Double.NaN;
    }

    double minMs() { return intervalCount > 0 ? minInterval / 1_000_000.0 : Double.NaN; }
    double maxMs() { return intervalCount > 0 ? maxInterval / 1_000_000.0 : Double.NaN; }

    /** Count in (now - 1 second, now], including idle time in the fixed window. */
    int recentCount(long nowNs) {
        return upperBound(nowNs) - upperBound(nowNs - SECOND_NS);
    }

    private int upperBound(long value) {
        int low = 0;
        int high = size;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (times[middle] <= value) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    double[] recentIntervals(int limit) {
        int count = Math.min(intervalCount, Math.max(0, limit));
        double[] result = new double[count];
        int write = count - 1;
        for (int index = size - 1; index > 0 && write >= 0; index--) {
            if (!segmentStarts[index]) result[write--] = (times[index] - times[index - 1]) / 1_000_000.0;
        }
        return result;
    }

    Window window() {
        double[] values = recentIntervals(WINDOW_INTERVALS);
        if (values.length == 0) return new Window(Double.NaN, Double.NaN, 0);
        double mean = 0;
        double m2 = 0;
        for (int i = 0; i < values.length; i++) {
            double delta = values[i] - mean;
            mean += delta / (i + 1);
            m2 += delta * (values[i] - mean);
        }
        Arrays.sort(values);
        int index = (int) Math.ceil(values.length * 0.95) - 1;
        return new Window(values[index], Math.sqrt(Math.max(0, m2 / values.length)), values.length);
    }

    Snapshot snapshot() {
        return new Snapshot(Arrays.copyOf(times, size), Arrays.copyOf(deliveriesMs, size),
                Arrays.copyOf(historical, size), Arrays.copyOf(segmentStarts, size));
    }

    static final class Window {
        final double p95Ms;
        final double jitterMs;
        final int count;
        Window(double p95Ms, double jitterMs, int count) {
            this.p95Ms = p95Ms;
            this.jitterMs = jitterMs;
            this.count = count;
        }
    }

    static final class Snapshot {
        final long[] times;
        final long[] deliveriesMs;
        final boolean[] historical;
        final boolean[] segmentStarts;
        Snapshot(long[] times, long[] deliveriesMs, boolean[] historical, boolean[] segmentStarts) {
            this.times = times;
            this.deliveriesMs = deliveriesMs;
            this.historical = historical;
            this.segmentStarts = segmentStarts;
        }
    }
}
