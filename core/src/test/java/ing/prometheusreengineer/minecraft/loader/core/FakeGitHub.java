package ing.prometheusreengineer.minecraft.loader.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GitHub's releases API, release asset downloads and raw patches.json on the loopback address. A repo without a
 * release answers 404, like GitHub.
 */
final class FakeGitHub implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, String> releases = new ConcurrentHashMap<>();
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final AtomicInteger releaseCalls = new AtomicInteger();
    volatile int releaseStatus = 200;
    volatile String patchesJson;

    FakeGitHub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/repos/", exchange -> {
            releaseCalls.incrementAndGet();
            // /repos/<owner>/<repo>/releases/latest
            String repo = exchange.getRequestURI().getPath().split("/")[3];
            String release = releases.get(repo);
            if (releaseStatus != 200 || release == null) {
                empty(exchange, releaseStatus != 200 ? releaseStatus : 404);
                return;
            }
            send(exchange, release.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/download/", exchange -> send(exchange, files.get(exchange.getRequestURI().getPath().substring("/download/".length()))));
        server.createContext("/truncated/", exchange -> {
            exchange.sendResponseHeaders(200, 1000);
            exchange.getResponseBody().write(new byte[10]);
            exchange.close();
        });
        server.createContext("/patches.json", exchange -> {
            if (patchesJson == null) {
                empty(exchange, 404);
                return;
            }
            send(exchange, patchesJson.getBytes(StandardCharsets.UTF_8));
        });
        server.start();
    }

    private static void empty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void send(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    String api() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * {@code repo}'s latest release has one asset, {@code name}, served from {@code /<route>/<name>}.
     *
     * @param digest the asset's {@code digest}, or null for none
     */
    void release(String repo, String tag, String name, byte[] jar, String digest, String route) {
        files.put(name, jar);
        releases.put(repo, "{\"tag_name\":\"" + tag + "\",\"assets\":[{\"name\":\"" + name + "\","
                + (digest == null ? "" : "\"digest\":\"" + digest + "\",")
                + "\"browser_download_url\":\"" + api() + "/" + route + "/" + name + "\"}]}");
    }

    void release(String repo, String tag, String name, byte[] jar, String digest) {
        release(repo, tag, name, jar, digest, "download");
    }

    void releaseJson(String repo, String json) {
        releases.put(repo, json);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
