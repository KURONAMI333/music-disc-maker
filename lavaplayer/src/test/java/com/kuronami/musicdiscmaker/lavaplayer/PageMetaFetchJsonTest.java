package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * 照会 API の取得作法を、ループバックの HTTP サーバーで確かめる。
 *
 * <p>見たいのは「リダイレクトを追わない」「2xx 以外を読まない」「大きすぎる本文を切る」
 * 「JSON 用の UA と Accept を名乗る」の 4 点。外部ネットワークへは出ない。
 */
class PageMetaFetchJsonTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aJsonBodyIsReturnedAsUtf8() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"title\":\"日本語\"}"));

        assertEquals("{\"title\":\"日本語\"}", PageMeta.fetchJson(url("/track"), 2_000));
    }

    @Test
    void sendsTheApiHeaders() throws Exception {
        final AtomicReference<String> userAgent = new AtomicReference<>();
        final AtomicReference<String> accept = new AtomicReference<>();
        start(exchange -> {
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            respond(exchange, 200, "{}");
        });

        PageMeta.fetchJson(url("/track"), 2_000);

        assertEquals("MusicDiscMaker/3.1.3", userAgent.get());
        assertEquals("application/json", accept.get());
    }

    @Test
    void aNonSuccessStatusIsNotRead() throws Exception {
        start(exchange -> respond(exchange, 404, "{\"title\":\"should not be used\"}"));

        assertThrows(IOException.class, () -> PageMeta.fetchJson(url("/track"), 2_000));
    }

    @Test
    void aRedirectIsNotFollowed() throws Exception {
        final AtomicInteger redirectTargetHits = new AtomicInteger();
        start(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/moved")) {
                redirectTargetHits.incrementAndGet();
                respond(exchange, 200, "{\"title\":\"elsewhere\"}");
                return;
            }
            exchange.getResponseHeaders().set("Location", url("/moved"));
            respond(exchange, 302, "");
        });

        assertThrows(IOException.class, () -> PageMeta.fetchJson(url("/track"), 2_000));
        assertEquals(0, redirectTargetHits.get(), "リダイレクト先を曲の情報として読んだ");
    }

    @Test
    void anOversizedBodyIsRejected() throws Exception {
        final byte[] body = new byte[PageMeta.MAX_JSON_BYTES + 1];
        java.util.Arrays.fill(body, (byte) 'x');
        start(exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        assertThrows(IOException.class, () -> PageMeta.fetchJson(url("/track"), 2_000));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", handler);
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
