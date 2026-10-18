package de.felix.lifeplugin.network;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/** Real local HTTP server; production HTTP allowlists remain enforced separately. */
public final class HttpFixture implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    public HttpFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(Thread.ofVirtual().factory()));
        server.createContext("/", exchange -> {
            Response response = responses.getOrDefault(exchange.getRequestURI().getPath(), new Response(404, new byte[0], null));
            if (response.redirect != null) exchange.getResponseHeaders().set("Location", response.redirect);
            exchange.sendResponseHeaders(response.status, response.bytes.length == 0 ? -1 : response.bytes.length);
            if (response.bytes.length > 0) exchange.getResponseBody().write(response.bytes);
            exchange.close();
        });
        server.start();
    }
    public URI uri(String path) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path); }
    public void put(String path, String json) { put(path, json.getBytes(StandardCharsets.UTF_8)); }
    public void put(String path, byte[] bytes) { responses.put(path, new Response(200, bytes, null)); }
    public void status(String path, int status) { responses.put(path, new Response(status, new byte[0], null)); }
    public void redirect(String path, URI destination) { responses.put(path, new Response(302, new byte[0], destination.toString())); }
    @Override public void close() { server.stop(0); }
    private record Response(int status, byte[] bytes, String redirect) { }
}
