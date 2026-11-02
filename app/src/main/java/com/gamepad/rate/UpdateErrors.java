package com.gamepad.rate;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Keep raw diagnostics out of the main UI without pretending a DNS failure means no update. */
final class UpdateErrors {
    private UpdateErrors() {}

    static String describe(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof UnknownHostException)
                return "无法解析更新服务器域名。请切换网络，或检查手机的私人 DNS / VPN 后重试。";
            if (cause instanceof SocketTimeoutException) return "连接更新服务器超时，请稍后重试或切换网络。";
            if (cause instanceof ConnectException || cause instanceof NoRouteToHostException)
                return "无法连接更新服务器，请检查网络连接。";
            if (cause instanceof SSLException) return "安全连接验证失败，请检查系统时间和网络证书。";
            if (cause instanceof HttpFailure) {
                int status = ((HttpFailure) cause).status;
                if (status == 403 || status == 429) return "更新服务暂时限制访问（HTTP " + status + "），请稍后重试或打开发布页。";
                if (status == 404) return "更新文件或发布信息不存在（HTTP 404）。";
                return "更新服务器返回 HTTP " + status + "，请稍后重试。";
            }
        }
        // Validation failures in our downloader already have specific Chinese explanations.
        String message = error.getMessage();
        if (message != null && message.matches("(?s).*[\\u4e00-\\u9fff].*") && message.length() <= 180) return message;
        return "更新操作失败，请重试或打开发布页手动下载。";
    }

    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { super("HTTP " + status); this.status = status; }
    }
}
