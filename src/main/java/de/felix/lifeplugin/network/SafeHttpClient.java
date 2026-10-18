package de.felix.lifeplugin.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;

/** Shared bounded HTTP transport. Synchronous methods belong on a worker thread. */
public final class SafeHttpClient {
    private final HttpClient client;
    private final Duration timeout;
    private final Set<String> allowedHosts;
    private final boolean allowLocalHttp;

    public SafeHttpClient(Duration timeout, Set<String> allowedHosts, boolean allowLocalHttp) {
        this(timeout, allowedHosts, allowLocalHttp, null);
    }

    SafeHttpClient(Duration timeout, Set<String> allowedHosts, boolean allowLocalHttp, SSLContext sslContext) {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("HTTP timeout must be between zero and two minutes");
        }
        this.timeout = timeout;
        this.allowLocalHttp = allowLocalHttp;
        HashSet<String> normalized = new HashSet<>();
        for (String host : allowedHosts) {
            if (host == null || host.isBlank() || host.contains("/") || host.contains("*") || host.contains(":")) {
                throw new IllegalArgumentException("Download hosts must be explicit DNS host names");
            }
            normalized.add(host.toLowerCase(Locale.ROOT));
        }
        this.allowedHosts = Set.copyOf(normalized);
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER);
        if (sslContext != null) builder.sslContext(sslContext);
        client = builder.build();
    }

    public byte[] get(URI uri, int maxBytes) throws IOException, InterruptedException {
        if (maxBytes <= 0 || maxBytes > 64 * 1024 * 1024) throw new IllegalArgumentException("Invalid download limit");
        URI current = uri;
        for (int redirects = 0; redirects <= 4; redirects++) {
            validate(current);
            HttpRequest request = HttpRequest.newBuilder(current).timeout(timeout)
                    .header("User-Agent", "LifeCore/2.1")
                    .header("Accept", "application/json, application/octet-stream;q=0.9")
                    .GET().build();
            CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, ignored -> new LimitedBody(maxBytes));
            HttpResponse<byte[]> response;
            try {
                // Covers the entire body, including a server that sends headers then stops sending bytes.
                response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                pending.cancel(true);
                throw new IOException("Remote request timed out", e);
            } catch (InterruptedException e) {
                pending.cancel(true);
                Thread.currentThread().interrupt();
                throw e;
            } catch (ExecutionException e) {
                throw new IOException("Remote request failed or exceeded its download limit", e.getCause());
            }
            int status = response.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                if (redirects == 4) throw new IOException("Too many redirects");
                String location = response.headers().firstValue("location")
                        .orElseThrow(() -> new IOException("Redirect has no location"));
                try {
                    URI next = current.resolve(location);
                    if ("https".equalsIgnoreCase(current.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())) {
                        throw new IOException("HTTPS redirect downgrade is forbidden");
                    }
                    current = next;
                }
                catch (IllegalArgumentException e) { throw new IOException("Invalid redirect destination", e); }
                continue;
            }
            if (status != 200) throw new HttpStatusException(status);
            return response.body();
        }
        throw new IOException("Too many redirects");
    }

    public void validate(URI uri) throws IOException {
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null) {
            throw new IOException("Remote URL must be absolute and must not contain credentials or fragments");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        if (allowLocalHttp && loopback && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) return;
        if (!"https".equalsIgnoreCase(uri.getScheme()) || loopback || !allowedHosts.contains(host)) {
            throw new IOException("Remote URL is outside the HTTPS download host allowlist");
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Download exceeds configured size limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
