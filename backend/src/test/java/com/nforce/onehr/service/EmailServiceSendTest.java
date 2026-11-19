package com.nforce.onehr.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Sends through the real EmailService to a local stand-in for Resend; nothing leaves the machine. */
class EmailServiceSendTest {

    private HttpServer server;
    private final EmailService service = new EmailService();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> keys = new CopyOnWriteArrayList<>();

    /** statuses.get(n) is the response to the n-th request; the last one repeats. */
    private void serve(int... statuses) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", ex -> {
            int n = calls.getAndIncrement();
            keys.add(ex.getRequestHeaders().getFirst("Idempotency-Key"));
            int status = statuses[Math.min(n, statuses.length - 1)];
            if (status == 429) ex.getResponseHeaders().add("Retry-After", "0");
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
        ReflectionTestUtils.setField(service, "resendUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/emails");
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "fromAddress", "OneHR <onehr@example.com>");
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private boolean send() {
        CompletableFuture<Boolean> f = ReflectionTestUtils.invokeMethod(service, "sendAsync", "a@x.com", null, "Subject", "<p>hi</p>");
        return f.join();
    }

    @Test
    @DisplayName("a 429 is retried once and the retry carries the same idempotency key")
    void retriesOnceOnRateLimit() throws Exception {
        serve(429, 200);
        assertThat(send()).isTrue();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(keys.get(0)).startsWith("onehr-").isEqualTo(keys.get(1));
    }

    @Test
    @DisplayName("a second 429 is final: only one retry, then the send is reported as failed")
    void givesUpAfterOneRetry() throws Exception {
        serve(429);
        assertThat(send()).isFalse();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("other refusals are not retried")
    void otherErrorsAreFinal() throws Exception {
        serve(422);
        assertThat(send()).isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }
}
