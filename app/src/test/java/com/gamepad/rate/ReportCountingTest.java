package com.gamepad.rate;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReportCountingTest {
    private static final byte[] SINGLE = {0x14, 1, 0x33, (byte) 0xc2, 0x11};
    private static final byte[] DOUBLE = {0x14, 1, (byte) 0xaa, 0x43, (byte) 0xf9, 0x14, 1, 0x3d, 0x53, 0x51};

    private static PacketRateSession configured(int limit) {
        PacketRateSession session = new PacketRateSession(limit);
        assertTrue(session.configureReports(ReportFormat.fixed(5, "14 01"), session.generation()));
        session.start(0, 0, "device", "channel", "test");
        return session;
    }

    @Test public void countsMergedAndSplitReportsWhileKeepingRawPacketRows() {
        PacketRateSession session = configured(100);
        session.accept(1, SINGLE); session.accept(2, DOUBLE);
        session.accept(3, new byte[]{0x14, 1, 0, 0, 0, 0x15, 1, 0, 0, 0});
        session.accept(4, new byte[]{0x14, 1, 0, 0});
        session.accept(5, new byte[]{0}); // Completes the report from the previous callback.
        session.accept(6, new byte[0]);
        session.pause(7);
        PacketRateSession.ExportData data = session.export();
        assertEquals(6, data.samples.times.length);
        assertEquals(5, data.reports); assertEquals(1, data.mergedPackets); assertEquals(1, data.splitReports);
        assertArrayEquals(new int[]{1, 3, 4, 4, 5, 5}, data.cumulativeReports);
        assertArrayEquals(new int[]{0, 0, 0, 0, 1, 0}, data.splitReportsByPacket);
        assertArrayEquals(new int[]{0, 0, 0, 4, 0, 0}, data.pendingBytesByPacket);
        assertArrayEquals(new int[]{0, 0, 5, 0, 0, 0}, data.skippedBytesByPacket);
        assertEquals(ReportFormat.RESYNCED, data.reportStatuses[2]);
        assertEquals(ReportFormat.WAITING, data.reportStatuses[3]);
        assertEquals(ReportFormat.MATCHED, data.reportStatuses[4]);
        assertEquals(ReportFormat.EMPTY, data.reportStatuses[5]);
        assertEquals(5, data.skippedReportBytes); assertEquals(0, data.discardedFragmentBytes);
        assertEquals(0, data.pendingReportBytes);
        assertArrayEquals(new byte[]{0x14, 1, 0, 0}, data.payloads[3]);
        assertEquals(data.bytes, data.reports * 5L + data.skippedReportBytes + data.discardedFragmentBytes);
    }

    @Test public void countsEqualTimestampsButNotBackwardsOrUnstoredPayloads() {
        PacketRateSession session = configured(20);
        session.accept(2, DOUBLE); session.accept(2, SINGLE);
        session.accept(1, DOUBLE); // Out-of-order data is rejected before counting.
        session.accept(3, DOUBLE); // Would exceed the full-payload byte limit.
        PacketRateSession.ExportData data = session.export();
        assertEquals(2, data.samples.times.length); assertEquals(3, data.reports);
        assertEquals(1, data.backwards); assertEquals(15, data.bytes);
        assertArrayEquals(new int[]{2, 3}, data.cumulativeReports);
    }

    @Test public void fixedWindowWeightsReportsAtCallbackTimeAndResetsOnResume() {
        PacketRateSession session = configured(100);
        session.accept(100_000_000L, DOUBLE); session.accept(200_000_000L, SINGLE);
        PacketRateSession.ViewData view = session.view(1_000_000_000L);
        assertEquals(2, view.hz); assertEquals(3, view.reportHz);
        session.pause(1_000_000_000L);
        assertTrue(session.resume(3_000_000_000L));
        session.accept(3_100_000_000L, DOUBLE);
        assertEquals(-1, session.view(3_500_000_000L).reportHz);
        assertEquals(2, session.view(4_000_000_000L).reportHz);
        assertEquals(0, session.view(4_100_000_000L).reportHz); // Lower edge of (now - 1s, now] is excluded.
        assertEquals(5, session.view(4_100_000_000L).reports);
    }

    @Test public void splitReportArrivesWhenItsLastFragmentCompletes() {
        PacketRateSession session = configured(100);
        session.accept(900_000_000L, new byte[]{0x14, 1});
        assertEquals(0, session.view(1_000_000_000L).reports);
        assertEquals(0, session.view(1_000_000_000L).reportHz);
        assertEquals(2, session.view(1_000_000_000L).pendingReportBytes);
        session.accept(1_100_000_000L, new byte[]{0x33, (byte) 0xc2, 0x11});
        assertEquals(1, session.view(2_000_000_000L).reportHz);
        assertEquals(1, session.view(2_000_000_000L).splitReports);
        assertEquals(0, session.view(2_100_000_000L).reportHz);
        session.pause(2_200_000_000L);
        assertArrayEquals(new int[]{0, 1}, session.export().cumulativeReports);
        assertArrayEquals(new long[]{900_000_000L, 1_100_000_000L}, session.export().samples.times);
    }

    @Test public void pauseAndStopDiscardFragmentsWithoutJoiningAcrossRecordingGaps() {
        PacketRateSession session = configured(100);
        session.accept(1, new byte[]{0x14, 1});
        session.pause(2);
        assertEquals(0, session.view(2).pendingReportBytes);
        assertEquals(2, session.view(2).discardedFragmentBytes);
        session.accept(3, new byte[]{0x33, (byte) 0xc2, 0x11}); // Ignored while paused.
        assertTrue(session.resume(4));
        session.accept(5, new byte[]{0x33, (byte) 0xc2, 0x11});
        session.accept(6, SINGLE);
        session.accept(7, new byte[]{0x14, 1, 0, 0});
        session.stop("finished", 8);
        PacketRateSession.ExportData data = session.export();
        assertEquals(4, data.samples.times.length);
        assertEquals(1, data.reports); assertEquals(0, data.splitReports);
        assertEquals(3, data.skippedReportBytes); assertEquals(6, data.discardedFragmentBytes);
        assertEquals(0, data.pendingReportBytes);
        assertArrayEquals(new int[]{2, 0, 0, 4}, data.pendingBytesByPacket);
        assertEquals(data.bytes, data.reports * 5L + data.skippedReportBytes + data.discardedFragmentBytes);
    }

    @Test public void rejectedBackwardsAndOversizedPacketsCannotCompleteBufferedFragments() {
        PacketRateSession session = configured(13);
        session.accept(2, new byte[]{0x14, 1});
        session.accept(1, new byte[]{0x33, (byte) 0xc2, 0x11}); // Rejected and invalidates carry.
        session.accept(3, new byte[]{0x33, (byte) 0xc2, 0x11});
        session.accept(4, SINGLE);
        session.accept(5, new byte[]{0x14, 1});
        session.accept(6, SINGLE); // Exceeds the payload budget and stops the session.
        PacketRateSession.ExportData data = session.export();
        assertEquals(4, data.samples.times.length); assertEquals(12, data.bytes);
        assertEquals(1, data.backwards); assertEquals(1, data.reports); assertEquals(0, data.splitReports);
        assertEquals(3, data.skippedReportBytes); assertEquals(4, data.discardedFragmentBytes);
        assertEquals(0, data.pendingReportBytes);
        assertArrayEquals(new int[]{0, 0, 1, 1}, data.cumulativeReports);
        assertEquals(data.bytes, data.reports * 5L + data.skippedReportBytes + data.discardedFragmentBytes);
    }

    @Test public void exportedRuleAndTotalsStayFrozenWhenContinuingOrSwitchingChannels() {
        PacketRateSession session = configured(100);
        session.accept(1, new byte[]{0x14, 1});
        session.accept(2, new byte[]{0x33, (byte) 0xc2, 0x11, 0x14, 1, 0x33, (byte) 0xc2, 0x11});
        session.pause(3);
        PacketRateSession.ExportData frozen = session.export();
        assertTrue(session.resume(4));
        session.accept(5, new byte[]{0x55, 0x14, 1, 0x33, (byte) 0xc2, 0x11}); session.pause(6);
        assertEquals(2, frozen.reports); assertEquals(1, frozen.splitReports); assertEquals(1, frozen.mergedPackets);
        assertEquals(0, frozen.skippedReportBytes);
        assertArrayEquals(new int[]{0, 2}, frozen.cumulativeReports);
        assertArrayEquals(new int[]{0, 1}, frozen.splitReportsByPacket);
        assertArrayEquals(new int[]{2, 0}, frozen.pendingBytesByPacket);
        assertArrayEquals(new int[]{0, 0}, frozen.skippedBytesByPacket);
        session.clear(); assertTrue(session.reportFormat().enabled()); assertEquals(0, session.view(7).reports);
        session.channelChanged(); assertFalse(session.reportFormat().enabled());
        assertEquals(5, frozen.reportFormat.bytesPerReport);
        assertFalse(session.configureReports(ReportFormat.fixed(5, ""), session.generation() - 1));
        session.start(10, 0, "other", "other", "test"); session.accept(11, SINGLE); session.pause(12);
        assertEquals(0, session.export().cumulativeReports.length);
        assertEquals(0, session.export().splitReportsByPacket.length);
        assertEquals(0, session.export().pendingBytesByPacket.length);
        assertEquals(0, session.export().skippedBytesByPacket.length);
    }

    @Test public void configurationRejectsBadLengthsAndPrefixesAndCannotChangeWhileRecording() {
        for (int length : new int[]{0, -1, 65536}) {
            try { ReportFormat.fixed(length, ""); fail("Invalid length accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        for (String prefix : new String[]{"1", "GG", "14 01 02 03 04 05"}) {
            try { ReportFormat.fixed(5, prefix); fail("Invalid prefix accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        assertEquals("14 01", ReportFormat.fixed(5, "14\n01").prefixHex());
        assertEquals(2, new ReportStreamDecoder(ReportFormat.fixed(5, "14\n01")).accept(DOUBLE).reports);
        assertEquals(10, new ReportStreamDecoder(ReportFormat.fixed(1, "")).accept(DOUBLE).reports);
        PacketRateSession session = configured(100);
        assertFalse(session.configureReports(ReportFormat.OFF, session.generation()));
        session.accept(1, SINGLE); session.pause(2);
        assertTrue(session.configureReports(ReportFormat.OFF, session.generation()));
        assertFalse(session.isPaused()); assertNull(session.export());
    }
}
