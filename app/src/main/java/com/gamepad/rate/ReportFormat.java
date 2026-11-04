package com.gamepad.rate;

/** Explicit, channel-local framing rule; never guesses a device's private protocol. */
final class ReportFormat {
    static final byte DISABLED = 0, MATCHED = 1, WAITING = 2, RESYNCED = 3, EMPTY = 4, MATCHED_PENDING = 5;
    static final ReportFormat OFF = new ReportFormat(0, new byte[0]);
    final int bytesPerReport;
    private final byte[] prefix;

    private ReportFormat(int bytesPerReport, byte[] prefix) {
        this.bytesPerReport = bytesPerReport;
        this.prefix = prefix.clone();
    }

    static ReportFormat fixed(int bytesPerReport, String prefixHex) {
        if (bytesPerReport < 1 || bytesPerReport > 65535) {
            throw new IllegalArgumentException("每报告长度须为 1～65535 字节");
        }
        String compact = prefixHex.replaceAll("\\s+", "");
        if ((compact.length() & 1) != 0 || !compact.matches("[0-9a-fA-F]*")) {
            throw new IllegalArgumentException("报告头须为完整十六进制字节，例如 14 01");
        }
        if (compact.length() / 2 > bytesPerReport) throw new IllegalArgumentException("报告头不能长于一份报告");
        byte[] prefix = new byte[compact.length() / 2];
        for (int i = 0; i < prefix.length; i++) prefix[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
        return new ReportFormat(bytesPerReport, prefix);
    }

    boolean enabled() { return bytesPerReport != 0; }
    String prefixHex() { return prefix.length == 0 ? "" : PacketRateSession.hex(prefix); }
    byte[] prefixBytes() { return prefix.clone(); }

    static String statusText(byte status) {
        switch (status) {
            case MATCHED: return "已完成";
            case WAITING: return "等待拼接";
            case RESYNCED: return "已跳过失配字节";
            case EMPTY: return "空数据";
            case MATCHED_PENDING: return "已完成，余字节待拼";
            default: return "未开启";
        }
    }
}
