package com.gamepad.rate;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/** Reads only a user-selected interrupt IN endpoint. All native USB work stays off the UI. */
final class UsbHostClient {
    private final UsbManager manager;
    private final PacketRateSession session;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();
    private Run active;
    private boolean foreground;
    private volatile State state = new State("尚未选择 USB 设备", "", "通过 OTG 连接手柄后选择设备", false, false);

    UsbHostClient(Context context, PacketRateSession session) {
        manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.session = session;
    }
    State state() { return state; }

    static List<Channel> channels(UsbDevice device) {
        List<Channel> result = new ArrayList<>();
        // Android exposes the first configuration through getInterface; do not switch configurations.
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getAlternateSetting() != 0) continue;
            for (int j = 0; j < intf.getEndpointCount(); j++) {
                UsbEndpoint ep = intf.getEndpoint(j);
                int packet = ep.getMaxPacketSize();
                if (ep.getDirection() == UsbConstants.USB_DIR_IN && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT
                        && packet > 0 && packet <= 1024) result.add(new Channel(intf, ep));
            }
        }
        return result;
    }

    void connect(UsbDevice device, Channel channel) {
        synchronized (lock) {
            cancelLocked("切换 USB 设备 / 端点");
            session.channelChanged();
            Run run = new Run(device, channel);
            active = run;
            state = new State(run.label, channel.label, "正在打开 USB 端点…", false, true);
            worker.execute(() -> read(run));
        }
    }

    void start(int seconds) {
        synchronized (lock) {
            if (!foreground || active == null || !state.ready) return;
            session.start(SystemClock.elapsedRealtimeNanos(), seconds, state.device, state.channel,
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date()));
        }
    }
    void stop(String reason) { session.stop(reason, SystemClock.elapsedRealtimeNanos()); }
    void foreground(boolean enabled) {
        synchronized (lock) {
            foreground = enabled;
            if (!enabled) stop("窗口失去焦点，测试已停止");
        }
    }
    void disconnect(String reason) {
        synchronized (lock) {
            cancelLocked(reason);
            state = new State(state.device, state.channel, reason, false, false);
        }
    }
    void detached(UsbDevice device) {
        synchronized (lock) {
            if (active != null && active.device.getDeviceName().equals(device.getDeviceName())) disconnect("USB 设备已拔出，测试停止");
        }
    }
    void destroy() { disconnect("页面已关闭"); worker.shutdown(); }

    private void cancelLocked(String reason) {
        if (active != null) active.cancelled = true;
        active = null;
        stop(reason);
        // requestWait has a 200 ms timeout. The worker cancels requests and releases the interface.
    }

    private void read(Run run) {
        UsbDeviceConnection connection = null;
        List<UsbRequest> requests = new ArrayList<>();
        boolean claimed = false;
        String outcome = "USB 连接已关闭";
        try {
            if (run.cancelled) return;
            if (!manager.hasPermission(run.device)) throw new IllegalStateException("USB 访问未授权，请重新选择设备");
            connection = manager.openDevice(run.device);
            if (connection == null) throw new IllegalStateException("无法打开 USB 设备，请检查授权、连接及 OTG 供电");
            byte[] configuration = new byte[1];
            int configRead = connection.controlTransfer(0x80, 8, 0, 0, configuration, 1, 1000);
            if (configRead != 1 || (configuration[0] & 255) != run.device.getConfiguration(0).getId())
                throw new IllegalStateException("无法确认默认 USB 配置，未改变设备配置；暂不支持此设备");
            // Explicit UI disclosure precedes this action: it can detach Android's HID driver.
            claimed = connection.claimInterface(run.channel.intf, true);
            if (!claimed) throw new IllegalStateException("接口被占用或系统不允许接管，请关闭其他 USB 应用");
            // Queue a small pipeline so re-queuing latency need not leave the endpoint idle.
            for (int i = 0; i < 4; i++) {
                UsbRequest request = new UsbRequest();
                requests.add(request);
                if (!request.initialize(connection, run.channel.endpoint)) throw new IllegalStateException("USB 请求初始化失败");
                ByteBuffer buffer = ByteBuffer.allocateDirect(run.channel.endpoint.getMaxPacketSize());
                request.setClientData(buffer);
                if (!request.queue(buffer)) throw new IllegalStateException("USB 接收请求提交失败");
            }
            synchronized (lock) {
                if (active != run || run.cancelled) return;
                state = new State(run.label, run.channel.label, "已连接 · 等待 Interrupt IN 数据", true, false);
            }
            while (!run.cancelled) {
                UsbRequest completed;
                try { completed = connection.requestWait(200); }
                catch (TimeoutException idle) {
                    if (!manager.hasPermission(run.device) || !manager.getDeviceList().containsKey(run.device.getDeviceName()))
                        throw new IllegalStateException("设备已拔出或 USB 授权已失效");
                    continue; // No report is not a zero-byte report.
                }
                long timestamp = SystemClock.elapsedRealtimeNanos();
                if (completed == null || !requests.contains(completed)) throw new IllegalStateException("USB 接收终止，请检查设备是否断开");
                ByteBuffer buffer = (ByteBuffer) completed.getClientData();
                int length = buffer.position();
                if (length > 0) {
                    byte[] value = new byte[length];
                    buffer.flip();
                    buffer.get(value);
                    synchronized (lock) {
                        if (active == run && !run.cancelled) session.accept(timestamp, value);
                    }
                }
                buffer.clear();
                if (!run.cancelled && !completed.queue(buffer)) throw new IllegalStateException("USB 后续接收请求失败");
            }
        } catch (RuntimeException exception) {
            outcome = "USB：" + (exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        } finally {
            for (UsbRequest request : requests) {
                try { request.cancel(); } catch (RuntimeException ignored) { /* Already detached. */ }
            }
            // Drain cancelled URBs before closing their buffers; teardown is on this same worker.
            if (connection != null) {
                for (int i = 0; i < requests.size(); i++) {
                    try { if (connection.requestWait(20) == null) break; }
                    catch (TimeoutException | RuntimeException finished) { break; }
                }
            }
            for (UsbRequest request : requests) {
                try { request.close(); } catch (RuntimeException ignored) { /* Finish all cleanup even after detach. */ }
            }
            if (connection != null) {
                try { if (claimed) connection.releaseInterface(run.channel.intf); }
                catch (RuntimeException ignored) { /* Device may already be gone. */ }
                finally { connection.close(); }
            }
            synchronized (lock) {
                if (active == run) {
                    active = null;
                    stop(outcome);
                    state = new State(run.label, run.channel.label, outcome, false, false);
                }
            }
        }
    }

    static String label(UsbDevice device) {
        String name = device.getProductName();
        return (name == null ? "USB 设备" : name) + String.format(Locale.US, " · VID %04X PID %04X", device.getVendorId(), device.getProductId());
    }
    static final class Channel {
        final UsbInterface intf;
        final UsbEndpoint endpoint;
        final String label;
        Channel(UsbInterface intf, UsbEndpoint endpoint) {
            this.intf = intf; this.endpoint = endpoint;
            label = String.format(Locale.US, "接口 %d / Alt %d · 类 %02X · IN 0x%02X\n单次接收上限 %d 字节 · bInterval=%d（原始值）",
                    intf.getId(), intf.getAlternateSetting(), intf.getInterfaceClass(), endpoint.getAddress(), endpoint.getMaxPacketSize(), endpoint.getInterval());
        }
    }
    static final class State {
        final String device, channel, status;
        final boolean ready, busy;
        State(String device, String channel, String status, boolean ready, boolean busy) {
            this.device = device; this.channel = channel; this.status = status; this.ready = ready; this.busy = busy;
        }
    }
    private static final class Run {
        final UsbDevice device;
        final Channel channel;
        final String label;
        volatile boolean cancelled;
        Run(UsbDevice device, Channel channel) { this.device = device; this.channel = channel; label = UsbHostClient.label(device); }
    }
}
