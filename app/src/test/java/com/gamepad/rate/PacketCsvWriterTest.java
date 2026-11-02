package com.gamepad.rate;

import java.io.StringWriter;
import org.junit.Test;
import static org.junit.Assert.*;

public class PacketCsvWriterTest {
    @Test public void csvMarksResumeBoundaryWithoutChangingRawTimestampOrPayload() throws Exception {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 0, "device", "channel", "test");
        session.accept(0, new byte[]{1});
        session.accept(8_000_000, new byte[]{2});
        session.pause(10_000_000);
        session.resume(1_010_000_000);
        session.accept(1_010_000_000, new byte[]{3});
        session.accept(1_018_000_000, new byte[]{4});
        session.pause(1_020_000_000);
        StringWriter writer = new StringWriter();
        PacketCsvWriter.write(writer, session.export(), "test", 35, "phone");
        String text = writer.toString();
        assertTrue(text.contains("# paused_ms,1000.000000\r\n"));
        assertTrue(text.contains("# active_duration_ms,20.000000\r\n"));
        assertTrue(text.contains("3,1010000000,1010.000000,,1,\"03\"\r\n"));
        assertTrue(text.contains("4,1018000000,1018.000000,8.000000,1,\"04\"\r\n"));
    }
    @Test public void fullPayloadBeyondPreviewIsExportedWithExactTimestampAndLength() throws Exception {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 0, "device", "channel", "test");
        byte[] bytes = new byte[200];
        bytes[0] = (byte) 0xff; bytes[199] = (byte) 0x81;
        session.accept(10_000_000L, bytes);
        session.accept(18_000_000L, new byte[]{0, 1});
        session.stop("done", 20_000_000L);
        StringWriter writer = new StringWriter();
        PacketCsvWriter.write(writer, session.export(), "ble_selected_characteristic_callback_rate", 35, "phone");
        String text = writer.toString();
        assertTrue(text.startsWith("\uFEFF# measurement,"));
        assertTrue(text.contains("payload_bytes,payload_hex\r\n"));
        assertTrue(text.contains("1,10000000,0.000000,,200,\"" + PacketRateSession.hex(bytes) + "\"\r\n"));
        assertTrue(text.contains("2,18000000,8.000000,8.000000,2,\"00 01\"\r\n"));
    }
    @Test public void emptyPayloadAndMetadataRemainValidCsv() throws Exception {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 0, " =1+1", "a,\"b\"\nnext", "test");
        session.accept(0, new byte[0]); session.stop("done", 20);
        StringWriter writer = new StringWriter();
        PacketCsvWriter.write(writer, session.export(), "test", 35, "phone");
        String text = writer.toString();
        assertTrue(text.contains("# device,\"' =1+1\"\r\n"));
        assertTrue(text.contains("# channel,\"a,\"\"b\"\" next\"\r\n"));
        assertTrue(text.contains("1,0,0.000000,,0,\"\"\r\n"));
        assertTrue(text.contains("# average_hz,\r\n"));
    }
}
