package com.gamepad.rate;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class PacketCsvWriterTest {
    @Test public void bleRowsKeepTheirOwnMtuSnapshotAndExportIsFrozen() throws Exception {
        PacketRateSession session = new PacketRateSession();
        Map<String, String> first = new LinkedHashMap<>();
        first.put("device_name", " =1+1"); first.put("att_mtu_bytes", "23");
        session.start(100, 0, "device", "channel", "test", new ConnectionMetadata(90, first));
        first.put("att_mtu_bytes", "999"); // The connection observation must own a copy.
        session.accept(110, new byte[]{1});
        Map<String, String> next = new LinkedHashMap<>(first);
        next.put("att_mtu_bytes", "247");
        session.updateConnection(new ConnectionMetadata(120, next));
        session.accept(130, new byte[]{2});
        session.pause(140);
        PacketRateSession.ExportData frozen = session.export();
        next.put("att_mtu_bytes", "517");
        session.updateConnection(new ConnectionMetadata(150, next));
        assertEquals(2, frozen.connections.size());
        assertArrayEquals(new int[]{0, 1}, frozen.connectionIds);
        assertEquals("23", frozen.connections.get(0).values.get("att_mtu_bytes"));
        assertEquals("247", frozen.connections.get(1).values.get("att_mtu_bytes"));
        StringWriter writer = new StringWriter();
        PacketCsvWriter.write(writer, frozen, "ble_selected_characteristic_callback_rate", 35, "phone");
        String csv = writer.toString();
        assertTrue(csv.contains("payload_hex,ble_connection_info_id\r\n"));
        assertTrue(csv.contains("# ble_info,0,90,\"device_name\",\"' =1+1\"\r\n"));
        assertTrue(csv.contains("# ble_info,1,120,\"att_mtu_bytes\",\"247\"\r\n"));
        assertTrue(csv.contains("1,110,0.000000,,1,\"01\",0\r\n"));
        assertTrue(csv.contains("2,130,0.000020,0.000020,1,\"02\",1\r\n"));
        assertFalse(csv.contains("517"));
    }

    @Test public void unknownLinkValuesAreBlankAndDoNotInventAnInterval() throws Exception {
        PacketRateSession session = new PacketRateSession();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("att_mtu_bytes", ""); values.put("att_mtu_status", "not_reported");
        values.put("connection_interval_ms", "");
        values.put("connection_parameters_status", "unavailable_public_api_sdk35");
        session.start(0, 0, "device", "channel", "test", new ConnectionMetadata(0, values));
        session.accept(0, new byte[]{0}); session.accept(8_000_000, new byte[]{1}); session.stop("done", 9_000_000);
        session.updateConnection(new ConnectionMetadata(10_000_000, Map.of("att_mtu_bytes", "517")));
        StringWriter writer = new StringWriter();
        PacketCsvWriter.write(writer, session.export(), "ble", 35, "phone");
        String csv = writer.toString();
        assertTrue(csv.contains("\"connection_interval_ms\",\"\"\r\n"));
        assertTrue(csv.contains("\"att_mtu_status\",\"not_reported\"\r\n"));
        assertFalse(csv.contains("517")); // Disconnect/new observations cannot relabel finished data.
    }

    @Test public void connectionHistoryStopsAtItsBoundAndClearDropsOldMetadata() throws Exception {
        PacketRateSession session = new PacketRateSession();
        session.start(0, 0, "device", "channel", "test", new ConnectionMetadata(0, Map.of("rssi_dbm", "-60")));
        session.accept(0, new byte[]{1});
        for (int i = 1; i <= PacketRateSession.MAX_CONNECTION_SNAPSHOTS; i++) {
            session.updateConnection(new ConnectionMetadata(i, Map.of("observation", Integer.toString(i))));
        }
        assertFalse(session.isRecording());
        assertEquals(PacketRateSession.MAX_CONNECTION_SNAPSHOTS, session.export().connections.size());
        session.start(2000, 0, "usb", "endpoint", "test");
        session.accept(2000, new byte[]{2}); session.stop("done", 2001);
        StringWriter writer = new StringWriter();
        PacketCsvWriter.write(writer, session.export(), "usb", 35, "phone");
        assertFalse(writer.toString().contains("ble_connection_info_id"));
        assertFalse(writer.toString().contains("# ble_info"));
    }

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
