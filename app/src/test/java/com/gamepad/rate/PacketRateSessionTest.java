package com.gamepad.rate;

import org.junit.Test;
import static org.junit.Assert.*;

public class PacketRateSessionTest {
    private static final byte[] REPORT = {1, 2, 3, 4};

    @Test public void pauseRetainsRowsAndResumeExcludesGapFromStatistics() {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 0, "device", "channel", "test");
        session.accept(0, REPORT);
        session.accept(8_000_000, new byte[]{5});
        session.pause(10_000_000);
        session.accept(500_000_000, new byte[]{9});
        PacketRateSession.ExportData frozen = session.export();
        assertEquals(2, frozen.lengths.length);
        assertTrue(session.resume(1_010_000_000));
        session.accept(1_000_000_000, new byte[]{9}); // A callback queued before resume cannot enter the new segment.
        session.accept(1_010_000_000, new byte[]{6});
        session.accept(1_018_000_000, new byte[]{7});
        session.pause(1_020_000_000);
        PacketRateSession.ViewData view = session.view(9_000_000_000L);
        assertEquals(4, view.count);
        assertEquals(20_000_000, view.elapsedNs);
        assertEquals(125.0, view.averageHz, 0.000001);
        assertEquals(8.0, view.maxMs, 0.000001);
        assertArrayEquals(new double[]{8, 8}, view.intervals, 0.000001);
        assertArrayEquals(new boolean[]{true, false, true, false}, session.export().samples.segmentStarts);
        assertEquals(1_000_000_000, session.export().pausedNs);
        assertArrayEquals(REPORT, frozen.payloads[0]);
        assertArrayEquals(new byte[]{7}, session.export().payloads[3]);
    }

    @Test public void pausedTimeDoesNotConsumeTimedRecordingBudget() {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 10, "device", "channel", "test");
        session.accept(1, REPORT);
        session.pause(2_000_000_000L);
        assertTrue(session.isPaused());
        assertEquals(2_000_000_000L, session.view(20_000_000_000L).elapsedNs);
        assertTrue(session.resume(22_000_000_000L));
        session.accept(29_999_999_999L, REPORT);
        session.accept(30_000_000_000L, REPORT);
        assertFalse(session.isRecording());
        assertFalse(session.isPaused());
        assertEquals(2, session.export().lengths.length);
        assertEquals(10_000_000_000L, session.view(40_000_000_000L).elapsedNs);
    }

    @Test public void stoppingPausedSessionKeepsEndAtPauseAndCannotResume() {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 0, "device", "channel", "test");
        session.accept(10, REPORT);
        session.pause(20);
        session.stop("closed", 100);
        assertEquals(20, session.export().endNs);
        assertFalse(session.resume(200));
        session.channelChanged();
        assertFalse(session.isPaused());
        assertEquals(1, session.generation());
        assertNull(session.export());
    }

    @Test public void pauseAtDeadlineCompletesInsteadOfCreatingResumableSession() {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 1, "device", "channel", "test");
        session.accept(10, REPORT);
        session.pause(1_000_000_000);
        assertFalse(session.isPaused());
        assertFalse(session.resume(2_000_000_000));
        assertEquals(1_000_000_000, session.export().endNs);
    }

    @Test public void previewDoesNotStartRecording() {
        PacketRateSession session = new PacketRateSession();
        session.accept(1_000_000L, REPORT);
        assertEquals(0, session.view(2_000_000L).count);
        assertEquals(4, session.view(2_000_000L).previewLength);
        assertNull(session.export());
    }

    @Test public void counts125HzCallbacksRegardlessOfIdenticalPayloads() {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 0, "custom device", "selected UUID", "test");
        for (int i = 0; i <= 125; i++) session.accept(i * 8_000_000L, REPORT);
        PacketRateSession.ViewData view = session.view(1_000_000_000L);
        assertEquals(126, view.count);
        assertEquals(125, view.hz);
        assertEquals(125.0, view.averageHz, 0.000001);
        assertEquals(126 * 4, view.bytes);
    }

    @Test public void deadlineUsesSameClockAndExcludesBoundaryCallback() {
        PacketRateSession session = new PacketRateSession();
        long start = 900_000_000_000L;
        session.start(start, 10, "device", "channel", "test");
        session.accept(start - 1, REPORT);
        session.accept(start, REPORT);
        session.accept(start + 9_999_999_999L, REPORT);
        session.accept(start + 10_000_000_000L, REPORT);
        PacketRateSession.ViewData view = session.view(start + 12_000_000_000L);
        assertFalse(view.recording);
        assertEquals(2, view.count);
        assertEquals(10_000_000_000L, view.elapsedNs);
        assertEquals(1, view.hz);
    }

    @Test public void resultIsFrozenWhilePreviewCanContinue() {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 0, "device", "channel", "test");
        session.accept(0, REPORT);
        session.accept(500_000_000L, REPORT);
        session.stop("manual", 1_000_000_000L);
        session.accept(6_000_000_000L, new byte[]{9});
        PacketRateSession.ViewData view = session.view(7_000_000_000L);
        assertEquals(2, view.count);
        assertEquals(1, view.hz);
        assertEquals(1_000_000_000L, view.elapsedNs);
        assertEquals(1, view.previewLength);
        assertEquals("manual", session.export().reason);
    }

    @Test public void backwardsTimestampCannotMisalignLengthsOrByteTotals() {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 0, "device", "channel", "test");
        session.accept(100, new byte[]{1});
        session.accept(90, new byte[]{2, 3, 4});
        session.accept(110, new byte[]{5, 6});
        session.stop("done", 200);
        PacketRateSession.ExportData export = session.export();
        assertEquals(1, export.backwards);
        assertEquals(3, export.bytes);
        assertArrayEquals(new int[]{1, 2}, export.lengths);
        assertArrayEquals(new long[]{100, 110}, export.samples.times);
    }

    @Test public void zeroLengthAndEqualTimestampNotificationsRemainCounted() {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 0, "device", "channel", "test");
        session.accept(100, new byte[0]);
        session.accept(100, REPORT);
        PacketRateSession.ViewData view = session.view(200);
        assertEquals(2, view.count);
        assertEquals(1, view.equalTimes);
        assertTrue(Double.isNaN(view.averageHz));
        assertEquals(4, view.bytes);
    }

    @Test public void previewIsBoundedButOriginalLengthIsPreserved() {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 0, "device", "channel", "test");
        byte[] large = new byte[200];
        large[0] = (byte) 0xff;
        session.accept(100, large);
        large[0] = 0; // The callback's source buffer must not mutate our saved preview.
        PacketRateSession.ViewData view = session.view(200);
        assertEquals(64, view.preview.length);
        assertEquals(200, view.previewLength);
        assertEquals(200, view.bytes);
        assertTrue(PacketRateSession.hex(view.preview).startsWith("FF 00"));
        session.stop("done", 200);
        assertEquals(200, session.export().lengths[0]);
        assertEquals(200, session.export().payloads[0].length);
        assertEquals((byte) 0xff, session.export().payloads[0][0]);
    }

    @Test public void channelChangeClearsSamplesButKeepsExistingExportImmutable() {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 0, "device", "first channel", "test");
        session.accept(10, REPORT);
        session.stop("done", 20);
        PacketRateSession.ExportData export = session.export();
        session.channelChanged();
        assertEquals(0, session.view(30).count);
        assertEquals(-1, session.view(30).previewTime);
        session.start(40, 0, "device", "second channel", "test");
        session.accept(50, new byte[]{7});
        assertEquals("first channel", export.channel);
        assertEquals(10, export.samples.times[0]);
        assertEquals(4, export.lengths[0]);
        assertArrayEquals(REPORT, export.payloads[0]);
    }

    @Test public void timeoutWithoutPacketsDoesNotCreateFakeRate() {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 10, "device", "channel", "test");
        PacketRateSession.ViewData view = session.view(15_000_000_000L);
        assertFalse(view.recording);
        assertEquals(0, view.hz);
        assertEquals(0, view.count);
        assertTrue(Double.isNaN(view.averageHz));
        assertNull(session.export());
    }

    @Test public void storageLimitStopsWithoutOverwritingOrIndexOverflow() {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 0, "device", "channel", "test");
        for (int i = 0; i <= RateStatistics.CAPACITY; i++) session.accept(i, REPORT);
        assertFalse(session.isRecording());
        PacketRateSession.ExportData export = session.export();
        assertEquals(RateStatistics.CAPACITY, export.lengths.length);
        assertEquals(RateStatistics.CAPACITY * 4L, export.bytes);
        assertEquals(0, export.samples.times[0]);
    }

    @Test public void memoryLimitDoesNotStoreOrCountAPartialPayload() {
        PacketRateSession session = new PacketRateSession(5);
        session.start(0, 0, "device", "channel", "test");
        session.accept(10, new byte[]{1, 2, 3});
        session.accept(20, new byte[]{4, 5, 6});
        assertFalse(session.isRecording());
        PacketRateSession.ExportData data = session.export();
        assertEquals(1, data.samples.times.length);
        assertEquals(3, data.bytes);
        assertArrayEquals(new byte[]{1, 2, 3}, data.payloads[0]);
    }

    @Test public void exactMemoryLimitIncludesTheWholeFinalPayload() {
        PacketRateSession session = new PacketRateSession(4);
        session.start(0, 0, "device", "channel", "test");
        session.accept(10, REPORT);
        assertFalse(session.isRecording());
        assertEquals(4, session.export().bytes);
        assertArrayEquals(REPORT, session.export().payloads[0]);
        session.start(20, 0, "device", "channel", "test");
        session.accept(30, new byte[]{9});
        assertTrue(session.isRecording());
    }

    @Test public void backwardsOversizedPacketDoesNotStopAValidSession() {
        PacketRateSession session = new PacketRateSession(5);
        session.start(0, 0, "device", "channel", "test");
        session.accept(100, new byte[]{1});
        session.accept(90, new byte[10]);
        assertTrue(session.isRecording());
        session.accept(110, new byte[]{2});
        session.stop("done", 200);
        assertEquals(1, session.export().backwards);
        assertArrayEquals(new byte[]{2}, session.export().payloads[1]);
    }

    @Test public void changingAnExportCannotMutateStoredPayloads() {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 0, "device", "channel", "test");
        byte[] input = {0, (byte) 0xff, 3};
        session.accept(100, input);
        input[0] = 9;
        session.stop("done", 200);
        PacketRateSession.ExportData first = session.export();
        assertEquals(0, first.payloads[0][0]);
        first.payloads[0][1] = 0;
        assertEquals((byte) 0xff, session.export().payloads[0][1]);
    }
}
