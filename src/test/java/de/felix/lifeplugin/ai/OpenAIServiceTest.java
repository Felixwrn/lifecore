package de.felix.lifeplugin.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.felix.lifeplugin.mode.ModeCodec;
import de.felix.lifeplugin.mode.ModeDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class OpenAIServiceTest {
    private static final String KEY = "sk-fixture-only-never-real";
    private HttpServer server;
    private ExecutorService executor;
    private URI endpoint;
    private Handler handler;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach void startFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        handler = exchange -> reply(exchange, 200, envelope(ModeCodec.toJson(mode()).toString(), "stop"));
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            try { handler.handle(exchange); }
            catch (Exception exception) { exchange.close(); }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
    }

    @AfterEach void stopFixture() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test void sendsStrictStructuredRequestAndReturnsValidatedPreviewOnly() throws Exception {
        AtomicReference<JsonObject> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        handler = exchange -> {
            body.set(ModeCodec.parseJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            reply(exchange, 200, envelope(ModeCodec.toJson(mode()).toString(), "stop"));
        };
        assertEquals(mode(), service().createMode("duel", "Create a fair duel with three lives"));
        assertEquals("POST", method.get());
        assertEquals("Bearer " + KEY, authorization.get());
        assertEquals("gpt-4.1-mini", body.get().get("model").getAsString());
        JsonObject format = body.get().getAsJsonObject("response_format");
        assertEquals("json_schema", format.get("type").getAsString());
        JsonObject schema = format.getAsJsonObject("json_schema");
        assertTrue(schema.get("strict").getAsBoolean());
        JsonObject definition = schema.getAsJsonObject("schema");
        assertFalse(definition.get("additionalProperties").getAsBoolean());
        assertEquals(ModeCodec.FIELDS, definition.getAsJsonObject("properties").keySet());
        assertEquals(ModeCodec.FIELDS.size(), definition.getAsJsonArray("required").size());
        assertEquals("duel", definition.getAsJsonObject("properties").getAsJsonObject("id")
                .getAsJsonArray("enum").get(0).getAsString());
        assertEquals(1, requests.get());
    }

    @ParameterizedTest @MethodSource("invalidResponses")
    void rejectsInvalidResponsesWithoutIncludingProviderContent(String body) throws Exception {
        handler = exchange -> reply(exchange, 200, body);
        IOException exception = assertThrows(IOException.class, () -> service().createMode("duel", "A duel"));
        assertTrue(exception.getMessage().contains("invalid or unsupported"));
        assertNull(exception.getCause());
        assertFalse(exception.toString().contains(KEY));
    }

    static Stream<String> invalidResponses() {
        JsonObject wrongId = ModeCodec.toJson(mode());
        wrongId.addProperty("id", "someone-else");
        JsonObject executable = ModeCodec.toJson(mode());
        executable.addProperty("command", "op " + KEY);
        JsonObject wrongType = ModeCodec.toJson(mode());
        wrongType.addProperty("max-lives", "20");
        JsonObject invalidIcon = ModeCodec.toJson(mode());
        invalidIcon.addProperty("icon", "AIR");
        JsonObject refusal = ModeCodec.parseJson(envelope("", "stop"));
        refusal.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message")
                .addProperty("refusal", "provider text " + KEY);
        return Stream.of("{bad-json " + KEY, "{}", envelope("not json " + KEY, "stop"),
                envelope(ModeCodec.toJson(mode()).toString(), "length"), envelope(wrongId.toString(), "stop"),
                envelope(executable.toString(), "stop"), envelope(wrongType.toString(), "stop"),
                envelope(invalidIcon.toString(), "stop"), refusal.toString(),
                envelope(ModeCodec.toJson(mode()).toString().replace("\"id\":\"duel\"", "\"id\":\"duel\",\"id\":\"duel\""), "stop"));
    }

    @Test void reportsHttpStatusWithoutReturningProviderSecrets() {
        handler = exchange -> reply(exchange, 401, "provider echo: " + KEY);
        IOException exception = assertThrows(IOException.class, () -> service().createMode("duel", "A duel"));
        assertEquals("AI provider returned HTTP 401", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test void doesNotFollowRedirectOrForwardAuthorization() {
        AtomicInteger destinationRequests = new AtomicInteger();
        server.createContext("/redirected", exchange -> {
            destinationRequests.incrementAndGet();
            reply(exchange, 200, envelope(ModeCodec.toJson(mode()).toString(), "stop"));
        });
        handler = exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirected");
            reply(exchange, 307, "");
        };
        IOException exception = assertThrows(IOException.class, () -> service().createMode("duel", "A duel"));
        assertEquals("AI provider returned HTTP 307", exception.getMessage());
        assertEquals(0, destinationRequests.get());
    }

    @Test void missingOrMalformedKeyNeverMakesARequest() {
        for (String key : new String[]{null, "", " ", "key\r\nInjected: true"}) {
            OpenAIService service = new OpenAIService(key, "gpt-4.1-mini", endpoint, Duration.ofSeconds(2), true);
            IOException exception = assertThrows(IOException.class, () -> service.createMode("duel", "A duel"));
            assertTrue(exception.getMessage().contains("API key"));
        }
        assertEquals(0, requests.get());
    }

    @Test void boundsResponseBytes() {
        handler = exchange -> reply(exchange, 200, "x".repeat(140_000));
        IOException exception = assertThrows(IOException.class, () -> service().createMode("duel", "A duel"));
        assertTrue(exception.getMessage().contains("response limit"));
        assertNull(exception.getCause());
    }

    @Test void timesOutEvenWhenServerSentHeadersAndStallsBody() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch headersSent = new CountDownLatch(1);
        handler = exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            headersSent.countDown();
            release.await(3, TimeUnit.SECONDS);
            exchange.close();
        };
        long started = System.nanoTime();
        try {
            OpenAIService service = new OpenAIService(KEY, "gpt-4.1-mini", endpoint, Duration.ofMillis(350), true);
            assertThrows(IOException.class, () -> service.createMode("duel", "A duel"));
            assertTrue(headersSent.await(1, TimeUnit.SECONDS), "The fixture must send headers before stalling");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
        } finally { release.countDown(); }
    }

    @Test void rejectsInvalidInputsAndInsecureProductionEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> new OpenAIService(KEY, "gpt-4.1-mini", endpoint, Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new OpenAIService(KEY, "gpt-4.1-mini",
                URI.create("https://user:pass@example.com/chat"), Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new OpenAIService(KEY, "gpt-4.1-mini",
                URI.create("https://example.com/chat?key=secret"), Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> service().createMode("../outside", "A duel"));
        assertThrows(IllegalArgumentException.class, () -> service().createMode("duel", " "));
        assertThrows(IllegalArgumentException.class, () -> service().createMode("duel", "x".repeat(8193)));
        assertThrows(IllegalArgumentException.class, () -> service().createMode("duel", "bad\u0000text"));
        assertEquals(0, requests.get());
    }

    private OpenAIService service() {
        return new OpenAIService(KEY, "gpt-4.1-mini", endpoint, Duration.ofSeconds(2), true);
    }

    private static ModeDefinition mode() {
        return new ModeDefinition("duel", "Fair Duel", "Three lives per player", "DIAMOND", 3, 20, true, true, 1, false);
    }

    private static String envelope(String mode, String finishReason) {
        JsonObject root = new JsonObject();
        JsonArray choices = new JsonArray();
        JsonObject choice = new JsonObject();
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", mode);
        choice.add("message", message);
        choice.addProperty("finish_reason", finishReason);
        choices.add(choice);
        root.add("choices", choices);
        return root.toString();
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
        exchange.close();
    }

    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws Exception; }
}
