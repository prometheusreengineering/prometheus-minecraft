package ing.prometheusreengineer.minecraft.loader.stage0;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GitHub's {@code releases/latest/download/<asset>} on the loopback address, redirects included. Never touches the
 * network.
 */
public final class FakeGitHub implements AutoCloseable {
    private static final String JAR = "/latest/prometheus-core.jar";

    private final HttpServer server;
    public final AtomicInteger hashCalls = new AtomicInteger();
    public final AtomicInteger jarCalls = new AtomicInteger();
    public volatile int status = 200;
    public volatile String hash = "";
    public volatile byte[] jar = new byte[0];
    /**
     * How the jar is sent: {@code whole}, {@code chunked} (no length) or {@code truncated}.
     */
    public volatile String route = "whole";

    public FakeGitHub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(JAR + ".sha256", exchange -> {
            hashCalls.incrementAndGet();
            redirectTo(exchange, "/asset/hash");
        });
        server.createContext(JAR, exchange -> {
            jarCalls.incrementAndGet();
            redirectTo(exchange, "/asset/jar");
        });
        server.createContext("/asset/hash", exchange -> {
            if (status != 200) refuse(exchange);
            else send(exchange, hash.getBytes(StandardCharsets.UTF_8), false);
        });
        server.createContext("/asset/jar", exchange -> {
            if (status != 200) refuse(exchange);
            else if (route.equals("truncated")) truncated(exchange);
            else send(exchange, jar, route.equals("chunked"));
        });
        server.start();
    }

    private static void redirectTo(HttpExchange exchange, String path) throws IOException {
        exchange.getResponseHeaders().set("Location", path);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void refuse(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    /**
     * Promises more than it sends, then hangs up.
     */
    private static void truncated(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, 1000);
        exchange.getResponseBody().write(new byte[10]);
        exchange.close();
    }

    private static void send(HttpExchange exchange, byte[] body, boolean chunked) throws IOException {
        exchange.sendResponseHeaders(200, chunked ? 0 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + JAR;
    }

    /**
     * Makes {@code jar} the latest release's core, published with {@code hash}.
     */
    public void release(byte[] jar, String hash) {
        this.jar = jar;
        this.hash = hash;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
