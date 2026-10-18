package de.felix.lifeplugin.network;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SafeHttpClientTest {
    private final SafeHttpClient http = new SafeHttpClient(Duration.ofSeconds(3), Set.of("github.com"), true);

    @Test void boundedHttpAndRedirects() throws Exception {
        try (HttpFixture fixture = new HttpFixture()) {
            fixture.put("/ok", "hello");
            fixture.redirect("/redirect", fixture.uri("/ok"));
            assertEquals("hello", new String(http.get(fixture.uri("/redirect"), 5), StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> http.get(fixture.uri("/ok"), 4));
            fixture.redirect("/outside", URI.create("https://evil.invalid/file"));
            assertThrows(IOException.class, () -> http.get(fixture.uri("/outside"), 10));
            fixture.redirect("/loop", fixture.uri("/loop"));
            assertThrows(IOException.class, () -> http.get(fixture.uri("/loop"), 10));
            HttpStatusException status = assertThrows(HttpStatusException.class, () -> http.get(fixture.uri("/missing"), 10));
            assertEquals(404, status.statusCode());
        }
    }

    @Test void productionPolicyRejectsPlainHttpCredentialsAndUnlistedHosts() {
        SafeHttpClient production = new SafeHttpClient(Duration.ofSeconds(2), Set.of("github.com"), false);
        for (String value : new String[] {"http://github.com/a", "https://user:password@github.com/a", "https://127.0.0.1/a", "https://evil.invalid/a", "https://github.com/a#fragment"}) {
            assertThrows(IOException.class, () -> production.validate(URI.create(value)));
        }
        assertDoesNotThrow(() -> production.validate(URI.create("https://github.com/Felixwrn/lifecore")));
    }

    @Test void verifiesTlsOnSafeRedirectAndRejectsDowngrade() throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = getClass().getResourceAsStream("/network/localhost.p12")) { keys.load(input, "fixture-password".toCharArray()); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, "fixture-password".toCharArray());
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        HttpsServer fixture = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.setHttpsConfigurator(new HttpsConfigurator(context));
        URI base = URI.create("https://127.0.0.1:" + fixture.getAddress().getPort());
        fixture.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", base.resolve("/ok").toString());
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        fixture.createContext("/downgrade", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/forbidden");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        fixture.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write(new byte[] { 'o', 'k' }); exchange.close();
        });
        fixture.start();
        try {
            SafeHttpClient trusted = new SafeHttpClient(Duration.ofSeconds(3), Set.of(), true, context);
            assertArrayEquals(new byte[] { 'o', 'k' }, trusted.get(base.resolve("/redirect"), 2));
            assertThrows(IOException.class, () -> trusted.get(base.resolve("/downgrade"), 10));
            assertThrows(IOException.class, () -> http.get(base.resolve("/ok"), 10));
        } finally { fixture.stop(0); }
    }

    @Test void strictJsonRejectsDuplicateKeysAndInvalidUtf8() {
        assertThrows(IOException.class, () -> ContentChecks.object("{\"schemaVersion\":1,\"schemaVersion\":2}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> ContentChecks.object("{'schemaVersion':1}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> ContentChecks.object(new byte[] { (byte)0xff }));
        assertThrows(IOException.class, () -> ContentChecks.hash(""));
    }
}
