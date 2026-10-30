package com.gamepad.rate;

import android.content.Context;
import android.os.SystemClock;

/** Application-owned receiver: an overlay never retains an Activity or its Views. */
final class PacketRuntime {
    private static PacketRuntime ble, usb;
    static synchronized PacketRuntime get(Context context, boolean usbMode) {
        if (usbMode) {
            if (usb == null) usb = new PacketRuntime(context, true);
            return usb;
        }
        if (ble == null) ble = new PacketRuntime(context, false);
        return ble;
    }
    final boolean usbMode;
    final PacketRateSession session = new PacketRateSession();
    final BleGattClient bleClient;
    final UsbHostClient usbClient;
    boolean visible, overlay;

    private PacketRuntime(Context context, boolean usbMode) {
        this.usbMode = usbMode;
        Context application = context.getApplicationContext();
        bleClient = usbMode ? null : new BleGattClient(application, session);
        usbClient = usbMode ? new UsbHostClient(application, session) : null;
    }
    boolean ready() { return usbMode ? usbClient.state().ready : bleClient.state().ready; }
    void focus(boolean focused) {
        if (usbMode) usbClient.foreground(focused || overlay);
        else bleClient.foreground(focused || overlay);
    }
    void pause() {
        visible = false;
        focus(false);
        if (!overlay) disconnect("离开前台，测试停止并释放连接");
    }
    void disconnect(String reason) {
        if (usbMode) usbClient.disconnect(reason); else bleClient.disconnect(reason);
    }
    void endOverlay() {
        overlay = false;
        if (usbMode) usbClient.stop("悬浮测试已关闭"); else bleClient.stop("悬浮测试已关闭");
        focus(visible);
        if (!visible) disconnect("悬浮测试已关闭，连接已释放");
    }
    PacketRateSession.ViewData data() { return session.view(SystemClock.elapsedRealtimeNanos()); }
}
