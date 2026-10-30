package com.gamepad.rate;

import org.junit.Test;
import static org.junit.Assert.*;

/** Regression coverage for event timing, batching, statistics, and bounded storage. */
public class RateStatisticsTest {
    @Test public void regular125HzUsesIntervalsNotSampleCount() {
        RateStatistics stats = new RateStatistics();
        for (int i = 0; i <= 125; i++) stats.add(1_000_000_000L + i * 8_000_000L, 0, false);
        assertEquals(126, stats.size());
        assertEquals(125.0, stats.averageHz(), 0.000001);
        assertEquals(8.0, stats.meanMs(), 0.000001);
        assertEquals(125, stats.recentCount(2_000_000_000L));
        assertEquals(0, stats.recentCount(3_000_000_000L));
    }

    @Test public void batchedHistoryUsesEventTimeRatherThanCallbackTime() {
        RateStatistics stats = new RateStatistics();
        stats.add(1_000_000_000L, 1100, true);
        stats.add(1_008_000_000L, 1100, true);
        stats.add(1_016_000_000L, 1100, false);
        assertEquals(125.0, stats.averageHz(), 0.000001);
        assertEquals(2, stats.historyCount());
        assertArrayEquals(new double[]{8.0, 8.0}, stats.recentIntervals(10), 0.000001);
    }

    @Test public void equalTimestampsArePreservedAndNeverProduceInfinity() {
        RateStatistics stats = new RateStatistics();
        stats.add(1_000_000L, 1, false);
        stats.add(1_000_000L, 1, false);
        assertTrue(Double.isNaN(stats.averageHz()));
        stats.add(2_000_000L, 2, false);
        assertEquals(3, stats.size());
        assertEquals(1, stats.equalTimes());
        assertEquals(2000.0, stats.averageHz(), 0.000001);
        assertEquals(0.0, stats.minMs(), 0.000001);
    }

    @Test public void reversedTimeIsReportedAndCannotCorruptStatistics() {
        RateStatistics stats = new RateStatistics();
        stats.add(20_000_000L, 20, false);
        assertFalse(stats.add(10_000_000L, 30, true));
        stats.add(28_000_000L, 30, false);
        assertEquals(1, stats.backwards());
        assertEquals(0, stats.historyCount());
        assertEquals(125.0, stats.averageHz(), 0.000001);
    }

    @Test public void idleGapIsIncludedRatherThanQuietlyDiscarded() {
        RateStatistics stats = new RateStatistics();
        stats.add(0, 0, false);
        stats.add(8_000_000L, 8, false);
        stats.add(1_008_000_000L, 1008, false);
        assertEquals(1000.0, stats.maxMs(), 0.000001);
        assertEquals(504.0, stats.meanMs(), 0.000001);
        assertEquals(2 / 1.008, stats.averageHz(), 0.000001);
    }

    @Test public void nearestRankP95AndPopulationDeviationAreDefined() {
        RateStatistics stats = new RateStatistics();
        stats.add(0, 0, false);
        stats.add(1_000_000L, 1, false);
        stats.add(4_000_000L, 4, false);
        RateStatistics.Window window = stats.window();
        assertEquals(2, window.count);
        assertEquals(3.0, window.p95Ms, 0.000001);
        assertEquals(1.0, window.jitterMs, 0.000001);
    }

    @Test public void boundedWindowDoesNotChangeWholeSessionExtrema() {
        RateStatistics stats = new RateStatistics();
        stats.add(0, 0, false);
        stats.add(1_000_000_000L, 1000, false);
        for (int i = 1; i <= 2048; i++) stats.add(1_000_000_000L + i * 8_000_000L, 0, false);
        assertEquals(2048, stats.window().count);
        assertEquals(8.0, stats.window().p95Ms, 0.000001);
        assertEquals(1000.0, stats.maxMs(), 0.000001);
    }

    @Test public void exportSnapshotSurvivesReset() {
        RateStatistics stats = new RateStatistics();
        stats.add(10, 20, true);
        RateStatistics.Snapshot snapshot = stats.snapshot();
        stats.clear();
        stats.add(30, 40, false);
        assertEquals(10, snapshot.times[0]);
        assertEquals(20, snapshot.deliveriesMs[0]);
        assertTrue(snapshot.historical[0]);
        assertEquals(0, stats.historyCount());
        assertTrue(Double.isNaN(stats.minMs()));
    }

    @Test public void fullBufferDoesNotOverwriteEarlierEvidence() {
        RateStatistics stats = new RateStatistics();
        for (int i = 0; i < RateStatistics.CAPACITY; i++) assertTrue(stats.add(i, 0, false));
        assertTrue(stats.full());
        assertFalse(stats.add(RateStatistics.CAPACITY, 0, false));
        assertEquals(RateStatistics.CAPACITY, stats.size());
        assertEquals(RateStatistics.CAPACITY - 1, stats.lastTime());
    }
}
