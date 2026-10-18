package de.felix.lifeplugin.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChatInputTest {
    @Test void onlyOneConcurrentChatCanClaimThePendingCallback() throws Exception {
        var request = new ChatInput.Pending(value -> { });
        var claims = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 32; index++) workers.submit(() -> {
                try { start.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
                if (request.claim()) claims.incrementAndGet();
            });
            start.countDown();
            workers.shutdown();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, claims.get());
    }

    @Test void replacingPromptMakesPreviouslyScheduledDeliveryHarmless() {
        var pending = new ChatInput.PendingInputs();
        UUID uuid = UUID.randomUUID();
        var oldRequest = new ChatInput.Pending(value -> { });
        var nextRequest = new ChatInput.Pending(value -> { });
        pending.replace(uuid, oldRequest);
        assertTrue(oldRequest.claim());
        assertSame(oldRequest, pending.replace(uuid, nextRequest));
        assertFalse(pending.remove(uuid, oldRequest));
        assertSame(nextRequest, pending.get(uuid));
        assertTrue(pending.remove(uuid, nextRequest));
        assertNull(pending.get(uuid));
    }

    @Test void quitAndShutdownInvalidateAlreadyClaimedRequests() {
        var pending = new ChatInput.PendingInputs();
        UUID uuid = UUID.randomUUID();
        var request = new ChatInput.Pending(value -> { });
        pending.replace(uuid, request);
        assertTrue(request.claim());
        assertSame(request, pending.remove(uuid));
        assertFalse(pending.remove(uuid, request));
        pending.replace(uuid, new ChatInput.Pending(value -> { }));
        pending.clear();
        assertNull(pending.get(uuid));
    }
}
