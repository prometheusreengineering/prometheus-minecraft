package studio.dreamys.prometheus.stage0;

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
 * GitHub's releases API and asset downloads on the loopback address. Never touches the network.
 */
public final class FakeGitHub implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    public final AtomicInteger releaseCalls = new AtomicInteger();
    public volatile int releaseStatus = 200;
    public volatile String releaseJson = "{}";

    public FakeGitHub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/repos/", exchange -> {
            releaseCalls.incrementAndGet();
            if (releaseStatus != 200) {
                exchange.sendResponseHeaders(releaseStatus, -1);
                exchange.close();
                return;
            }
            send(exchange, releaseJson.getBytes(StandardCharsets.UTF_8), false);
        });
        server.createContext("/download/", exchange -> {
            byte[] file = files.get(exchange.getRequestURI().getPath().substring("/download/".length()));
            if (file == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            send(exchange, file, false);
        });
        server.createContext("/chunked/", exchange -> send(exchange, files.get(exchange.getRequestURI().getPath().substring("/chunked/".length())), true));
        server.createContext("/truncated/", exchange -> {
            // promises more than it sends, then hangs up
            exchange.sendResponseHeaders(200, 1000);
            exchange.getResponseBody().write(new byte[10]);
            exchange.close();
        });
        server.start();
    }

    private static void send(HttpExchange exchange, byte[] body, boolean chunked) throws IOException {
        exchange.sendResponseHeaders(200, chunked ? 0 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    public String api() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Serves {@code jar} at {@code /<route>/<name>}, and makes it the only asset of the latest release.
     *
     * @param digest the asset's {@code digest}, or null for none (releases from before GitHub added it)
     */
    public void release(String tag, String name, byte[] jar, String digest, String route) {
        files.put(name, jar);
        releaseJson = "{\"tag_name\":\"" + tag + "\",\"assets\":[{\"name\":\"" + name + "\","
                + (digest == null ? "" : "\"digest\":\"" + digest + "\",")
                + "\"browser_download_url\":\"" + api() + "/" + route + "/" + name + "\"}]}";
    }

    public void release(String tag, String name, byte[] jar, String digest) {
        release(tag, name, jar, digest, "download");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
