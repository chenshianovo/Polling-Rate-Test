package com.gamepad.rate;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateErrorsTest {
    @Test public void wrappedDnsFailureIsNotReportedAsLatestVersionOrTimeout() {
        String text = UpdateErrors.describe(new IOException("request failed", new UnknownHostException("api.github.com")));
        assertTrue(text.contains("域名"));
        assertFalse(text.contains("最新版本"));
        assertFalse(text.contains("超时"));
        assertFalse(text.contains("Unable to resolve"));
    }

    @Test public void timeoutTlsAndRateLimitHaveDifferentRecoveryMessages() {
        assertTrue(UpdateErrors.describe(new SocketTimeoutException()).contains("超时"));
        assertTrue(UpdateErrors.describe(new SSLHandshakeException("TLS failed")).contains("证书"));
        assertTrue(UpdateErrors.describe(new UpdateErrors.HttpFailure(429)).contains("限制访问"));
        assertTrue(UpdateErrors.describe(new UpdateErrors.HttpFailure(404)).contains("不存在"));
        assertEquals("安装包校验失败", UpdateErrors.describe(new IOException("安装包校验失败")));
    }
}
