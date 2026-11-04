package com.gamepad.rate;

/** Localize exported labels only; receiver metadata and device-supplied text stay unchanged. */
final class CsvLabels {
    private CsvLabels() {}

    static String parameter(String key) {
        if (key == null) return null;
        switch (key) {
            case "csv_schema": return "CSV格式版本";
            case "field_language": return "字段语言";
            case "measurement": return "测量类型";
            case "clock": return "时间基准";
            case "device": return "设备标识";
            case "channel": return "数据通道";
            case "started": return "开始时间";
            case "stop_reason": return "停止原因";
            case "android_sdk": return "Android SDK版本";
            case "phone": return "手机型号";
            case "duration_ms": return "测试跨度_毫秒";
            case "paused_ms": return "暂停时长_毫秒";
            case "active_duration_ms": return "有效记录时长_毫秒";
            case "segments": return "分段说明";
            case "average_hz": return "平均回报率_Hz";
            case "total_bytes": return "接收总字节数";
            case "rejected_backwards": return "丢弃的逆序时间戳数量";
            case "payload": return "原始数据说明";
            case "ble_info_schema": return "蓝牙信息格式版本";
            case "ble_info_clock": return "蓝牙信息时间基准";
            case "ble_info_unknown": return "蓝牙未知参数说明";
            case "ble_info_columns": return "蓝牙信息列名";
            case "ble_info": return "蓝牙连接信息";
            case "id": return "快照编号";
            case "observed_elapsed_realtime_ns": return "快照保存时间戳_elapsedRealtime_纳秒";
            case "key": return "参数名";
            case "value": return "参数值";
            case "sample": return "采样序号";
            case "callback_elapsed_realtime_ns": return "接收回调时间戳_elapsedRealtime_纳秒";
            case "relative_ms": return "相对时间_毫秒";
            case "interval_ms": return "采样间隔_毫秒";
            case "payload_bytes": return "数据长度_字节";
            case "payload_hex": return "完整数据_十六进制";
            case "ble_connection_info_id": return "蓝牙连接快照编号";
            case "scope": return "参数范围";
            case "device_name": return "设备名称";
            case "device_address": return "设备地址";
            case "device_type": return "设备类型";
            case "transport": return "传输方式";
            case "bond_state": return "配对状态";
            case "gatt_connected": return "本App GATT连接状态";
            case "connected_elapsed_realtime_ns": return "连接建立时间戳_elapsedRealtime_纳秒";
            case "att_mtu_bytes": return "ATT MTU_字节";
            case "att_mtu_status": return "ATT MTU状态";
            case "att_mtu_source": return "ATT MTU来源";
            case "att_mtu_observed_ns": return "ATT MTU观测时间戳_elapsedRealtime_纳秒";
            case "att_notification_pdu_value_space_bytes": return "ATT通知值字段空间_字节";
            case "phy_tx": return "发送PHY_以手机为基准";
            case "phy_rx": return "接收PHY_以手机为基准";
            case "phy_status": return "PHY状态";
            case "phy_source": return "PHY来源";
            case "phy_observed_ns": return "PHY观测时间戳_elapsedRealtime_纳秒";
            case "rssi_dbm": return "接收信号强度_RSSI_dBm";
            case "rssi_status": return "RSSI状态";
            case "rssi_source": return "RSSI来源";
            case "rssi_observed_ns": return "RSSI观测时间戳_elapsedRealtime_纳秒";
            case "ll_tx_max_octets": return "LL发送最大长度_字节";
            case "ll_rx_max_octets": return "LL接收最大长度_字节";
            case "ll_length_status": return "LL长度获取状态";
            case "connection_interval_ms": return "蓝牙连接间隔_毫秒";
            case "peripheral_latency_events": return "外设延迟_连接事件数";
            case "supervision_timeout_ms": return "链路监督超时_毫秒";
            case "connection_parameters_status": return "连接参数获取状态";
            case "encryption_status": return "链路加密状态";
            case "service_uuid": return "服务UUID";
            case "service_instance": return "服务实例编号";
            case "characteristic_uuid": return "特征UUID";
            case "characteristic_instance": return "特征实例编号";
            case "characteristic_properties": return "特征属性";
            case "subscription": return "订阅方式";
            case "identity": return "设备身份信息";
            case "timestamp_unit": return "时间戳单位";
            case "timestamp_resolution": return "时间戳分辨率说明";
            case "session_duration_ms": return "会话时长_毫秒";
            case "callback_count": return "输入回调数量";
            case "historical_samples": return "历史采样数量";
            case "equal_timestamps": return "相同时间戳数量";
            case "button_presses_excluded_from_rate": return "按键按下次数_不计入回报率";
            case "event_uptime_ns": return "事件时间戳_uptime_纳秒";
            case "historical": return "是否历史采样";
            case "callback_uptime_ms": return "回调时间戳_uptime_毫秒";
            default: return key;
        }
    }

    static String value(String key, String value) {
        if (key == null || value == null || value.isEmpty()) return value;
        switch (key) {
            case "att_mtu_status":
            case "phy_status":
            case "rssi_status":
            case "ll_length_status":
            case "connection_parameters_status":
            case "encryption_status": return status(value);
            case "scope": return value.equals("this_app_gatt_connection") ? "本App的GATT连接" : value;
            case "device_type":
                switch (value) {
                    case "LE": return "低功耗蓝牙（LE）";
                    case "dual": return "双模蓝牙";
                    case "classic": return "经典蓝牙";
                    case "unknown": return "未知";
                    default: return value;
                }
            case "transport": return value.equals("LE") ? "低功耗蓝牙（LE）" : value;
            case "bond_state":
                switch (value) {
                    case "bonded": return "已配对";
                    case "bonding": return "配对中";
                    case "not_bonded": return "未配对";
                    case "unknown": return "未知";
                    default: return value;
                }
            case "gatt_connected":
                return value.equals("true") ? "已连接" : value.equals("false") ? "未连接" : value;
            case "subscription":
                return value.equals("Notify") ? "通知（Notify）" : value.equals("Indicate") ? "指示（Indicate）" : value;
            case "characteristic_properties": return properties(value);
            case "phy_source": return value.equals("not_observed") ? "未观测" : value;
            case "measurement":
                switch (value) {
                    case "ble_selected_characteristic_callback_rate": return "BLE所选特征接收回调率";
                    case "usb_interrupt_in_nonempty_completion_rate": return "USB中断IN非空接收完成率";
                    case "android_joystick_events_not_bluetooth_packets": return "Android摇杆输入事件率（非蓝牙数据包率）";
                    default: return value;
                }
            case "clock":
                if (value.equals("elapsedRealtimeNanos_not_hid_uptime")) {
                    return "elapsedRealtimeNanos（含系统休眠，与HID uptime时钟不同）";
                }
                return value;
            case "segments":
                return value.equals("blank_interval_marks_first_sample_or_resume_boundary")
                        ? "首条采样及续录边界的间隔留空" : value;
            case "payload":
                switch (value) {
                    case "full_received_bytes_hex_no_protocol_decoding": return "本次接收的完整字节（十六进制，未解析协议）";
                    case "unavailable_in_android_input_events_use_ble_or_usb_mode": return "Android输入事件不提供原始数据，请使用BLE或USB模式";
                    default: return value;
                }
            case "ble_info_unknown":
                return value.equals("empty_numeric_value_with_explicit_status_or_source")
                        ? "未知数值留空，并注明状态或来源" : value;
            case "timestamp_unit": return value.equals("ns") ? "纳秒" : value.equals("ms") ? "毫秒" : value;
            case "timestamp_resolution":
                return value.equals("ns_unit_not_accuracy_guarantee") ? "纳秒单位（不保证纳秒精度）"
                        : value.equals("ms") ? "毫秒" : value;
            default: return value;
        }
    }

    private static String status(String value) {
        switch (value) {
            case "not_reported": return "未上报";
            case "not_read": return "未读取";
            case "not_observed": return "未观测";
            case "confirmed_callback": return "回调确认";
            case "request_pending": return "请求中";
            case "request_rejected": return "请求未被接受";
            case "request_failed": return "请求失败";
            case "read_pending": return "读取中";
            case "read_rejected": return "读取未被接受";
            case "read_failed": return "读取失败";
            case "timeout": return "超时";
            case "invalid_callback_value": return "回调值无效";
            case "unavailable_public_api_sdk35": return "当前SDK35公开接口不可获取";
            case "unknown_bond_state_is_not_encryption_state": return "未知（配对状态不代表链路加密状态）";
            default:
                String prefix = "callback_failed_";
                if (value.startsWith(prefix)) {
                    String code = value.substring(prefix.length());
                    if (code.matches("-?\\d+")) return "回调失败（状态码 " + code + "）";
                }
                return value;
        }
    }

    private static String properties(String value) {
        String[] tokens = value.split(" ", -1);
        StringBuilder result = new StringBuilder();
        for (String token : tokens) {
            String label;
            switch (token) {
                case "Read": label = "读取（Read）"; break;
                case "Write": label = "写入（Write）"; break;
                case "WriteNoResponse": label = "无响应写入（WriteNoResponse）"; break;
                case "Notify": label = "通知（Notify）"; break;
                case "Indicate": label = "指示（Indicate）"; break;
                default: return value;
            }
            if (result.length() != 0) result.append(' ');
            result.append(label);
        }
        return result.toString();
    }
}
