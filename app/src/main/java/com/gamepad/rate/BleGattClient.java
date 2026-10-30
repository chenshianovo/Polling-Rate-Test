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
    private String status = "点击选择蓝牙设备，自动获取服务与特征";
    private String services = "连接后自动发现";
    private Runnable timeout;
    private volatile State state;
    private volatile boolean foreground;

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
        session.stop(reason, SystemClock.elapsedRealtimeNanos());
        // Also stop after any already-queued start request.
        worker.post(() -> session.stop(reason, SystemClock.elapsedRealtimeNanos()));
    }

    void connect(BluetoothDevice device) {
        worker.post(() -> {
            closeInternal("切换设备，测试停止");
            channels.clear();
            selected = requested = null;
            services = "正在连接，稍后自动发现服务";
            session.channelChanged();
            try {
                String name = device.getName();
                deviceName = (name == null || name.isEmpty() ? "未命名设备" : name) + "\n" + device.getAddress();
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
            if (!connected || busy) return;
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
        worker.post(() -> {
            if (!foreground || !ready || selected == null || busy || !connected || session.isRecording()) return;
            session.start(SystemClock.elapsedRealtimeNanos(), seconds, deviceName, selected.label,
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date()));
        });
    }

    void disconnect(String reason) {
        // Stop recording immediately, even if the worker has callbacks queued.
        session.stop(reason, SystemClock.elapsedRealtimeNanos());
        worker.post(() -> closeInternal(reason));
    }

    void destroy() {
        session.stop("页面关闭", SystemClock.elapsedRealtimeNanos());
        worker.post(() -> {
            closeInternal("页面关闭");
            thread.quitSafely();
        });
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
            status = channels.isEmpty() ? "未找到可订阅通道；可查看下方服务信息" : "已自动发现 " + channels.size() + " 个可接收通道，请选择";
            publish();
            if (channels.size() == 1) select(channels.get(0).key);
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
        publish();
    }

    private void publish() {
        state = new State(deviceName, status, services, connected, busy, ready, selected,
                Collections.unmodifiableList(new ArrayList<>(channels)));
    }

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
        final String deviceName, status, services;
        final boolean connected, busy, ready;
        final Channel selected;
        final List<Channel> channels;
        State(String deviceName, String status, String services, boolean connected, boolean busy, boolean ready,
              Channel selected, List<Channel> channels) {
            this.deviceName = deviceName; this.status = status; this.services = services;
            this.connected = connected; this.busy = busy; this.ready = ready; this.selected = selected; this.channels = channels;
        }
    }
}
