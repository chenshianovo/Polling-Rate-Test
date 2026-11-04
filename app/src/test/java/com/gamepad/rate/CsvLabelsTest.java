package com.gamepad.rate;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class CsvLabelsTest {
    @Test public void connectionParametersHaveDistinctLabelsAndPreserveLayerAndUnits() {
        String[] keys = {
                "scope", "device_name", "device_address", "device_type", "transport", "bond_state",
                "gatt_connected", "connected_elapsed_realtime_ns", "att_mtu_bytes", "att_mtu_status",
                "att_mtu_source", "att_mtu_observed_ns", "att_notification_pdu_value_space_bytes",
                "phy_tx", "phy_rx", "phy_status", "phy_source", "phy_observed_ns", "rssi_dbm",
                "rssi_status", "rssi_source", "rssi_observed_ns", "ll_tx_max_octets", "ll_rx_max_octets",
                "ll_length_status", "connection_interval_ms", "peripheral_latency_events",
                "supervision_timeout_ms", "connection_parameters_status", "encryption_status",
                "service_uuid", "service_instance", "characteristic_uuid", "characteristic_instance",
                "characteristic_properties", "subscription"
        };
        Set<String> labels = new HashSet<>();
        for (String key : keys) {
            String label = CsvLabels.parameter(key);
            assertNotEquals(key, label);
            assertTrue("Missing Chinese parameter name for " + key, label.matches(".*[\\u4e00-\\u9fff].*"));
            assertTrue("Ambiguous label for " + key, labels.add(label));
        }
        assertEquals("ATT MTU_字节", CsvLabels.parameter("att_mtu_bytes"));
        assertEquals("LL发送最大长度_字节", CsvLabels.parameter("ll_tx_max_octets"));
        assertEquals("外设延迟_连接事件数", CsvLabels.parameter("peripheral_latency_events"));
        assertNotEquals(CsvLabels.parameter("event_uptime_ns"), CsvLabels.parameter("callback_elapsed_realtime_ns"));
    }

    @Test public void unknownFieldsAndDeviceTextRemainUnchanged() {
        assertEquals("vendor_counter", CsvLabels.parameter("vendor_counter"));
        assertEquals("not_read", CsvLabels.value("vendor_status", "not_read"));
        assertEquals("vendor_state", CsvLabels.value("att_mtu_status", "vendor_state"));
        assertEquals("unknown", CsvLabels.value("device_name", "unknown"));
        assertEquals(" =1+1,\"device\"\nnext", CsvLabels.value("device_name", " =1+1,\"device\"\nnext"));
        assertEquals("", CsvLabels.value("att_mtu_bytes", ""));
        assertNull(CsvLabels.parameter(null));
        assertNull(CsvLabels.value("att_mtu_status", null));
    }

    @Test public void errorsKeepTheirNumericCodesAndCallbackSourcesStayLiteral() {
        assertEquals("回调失败（状态码 133）", CsvLabels.value("att_mtu_status", "callback_failed_133"));
        assertEquals("回调失败（状态码 -1）", CsvLabels.value("rssi_status", "callback_failed_-1"));
        assertEquals("callback_failed_vendor", CsvLabels.value("phy_status", "callback_failed_vendor"));
        assertEquals("回调确认", CsvLabels.value("att_mtu_status", "confirmed_callback"));
        assertEquals("当前SDK35公开接口不可获取", CsvLabels.value("ll_length_status", "unavailable_public_api_sdk35"));
        assertEquals("onMtuChanged", CsvLabels.value("att_mtu_source", "onMtuChanged"));
        assertEquals("onPhyUpdate", CsvLabels.value("phy_source", "onPhyUpdate"));
    }
}
