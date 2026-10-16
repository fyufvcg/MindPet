package service;

import com.sun.net.httpserver.HttpServer;
import config.EmbeddingConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import util.Logger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EmbeddingReadinessTest {
    @TempDir Path root;
    HttpServer server;
    AtomicInteger cloudCalls = new AtomicInteger(), localCalls = new AtomicInteger();
    int cloudStatus = 200;
    String vector = vector(1024);
    String base;
    EmbeddingConfig config;
    @BeforeEach void setup() throws Exception {
        config = new EmbeddingConfig(root.resolve("embedding.json"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cloud", exchange -> {
            cloudCalls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] body = (cloudStatus == 200 ? "{\"data\":{\"embedding\":" + vector + "}}"
                : "{\"error\":\"fixture-secret\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(cloudStatus, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/local", exchange -> {
            localCalls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(404, -1); exchange.close();
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }
    EmbeddingService service(String mode, String key) {
        config.update(mode, key, base + "/cloud", "fixture-model");
        return new EmbeddingService(base + "/local", "bge-m3", "30m", base + "/cloud",
            "fixture-model", "static-credential", config, mock(Logger.class));
    }
    static String vector(int size) { return IntStream.range(0,size).mapToObj(i -> "0.25").collect(Collectors.joining(",", "[", "]")); }

    @Test void forcedCloudValidatesRealVectorWithoutAnyOllamaRequest() {
        var service = service("DOUBAO", "fixture-secret");
        assertEquals("doubao", service.getActiveProvider());
        assertEquals(0, localCalls.get()); assertEquals(1, cloudCalls.get());
        assertEquals(true, service.status().get("available"));
        assertEquals(1024, service.status().get("dimension"));
        service.configure(false);
        assertEquals(1, cloudCalls.get(), "An unchanged sync must not repeat a paid probe");
        service.refresh(); assertEquals(2, cloudCalls.get());
    }
    @Test void invalidCredentialIsConfiguredButUnavailableAndDoesNotLeakResponse() {
        cloudStatus = 401;
        var service = service("DOUBAO", "fixture-secret");
        assertNull(service.getActiveProvider());
        assertEquals(EmbeddingService.Reason.DOUBAO_REQUEST_FAILED, service.getReason());
        assertTrue(service.hint().contains("401"));
        assertFalse(service.status().toString().contains("fixture-secret"));
    }
    @Test void wrongDimensionAndNonNumericValuesNeverCountAsReady() {
        vector = vector(8);
        var service = service("DOUBAO", "fixture-secret");
        assertEquals(EmbeddingService.Reason.INVALID_VECTOR, service.getReason());
        assertNull(service.getActiveProvider());
        vector = "[\"bad\"," + vector(1023).substring(1);
        service.refresh(); assertFalse((boolean)service.status().get("available"));
    }
    @Test void clearingRuntimeKeyDoesNotFallBackToStaticCredential() {
        var service = service("DOUBAO", "fixture-secret");
        service.applyConfiguration("DOUBAO", "", base + "/cloud", "fixture-model", true);
        assertEquals(EmbeddingService.Reason.DOUBAO_KEY_MISSING, service.getReason());
        assertEquals(1, cloudCalls.get()); assertFalse(service.hasDoubaoApiKey());
    }
    @Test void autoFallbackAlsoValidatesCloudAndRecoversOnExplicitCheck() {
        cloudStatus = 403;
        var service = service("AUTO", "fixture-secret");
        assertEquals(1, localCalls.get()); assertNull(service.getActiveProvider());
        cloudStatus = 200; service.refresh();
        assertEquals("doubao", service.getActiveProvider());
        assertTrue(service.hint().contains("真实向量"));
    }
    @Test void laterAuthenticationFailureInvalidatesReadyState() {
        var service = service("DOUBAO", "fixture-secret");
        cloudStatus = 401;
        assertNull(service.embed("fixture text"));
        assertFalse((boolean)service.status().get("available"));
        assertFalse((boolean)((Map<?,?>)service.status().get("doubao")).get("verified"));
    }
    @Test void temporaryFailureBacksOffThenRecoversWithoutChangingConfiguration() {
        var service = service("DOUBAO", "fixture-secret");
        cloudStatus = 503;
        assertNull(service.embed("fixture text"));
        int failedCalls = cloudCalls.get();
        cloudStatus = 200;
        assertNull(service.embed("fixture text"));
        assertEquals(failedCalls, cloudCalls.get(), "Unavailable services must respect probe backoff");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "nextProbeAt", 0L);
        assertNotNull(service.embed("fixture text"));
        assertTrue((boolean) service.status().get("available"));
        assertEquals(0, localCalls.get());
    }
}
