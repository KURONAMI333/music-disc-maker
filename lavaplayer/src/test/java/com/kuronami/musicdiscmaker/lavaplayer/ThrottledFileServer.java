package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 実ネットワークの代わりに使う、127.0.0.1 だけで完結する HTTP サーバ。
 *
 * <p>ローカルの loopback は転送が事実上瞬時なので、素の配信では「cold は遅い」を再現できない
 * (実機の 5〜10 秒無音は、ネットワーク越しのダウンロードが再生速度に追いつかない時間そのもの)。
 * ここでは配信レートを意図的に「等速 = 再生に必要な秒数ぶんだけ転送にも同じ秒数かかる」に絞る。
 * これは実測したネットワーク値ではなく、<b>再現性のために選んだ合成モデル</b> — 「ダウンロードが
 * 再生に追いつくかどうかギリギリ」という、無音が起きる典型的な条件の下限を模している。
 */
final class ThrottledFileServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final int port;

    /**
     * @param file       配信するファイル
     * @param bytesPerSec 配信レート (byte/sec)。{@code <= 0} なら無制限 (スロットル無し)。
     */
    ThrottledFileServer(Path file, long bytesPerSec) throws IOException {
        final byte[] content = Files.readAllBytes(file);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/track.wav", exchange -> serve(exchange, content, bytesPerSec));
        server.start();
        this.port = server.getAddress().getPort();
    }

    private static void serve(HttpExchange exchange, byte[] content, long bytesPerSec) throws IOException {
        try {
            exchange.getResponseHeaders().add("Content-Type", "audio/wav");
            exchange.getResponseHeaders().add("Accept-Ranges", "none");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                if (bytesPerSec <= 0) {
                    os.write(content);
                    return;
                }
                final int chunk = 4096;
                final long startNanos = System.nanoTime();
                long sent = 0;
                while (sent < content.length) {
                    final int n = (int) Math.min(chunk, content.length - sent);
                    os.write(content, (int) sent, n);
                    sent += n;
                    // このチャンクまで送り終わっているべき時刻まで待つ (等速配信)。
                    final long dueNanos = startNanos + (sent * 1_000_000_000L) / bytesPerSec;
                    final long waitNanos = dueNanos - System.nanoTime();
                    if (waitNanos > 0) {
                        try {
                            Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
                        } catch (final InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        } finally {
            exchange.close();
        }
    }

    String url() {
        return "http://127.0.0.1:" + port + "/track.wav";
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
