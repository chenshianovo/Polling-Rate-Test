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
        assertTrue(csv.contains("完整数据_十六进制,蓝牙连接快照编号\r\n"));
        assertTrue(csv.contains("# 蓝牙连接信息,0,90,\"设备名称\",\"' =1+1\"\r\n"));
        assertTrue(csv.contains("# 蓝牙连接信息,1,120,\"ATT MTU_字节\",\"247\"\r\n"));
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
        assertTrue(csv.contains("\"蓝牙连接间隔_毫秒\",\"\"\r\n"));
        assertTrue(csv.contains("\"ATT MTU状态\",\"未上报\"\r\n"));
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
        assertFalse(writer.toString().contains("蓝牙连接快照编号"));
        assertFalse(writer.toString().contains("# 蓝牙连接信息"));
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
        assertTrue(text.contains("# 暂停时长_毫秒,1000.000000\r\n"));
        assertTrue(text.contains("# 有效记录时长_毫秒,20.000000\r\n"));
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
        assertTrue(text.startsWith("\uFEFF# CSV格式版本,2\r\n# 字段语言,中文\r\n"));
        assertTrue(text.contains("数据长度_字节,完整数据_十六进制\r\n"));
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
        assertTrue(text.contains("# 设备标识,\"' =1+1\"\r\n"));
        assertTrue(text.contains("# 数据通道,\"a,\"\"b\"\" next\"\r\n"));
        assertTrue(text.contains("1,0,0.000000,,0,\"\"\r\n"));
        assertTrue(text.contains("# 平均接收率_Hz,\r\n"));
    }

    @Test public void mergedAndSplitReportsKeepOneRawRowAndConnectionIdPerCallback() throws Exception {
        PacketRateSession session = new PacketRateSession();
        assertTrue(session.configureReports(ReportFormat.fixed(5, "14 01"), session.generation()));
        session.start(100, 0, "device", "channel", "test", new ConnectionMetadata(90, Map.of("att_mtu_bytes", "247")));
        session.accept(110, new byte[]{0x14, 1, (byte) 0xaa, 0x43, (byte) 0xf9, 0x14, 1, 0x3d, 0x53, 0x51});
        session.accept(120, new byte[]{0x14, 1});
        session.accept(130, new byte[]{(byte) 0xe0, 0x52, (byte) 0xb3});
        session.accept(140, new byte[]{0x14, 1, 0, 0});
        session.pause(150);
        PacketRateSession.ExportData frozen = session.export();
        assertTrue(session.resume(160));
        session.accept(170, new byte[]{0}); // The fragment before pause must not be completed here.
        session.pause(180);
        StringWriter writer = new StringWriter();
        PacketCsvWriter.write(writer, frozen, "ble_selected_characteristic_callback_rate", 35, "phone");
        String csv = writer.toString();
        assertTrue(csv.contains("蓝牙连接快照编号,本次完成报告数,累计报告数,本次拆包完成数,接收后待拼字节,本次失配丢弃字节,报告解析状态\r\n"));
        assertTrue(csv.contains("# 接收包总数,4\r\n"));
        assertTrue(csv.contains("# 摇杆报告总数,3\r\n# 合包完成次数,1\r\n# 拆包完成报告数,1\r\n# 发生失配的接收次数,0\r\n"));
        assertTrue(csv.contains("# 当前待拼字节,0\r\n# 失配丢弃总字节,0\r\n# 中断丢弃残片字节,4\r\n"));
        assertTrue(csv.contains("1,110,0.000000,,10,\"14 01 AA 43 F9 14 01 3D 53 51\",0,2,2,0,0,0,\"已完成\"\r\n"));
        assertTrue(csv.contains("2,120,0.000010,0.000010,2,\"14 01\",0,0,2,0,2,0,\"等待拼接\"\r\n"));
        assertTrue(csv.contains("3,130,0.000020,0.000010,3,\"E0 52 B3\",0,1,3,1,0,0,\"已完成\"\r\n"));
        assertTrue(csv.contains("4,140,0.000030,0.000010,4,\"14 01 00 00\",0,0,3,0,4,0,\"等待拼接\"\r\n"));
        assertFalse(csv.contains("5,170,"));
        assertEquals(3, session.export().reports);
    }
}
