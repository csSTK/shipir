package dev.bethge.shipir.ai.llm;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class LlmTransportTimeoutTest {

    @Test
    void connectTimeoutAndSocketTimeoutAreTimeouts() {
        assertThat(LlmException.isTimeout(new ConnectTimeoutException("connect timed out"))).isTrue();
        assertThat(LlmException.isTimeout(new SocketTimeoutException("read"))).isTrue();
        assertThat(LlmException.isTimeout(new IOException("wrapped", new ConnectTimeoutException("c")))).isTrue();
    }

    @Test
    void refusedAndOtherFailuresAreNotTimeouts() {
        assertThat(LlmException.isTimeout(new ConnectException("refused"))).isFalse();
        assertThat(LlmException.isTimeout(new IOException("boom"))).isFalse();
    }
}
