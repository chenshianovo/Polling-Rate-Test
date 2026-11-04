package com.gamepad.rate;

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReportStreamDecoderTest {
    private static final byte[] TWO_REPORTS = {
            0x14, 1, (byte) 0xaa, 0x43, (byte) 0xf9, 0x14, 1, 0x3d, 0x53, 0x51};

    private static ReportStreamDecoder decoder() {
        return new ReportStreamDecoder(ReportFormat.fixed(5, "14 01"));
    }

    @Test public void everyPartitionOfTwoReportsPreservesReportAndByteCounts() {
        // All 512 choices of boundaries exercise header, body and exact-report cuts.
        for (int cuts = 0; cuts < (1 << 9); cuts++) {
            ReportStreamDecoder decoder = decoder();
            int start = 0, reports = 0, splits = 0, skipped = 0;
            for (int end = 1; end <= TWO_REPORTS.length; end++) {
                if (end < TWO_REPORTS.length && (cuts & (1 << (end - 1))) == 0) continue;
                ReportStreamDecoder.Result result = decoder.accept(Arrays.copyOfRange(TWO_REPORTS, start, end));
                reports += result.reports; splits += result.splitReports; skipped += result.skippedBytes;
                assertEquals("Byte conservation for partition " + cuts, end, reports * 5 + skipped + result.pendingBytes);
                assertEquals(result.pendingBytes, decoder.pendingBytes());
                start = end;
            }
            int expectedSplits = ((cuts & 0x00f) == 0 ? 0 : 1) + ((cuts & 0x1e0) == 0 ? 0 : 1);
            assertEquals("Reports for partition " + cuts, 2, reports);
            assertEquals("Split reports for partition " + cuts, expectedSplits, splits);
            assertEquals(0, skipped); assertEquals(0, decoder.pendingBytes());
        }
    }

    @Test public void overlappingPrefixCanSpanSeveralCallbacksWithoutDroppingItsSuffix() {
        ReportStreamDecoder decoder = new ReportStreamDecoder(ReportFormat.fixed(5, "AA AA AB"));
        assertEquals(1, decoder.accept(new byte[]{(byte) 0xaa}).pendingBytes);
        assertEquals(2, decoder.accept(new byte[]{(byte) 0xaa}).pendingBytes);
        ReportStreamDecoder.Result overlap = decoder.accept(new byte[]{(byte) 0xaa});
        assertEquals(1, overlap.skippedBytes); assertEquals(2, overlap.pendingBytes);
        assertEquals(0, overlap.reports);
        assertEquals(4, decoder.accept(new byte[]{(byte) 0xab, (byte) 0xaa}).pendingBytes);
        ReportStreamDecoder.Result completed = decoder.accept(new byte[]{(byte) 0xaa});
        assertEquals(1, completed.reports); assertEquals(1, completed.splitReports);
        assertEquals(0, completed.pendingBytes); assertEquals(0, completed.skippedBytes);
    }

    @Test public void headerBytesInsideReportBodyDoNotStartAnotherReport() {
        ReportStreamDecoder decoder = decoder();
        ReportStreamDecoder.Result first = decoder.accept(new byte[]{0x14, 1, 0x14, 1});
        assertEquals(0, first.reports); assertEquals(4, first.pendingBytes);
        ReportStreamDecoder.Result next = decoder.accept(new byte[]{0x14, 0x14, 1, 2, 3, 4});
        assertEquals(2, next.reports); assertEquals(1, next.splitReports);
        assertEquals(0, next.pendingBytes); assertEquals(0, next.skippedBytes);
    }

    @Test public void emptyCallbacksPreserveCarryAndExplicitDiscardStartsFresh() {
        ReportStreamDecoder decoder = decoder();
        assertEquals(ReportFormat.EMPTY, decoder.accept(new byte[0]).status);
        assertEquals(2, decoder.accept(new byte[]{0x14, 1}).pendingBytes);
        ReportStreamDecoder.Result empty = decoder.accept(new byte[0]);
        assertEquals(0, empty.reports); assertEquals(0, empty.splitReports);
        assertEquals(0, empty.skippedBytes); assertEquals(2, empty.pendingBytes);
        assertEquals(2, decoder.discardPending()); assertEquals(0, decoder.discardPending());
        ReportStreamDecoder.Result tail = decoder.accept(new byte[]{0x33, (byte) 0xc2, 0x11});
        assertEquals(0, tail.reports); assertEquals(3, tail.skippedBytes); assertEquals(0, tail.pendingBytes);
        assertEquals(2, decoder.accept(TWO_REPORTS).reports);
    }

    @Test public void noHeaderUsesExplicitStartingAlignmentAndCarriesIncompleteBlocks() {
        ReportStreamDecoder decoder = new ReportStreamDecoder(ReportFormat.fixed(3, ""));
        ReportStreamDecoder.Result first = decoder.accept(new byte[]{1, 2});
        assertEquals(0, first.reports); assertEquals(2, first.pendingBytes);
        ReportStreamDecoder.Result next = decoder.accept(new byte[]{3, 4, 5, 6, 7});
        assertEquals(2, next.reports); assertEquals(1, next.splitReports);
        assertEquals(1, next.pendingBytes); assertEquals(0, next.skippedBytes);
        assertEquals(ReportFormat.MATCHED_PENDING, next.status);
        assertEquals(1, decoder.discardPending());
        ReportStreamDecoder.Result aligned = decoder.accept(new byte[]{8, 9, 10});
        assertEquals(1, aligned.reports); assertEquals(0, aligned.splitReports);
    }

    @Test public void oneThousandRandomChunkingsMatchAnUnsplitStream() {
        byte[] stream = {
                0x55, 0x14,
                0x14, 1, (byte) 0xaa, 0x43, (byte) 0xf9,
                0x14, 1, 0x14, 1, 0x14,
                0x22, 0x14, 0x14,
                0x14, 1, 0x20, 0x30, 0x40,
                0x14, 1, 0x55};
        ReportStreamDecoder.Result whole = decoder().accept(stream);
        assertEquals(3, whole.reports); assertEquals(5, whole.skippedBytes); assertEquals(3, whole.pendingBytes);
        Random random = new Random(0x14015eedL);
        for (int trial = 0; trial < 1000; trial++) {
            ReportStreamDecoder decoder = decoder();
            int offset = 0, reports = 0, skipped = 0;
            while (offset < stream.length) {
                if (random.nextBoolean()) {
                    int pending = decoder.pendingBytes();
                    ReportStreamDecoder.Result empty = decoder.accept(new byte[0]);
                    assertEquals(0, empty.reports); assertEquals(0, empty.skippedBytes);
                    assertEquals(pending, empty.pendingBytes);
                }
                int end = Math.min(stream.length, offset + 1 + random.nextInt(9));
                ReportStreamDecoder.Result result = decoder.accept(Arrays.copyOfRange(stream, offset, end));
                reports += result.reports; skipped += result.skippedBytes;
                assertEquals("Byte conservation in trial " + trial, end, reports * 5 + skipped + result.pendingBytes);
                offset = end;
            }
            assertEquals(whole.reports, reports); assertEquals(whole.skippedBytes, skipped);
            assertEquals(whole.pendingBytes, decoder.pendingBytes());
        }
    }
}
