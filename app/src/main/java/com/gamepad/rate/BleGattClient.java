package com.gamepad.rate;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

/** All GATT commands/callbacks run on one worker. The Activity handles permission requests. */
@SuppressLint("MissingPermission")
final class BleGattClient {
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final PacketRateSession session;
    private final HandlerThread thread = new HandlerThread("ble-gatt-receiver");
    private final Handler worker;
    private BluetoothGatt gatt;
    private final List<Channel> channels = new ArrayList<>();
    private Channel selected, requested;
    private BluetoothGattDescriptor pendingDescriptor;
    private boolean connected, busy, ready, disabling;
    private String deviceName = "尚未选择 BLE 设备";
    private String status = "未连接";
    private String services = "连接后自动发现";
    private Runnable timeout;
    private volatile State state;
    private volatile boolean foreground;
    private long recordingCommand; // Guarded by the session monitor, including queued start requests.
    private String address = "", simpleName = "", bondState = "unknown", deviceType = "unknown";
    private long connectedNs = -1, mtuNs = -1, phyNs = -1, rssiNs = -1;
    private int mtu, txPhy, rxPhy;
    private Integer rssi;
    private String mtuStatus = "not_reported", phyStatus = "not_read", rssiStatus = "not_read";
    private String phySource = "not_observed";
    private String infoStatus = "未连接";
    private static final int INFO_NONE = 0, INFO_RSSI = 1, INFO_PHY = 2, INFO_MTU = 3;
    private int infoOperation;
    private boolean selectAfterInfo;
    private Runnable infoTimeout;

    BleGattClient(Context context, PacketRateSession session) {
        this.context = context.getApplicationContext();
        this.session = session;
        thread.start();
        worker = new Handler(thread.getLooper());
        publish();
    }

    State state() { return state; }

    void foreground(boolean value) {
        foreground = value;
        if (!value) stop("窗口失去焦点，测试已停止");
    }

    void stop(String reason) {
        synchronized (session) {
            recordingCommand++;
            session.stop(reason, SystemClock.elapsedRealtimeNanos());
        }
    }

    void pause() {
        synchronized (session) {
            recordingCommand++;
            session.pause(SystemClock.elapsedRealtimeNanos());
        }
    }

    void connect(BluetoothDevice device) {
        worker.post(() -> {
            closeInternal("切换设备，测试停止");
            resetLinkInfo();
            channels.clear();
            selected = requested = null;
            services = "正在连接，稍后自动发现服务";
            session.channelChanged();
            try {
                String name = device.getName();
                simpleName = name == null || name.isEmpty() ? "未命名设备" : name;
                address = device.getAddress();
                deviceName = simpleName + "\n" + address;
                readDeviceInfo(device);
                status = "建立本 App 的 GATT 连接…";
                busy = true;
                // API 26+ supplies a worker Handler; UI redraws do not run in this callback queue.
                gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE,
                        BluetoothDevice.PHY_LE_1M_MASK, worker);
                if (gatt == null) { closeInternal("无法创建 GATT 客户端"); return; }
                armTimeout("连接超时，请确认设备支持 BLE 且仍可连接");
                publish();
            } catch (SecurityException exception) {
                closeInternal("蓝牙连接权限不可用，请重新授权");
            } catch (IllegalArgumentException | IllegalStateException exception) {
                closeInternal("连接失败：" + exception.getMessage());
            }
        });
    }

    void select(String key) {
        worker.post(() -> {
            if (!connected || busy || infoOperation != INFO_NONE) return;
            Channel found = null;
            for (Channel channel : channels) if (channel.key.equals(key)) found = channel;
            if (found == null || (ready && selected == found)) return;
            session.channelChanged();
            ready = false;
            requested = found;
            busy = true;
            status = "正在订阅 " + requested.mode;
            if (selected != null) {
                disabling = true;
                writeCccd(selected.characteristic.getDescriptor(CCCD), BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            } else enableRequested();
        });
    }

    void start(int seconds) {
        synchronized (session) {
            long command = ++recordingCommand;
            worker.post(() -> {
                synchronized (session) {
                    if (command != recordingCommand || !foreground || !ready || selected == null
                            || busy || infoOperation != INFO_NONE || !connected || session.isRecording()) return;
                    if (session.isPaused()) session.resume(SystemClock.elapsedRealtimeNanos());
                    else session.start(SystemClock.elapsedRealtimeNanos(), seconds, deviceName, selected.label,
                            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date()),
                            connectionMetadata(SystemClock.elapsedRealtimeNanos()));
                }
            });
        }
    }

    void disconnect(String reason) {
        // Stop recording immediately, even if the worker has callbacks queued.
        stop(reason);
        worker.post(() -> closeInternal(reason));
    }

    void destroy() {
        session.stop("页面关闭", SystemClock.elapsedRealtimeNanos());
        worker.post(() -> {
            closeInternal("页面关闭");
            thread.quitSafely();
        });
    }

    // Read diagnostics only on demand/once before auto-start, never poll during a rate measurement.
    void refreshLinkInfo() { worker.post(() -> refreshLinkInfoInternal(false)); }

    void requestMtu(int value) {
        if (value < 23 || value > 517) return;
        worker.post(() -> {
            if (!connected || busy || infoOperation != INFO_NONE || session.isRecording()) return;
            selectAfterInfo = false;
            infoOperation = INFO_MTU;
            infoStatus = "等待 MTU 协商结果";
            mtuStatus = "request_pending";
            publish();
            try {
                if (!gatt.requestMtu(value)) { mtuStatus = "request_rejected"; finishInfo("MTU 请求未被接受"); return; }
                armInfoTimeout(INFO_MTU);
            } catch (SecurityException error) { closeInternal("蓝牙连接权限已撤销"); }
            catch (IllegalStateException error) { mtuStatus = "request_failed"; finishInfo("MTU 请求失败"); }
        });
    }

    private void resetLinkInfo() {
        deviceName = "尚未选择 BLE 设备";
        address = simpleName = "";
        bondState = deviceType = "unknown";
        connectedNs = mtuNs = phyNs = rssiNs = -1;
        mtu = txPhy = rxPhy = 0; rssi = null;
        mtuStatus = "not_reported"; phyStatus = rssiStatus = "not_read";
        phySource = "not_observed";
        infoStatus = "等待连接";
    }

    private void readDeviceInfo(BluetoothDevice device) {
        int bond = device.getBondState(), type = device.getType();
        bondState = bond == BluetoothDevice.BOND_BONDED ? "bonded"
                : bond == BluetoothDevice.BOND_BONDING ? "bonding" : "not_bonded";
        deviceType = type == BluetoothDevice.DEVICE_TYPE_LE ? "LE"
                : type == BluetoothDevice.DEVICE_TYPE_DUAL ? "dual"
                : type == BluetoothDevice.DEVICE_TYPE_CLASSIC ? "classic" : "unknown";
    }

    private void refreshLinkInfoInternal(boolean autoSelect) {
        if (!connected || busy || infoOperation != INFO_NONE || session.isRecording()) return;
        selectAfterInfo = autoSelect;
        infoOperation = INFO_RSSI;
        infoStatus = "读取连接信息…";
        try {
            readDeviceInfo(gatt.getDevice());
            rssiStatus = "read_pending";
            publish();
            if (gatt.readRemoteRssi()) armInfoTimeout(INFO_RSSI);
            else { rssiStatus = "read_rejected"; readPhy(); }
        } catch (SecurityException error) { closeInternal("蓝牙连接权限已撤销"); }
        catch (IllegalStateException error) { rssiStatus = "read_failed"; finishInfo("连接信息读取失败"); }
    }

    private void readPhy() {
        cancelInfoTimeout();
        if (!connected || gatt == null) { finishInfo("连接已断开"); return; }
        infoOperation = INFO_PHY;
        phyStatus = "read_pending";
        publish();
        try { gatt.readPhy(); armInfoTimeout(INFO_PHY); }
        catch (SecurityException error) { closeInternal("蓝牙连接权限已撤销"); }
        catch (IllegalStateException error) { phyStatus = "read_failed"; finishInfo("PHY 读取失败"); }
    }

    private void armInfoTimeout(int operation) {
        cancelInfoTimeout();
        infoTimeout = () -> {
            if (infoOperation != operation) return;
            if (operation == INFO_RSSI) { rssiStatus = "timeout"; readPhy(); }
            else {
                if (operation == INFO_PHY) phyStatus = "timeout"; else mtuStatus = "timeout";
                finishInfo("部分参数未返回，保留最后确认值");
            }
        };
        worker.postDelayed(infoTimeout, 3000);
    }

    private void cancelInfoTimeout() {
        if (infoTimeout != null) worker.removeCallbacks(infoTimeout);
        infoTimeout = null;
    }

    private void finishInfo(String message) {
        cancelInfoTimeout();
        infoOperation = INFO_NONE;
        infoStatus = message;
        boolean selectNext = selectAfterInfo;
        selectAfterInfo = false;
        publish();
        if (selectNext && connected && !busy && channels.size() == 1) select(channels.get(0).key);
    }

    private void enableRequested() {
        disabling = false;
        if (requested == null || gatt == null) { closeInternal("数据通道已失效，请重新连接"); return; }
        try {
            if (!gatt.setCharacteristicNotification(requested.characteristic, true)) {
                subscriptionFailed("系统无法注册该数据通道");
                return;
            }
            byte[] value = requested.indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            writeCccd(requested.characteristic.getDescriptor(CCCD), value);
        } catch (SecurityException exception) {
            closeInternal("蓝牙连接权限已撤销");
        }
    }

    private void writeCccd(BluetoothGattDescriptor descriptor, byte[] value) {
        if (descriptor == null || gatt == null) { subscriptionFailed("没有标准 CCCD，无法开启通知"); return; }
        pendingDescriptor = descriptor;
        publish();
        try {
            boolean accepted;
            if (Build.VERSION.SDK_INT >= 33) {
                accepted = gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS;
            } else {
                descriptor.setValue(value);
                accepted = gatt.writeDescriptor(descriptor);
            }
            if (!accepted) { subscriptionFailed("订阅请求未被系统接受，请重试或重新连接"); return; }
            armTimeout("订阅响应超时，已关闭本 App 的连接，请重连");
        } catch (SecurityException exception) {
            closeInternal("蓝牙连接权限已撤销");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            subscriptionFailed("订阅失败：" + exception.getMessage());
        }
    }

    private void subscriptionFailed(String message) {
        cancelTimeout();
        if (disabling) { closeInternal("旧通道关闭失败；" + message); return; }
        if (gatt != null && requested != null) {
            try { gatt.setCharacteristicNotification(requested.characteristic, false); }
            catch (SecurityException ignored) { /* The permission may have been revoked. */ }
        }
        pendingDescriptor = null;
        selected = requested = null;
        ready = busy = false;
        status = message;
        publish();
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt source, int result, int newState) {
            if (source != gatt) return;
            if (result != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeInternal("GATT 已断开，状态码 " + result);
                return;
            }
            if (newState != BluetoothProfile.STATE_CONNECTED) return;
            if (connected) return;
            cancelTimeout();
            connected = true;
            connectedNs = SystemClock.elapsedRealtimeNanos();
            busy = true;
            status = "连接成功，正在自动发现服务与特征…";
            publish();
            try {
                if (!source.discoverServices()) { closeInternal("服务发现请求未被接受，请重连"); return; }
                armTimeout("服务发现超时，请重新连接");
            } catch (SecurityException exception) { closeInternal("蓝牙连接权限已撤销"); }
        }

        @Override public void onServicesDiscovered(BluetoothGatt source, int result) {
            if (source != gatt) return;
            cancelTimeout();
            if (result != BluetoothGatt.GATT_SUCCESS) { closeInternal("服务发现失败，状态码 " + result); return; }
            channels.clear();
            StringBuilder text = new StringBuilder();
            for (BluetoothGattService service : source.getServices()) {
                text.append("服务 ").append(service.getUuid()).append(" #").append(service.getInstanceId()).append('\n');
                for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                    int properties = characteristic.getProperties();
                    text.append("  特征 ").append(characteristic.getUuid()).append(" #")
                            .append(characteristic.getInstanceId()).append('\n')
                            .append("  ").append(propertiesText(properties)).append('\n');
                    boolean notify = (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                    boolean indicate = (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                    if (notify || indicate) {
                        if (characteristic.getDescriptor(CCCD) == null) text.append("  缺少 CCCD，不能订阅\n");
                        else channels.add(new Channel(characteristic, !notify && indicate));
                    }
                }
                text.append('\n');
            }
            services = text.length() == 0 ? "设备没有返回可见 GATT 服务" : text.toString();
            busy = false;
            status = channels.isEmpty() ? "无可用通道" : "发现 " + channels.size() + " 个通道";
            publish();
            refreshLinkInfoInternal(true);
        }

        @Override public void onMtuChanged(BluetoothGatt source, int value, int result) {
            if (source != gatt || !connected) return;
            boolean valid = result == BluetoothGatt.GATT_SUCCESS && value >= 23 && value <= 517;
            if (valid) {
                mtu = value; mtuNs = SystemClock.elapsedRealtimeNanos(); mtuStatus = "confirmed_callback";
            } else mtuStatus = result == BluetoothGatt.GATT_SUCCESS ? "invalid_callback_value" : "callback_failed_" + result;
            if (infoOperation == INFO_MTU) finishInfo(valid ? "MTU 已更新" : "MTU 协商失败，保留最后确认值");
            else publish();
        }

        @Override public void onReadRemoteRssi(BluetoothGatt source, int value, int result) {
            if (source != gatt || !connected) return;
            if (result == BluetoothGatt.GATT_SUCCESS && value >= -127 && value <= 20) {
                rssi = value; rssiNs = SystemClock.elapsedRealtimeNanos(); rssiStatus = "confirmed_callback";
            } else rssiStatus = result == BluetoothGatt.GATT_SUCCESS ? "invalid_callback_value" : "callback_failed_" + result;
            if (infoOperation == INFO_RSSI) readPhy(); else publish();
        }

        @Override public void onPhyRead(BluetoothGatt source, int tx, int rx, int result) {
            if (source != gatt || !connected) return;
            updatePhy(tx, rx, result, "onPhyRead");
            if (infoOperation == INFO_PHY) finishInfo("连接信息读取结束"); else publish();
        }

        @Override public void onPhyUpdate(BluetoothGatt source, int tx, int rx, int result) {
            if (source != gatt || !connected) return;
            updatePhy(tx, rx, result, "onPhyUpdate");
            publish();
        }

        @Override public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor descriptor, int result) {
            if (source != gatt || descriptor != pendingDescriptor) return;
            cancelTimeout();
            pendingDescriptor = null;
            if (result != BluetoothGatt.GATT_SUCCESS) {
                subscriptionFailed("订阅失败，GATT 状态码 " + result + "；可能需要系统配对或设备授权");
                return;
            }
            if (disabling) {
                try { source.setCharacteristicNotification(selected.characteristic, false); }
                catch (SecurityException exception) { closeInternal("蓝牙连接权限已撤销"); return; }
                selected = null;
                enableRequested();
            } else {
                selected = requested;
                requested = null;
                busy = false;
                ready = true;
                status = "已订阅 " + selected.mode + "，等待设备数据";
                publish();
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic, byte[] value) {
            long timestamp = SystemClock.elapsedRealtimeNanos();
            receive(source, characteristic, value, timestamp);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic) {
            long timestamp = SystemClock.elapsedRealtimeNanos();
            // Android 13+ uses the memory-safe overload above; never count both callbacks.
            if (Build.VERSION.SDK_INT < 33) {
                byte[] value = characteristic.getValue();
                receive(source, characteristic, value == null ? new byte[0] : value, timestamp);
            }
        }

        @Override public void onServiceChanged(BluetoothGatt source) {
            if (source == gatt) closeInternal("设备服务表发生变化，请重新连接以重新发现");
        }
    };

    private void receive(BluetoothGatt source, BluetoothGattCharacteristic characteristic, byte[] value, long timestamp) {
        if (source != gatt || !ready || selected == null || !selected.key.equals(keyOf(characteristic))) return;
        // Only the chosen characteristic contributes to the rate; no UI work or hex formatting here.
        session.accept(timestamp, value);
    }

    private void armTimeout(String message) {
        cancelTimeout();
        timeout = () -> closeInternal(message);
        worker.postDelayed(timeout, 15_000);
    }

    private void cancelTimeout() {
        if (timeout != null) worker.removeCallbacks(timeout);
        timeout = null;
    }

    private void closeInternal(String reason) {
        cancelTimeout();
        cancelInfoTimeout();
        infoOperation = INFO_NONE; selectAfterInfo = false;
        session.stop(reason, SystemClock.elapsedRealtimeNanos());
        BluetoothGatt old = gatt;
        gatt = null; // Invalidate stale callbacks before closing the old client.
        connected = busy = ready = disabling = false;
        pendingDescriptor = null;
        requested = null;
        if (old != null) {
            try { old.disconnect(); } catch (SecurityException ignored) { }
            try { old.close(); } catch (SecurityException ignored) { }
        }
        status = reason;
        infoStatus = "连接已关闭；以下为最后确认值";
        publish();
    }

    private void publish() {
        session.updateConnection(connectionMetadata(SystemClock.elapsedRealtimeNanos()));
        state = new State(deviceName, status, services, connectionDetails(), address, connected,
                busy || infoOperation != INFO_NONE, ready, selected,
                Collections.unmodifiableList(new ArrayList<>(channels)));
    }

    private void updatePhy(int tx, int rx, int result, String source) {
        if (result == BluetoothGatt.GATT_SUCCESS && tx > 0 && rx > 0) {
            txPhy = tx; rxPhy = rx; phyNs = SystemClock.elapsedRealtimeNanos(); phyStatus = "confirmed_callback";
            phySource = source;
        } else phyStatus = result == BluetoothGatt.GATT_SUCCESS ? "invalid_callback_value" : "callback_failed_" + result;
    }

    private static String phy(int value) {
        return value == BluetoothDevice.PHY_LE_1M ? "LE 1M" : value == BluetoothDevice.PHY_LE_2M ? "LE 2M"
                : value == BluetoothDevice.PHY_LE_CODED ? "LE Coded" : value == 0 ? "未读取" : "未知类型 " + value;
    }

    private String connectionDetails() {
        String bond = bondState.equals("bonded") ? "已配对" : bondState.equals("bonding") ? "配对中"
                : bondState.equals("not_bonded") ? "未配对" : "未知";
        return "本 App GATT：" + (connected ? "已连接" : "未连接") + " · " + bond + "\n类型：" + deviceType
                + "\nATT MTU：" + (mtu == 0 ? "未上报" : mtu + " 字节（回调确认）")
                + "\nPHY TX / RX：" + phy(txPhy) + " / " + phy(rxPhy)
                + "\nRSSI：" + (rssi == null ? "未读取" : rssi + " dBm（最近读取）")
                + "\nLL 长度 / 连接间隔 / 延迟 / 监督超时：当前接口不可获取"
                + "\n" + infoStatus;
    }

    private ConnectionMetadata connectionMetadata(long now) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("scope", "this_app_gatt_connection");
        values.put("device_name", simpleName); values.put("device_address", address);
        values.put("device_type", deviceType); values.put("transport", "LE"); values.put("bond_state", bondState);
        values.put("gatt_connected", Boolean.toString(connected)); values.put("connected_elapsed_realtime_ns", stamp(connectedNs));
        values.put("att_mtu_bytes", mtu == 0 ? "" : Integer.toString(mtu));
        values.put("att_mtu_status", mtuStatus); values.put("att_mtu_source", "onMtuChanged");
        values.put("att_mtu_observed_ns", stamp(mtuNs));
        values.put("att_notification_pdu_value_space_bytes", mtu == 0 ? "" : Integer.toString(mtu - 3));
        values.put("phy_tx", txPhy == 0 ? "" : phy(txPhy)); values.put("phy_rx", rxPhy == 0 ? "" : phy(rxPhy));
        values.put("phy_status", phyStatus); values.put("phy_source", phySource); values.put("phy_observed_ns", stamp(phyNs));
        values.put("rssi_dbm", rssi == null ? "" : rssi.toString()); values.put("rssi_status", rssiStatus);
        values.put("rssi_source", "onReadRemoteRssi"); values.put("rssi_observed_ns", stamp(rssiNs));
        values.put("ll_tx_max_octets", ""); values.put("ll_rx_max_octets", ""); values.put("ll_length_status", "unavailable_public_api_sdk35");
        values.put("connection_interval_ms", ""); values.put("peripheral_latency_events", ""); values.put("supervision_timeout_ms", "");
        values.put("connection_parameters_status", "unavailable_public_api_sdk35");
        values.put("encryption_status", "unknown_bond_state_is_not_encryption_state");
        values.put("service_uuid", selected == null ? "" : selected.characteristic.getService().getUuid().toString());
        values.put("service_instance", selected == null ? "" : Integer.toString(selected.characteristic.getService().getInstanceId()));
        values.put("characteristic_uuid", selected == null ? "" : selected.characteristic.getUuid().toString());
        values.put("characteristic_instance", selected == null ? "" : Integer.toString(selected.characteristic.getInstanceId()));
        values.put("characteristic_properties", selected == null ? "" : propertiesText(selected.characteristic.getProperties()));
        values.put("subscription", selected == null ? "" : selected.mode);
        return new ConnectionMetadata(now, values);
    }

    private static String stamp(long ns) { return ns < 0 ? "" : Long.toString(ns); }

    private static String keyOf(BluetoothGattCharacteristic characteristic) {
        BluetoothGattService service = characteristic.getService();
        if (service == null) return "";
        return service.getUuid() + "/" + service.getInstanceId() + "/"
                + characteristic.getUuid() + "/" + characteristic.getInstanceId();
    }

    private static String propertiesText(int properties) {
        StringBuilder text = new StringBuilder();
        if ((properties & BluetoothGattCharacteristic.PROPERTY_READ) != 0) text.append("Read ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) text.append("Write ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) text.append("WriteNoResponse ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) text.append("Notify ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) text.append("Indicate ");
        return text.length() == 0 ? "无常见读写/通知属性" : text.toString().trim();
    }

    static final class Channel {
        final String key, label, mode;
        final boolean indicate;
        private final BluetoothGattCharacteristic characteristic;
        Channel(BluetoothGattCharacteristic characteristic, boolean indicate) {
            this.characteristic = characteristic;
            this.indicate = indicate;
            this.key = keyOf(characteristic);
            this.mode = indicate ? "Indicate" : "Notify";
            this.label = mode + "\n服务 " + characteristic.getService().getUuid() + " #" + characteristic.getService().getInstanceId()
                    + "\n特征 " + characteristic.getUuid() + " #" + characteristic.getInstanceId();
        }
    }

    static final class State {
        final String deviceName, status, services, connectionDetails, address;
        final boolean connected, busy, ready;
        final Channel selected;
        final List<Channel> channels;
        State(String deviceName, String status, String services, String connectionDetails, String address, boolean connected, boolean busy, boolean ready,
              Channel selected, List<Channel> channels) {
            this.deviceName = deviceName; this.status = status; this.services = services;
            this.connectionDetails = connectionDetails; this.address = address;
            this.connected = connected; this.busy = busy; this.ready = ready; this.selected = selected; this.channels = channels;
        }
    }
}
