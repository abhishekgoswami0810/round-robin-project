package com.coda.routingapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class RoutingServiceTest {

    private MockWebServer server1, server2, server3;
    private HealthCheckService healthCheckService;
    private RoutingService routingService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server1 = new MockWebServer();
        server2 = new MockWebServer();
        server3 = new MockWebServer();
        server1.start();
        server2.start();
        server3.start();

        WebClient.Builder builder = WebClient.builder();
        String url1 = server1.url("/").toString();
        String url2 = server2.url("/").toString();
        String url3 = server3.url("/").toString();

        healthCheckService = new HealthCheckService(builder, List.of(url1, url2, url3));

        server1.enqueue(new MockResponse().setResponseCode(200).setBody("UP"));
        server2.enqueue(new MockResponse().setResponseCode(200).setBody("UP"));
        server3.enqueue(new MockResponse().setResponseCode(200).setBody("UP"));

        healthCheckService.init();
        waitForAsyncCompletion();

        routingService = new RoutingService(builder, healthCheckService);
    }

    @AfterEach
    void tearDown() throws Exception {
        server1.shutdown();
        server2.shutdown();
        server3.shutdown();
    }

    @Test
    void testRoundRobin() {
        server1.enqueue(jsonResponse("{\"message\":\"response1\"}", 200));
        server2.enqueue(jsonResponse("{\"message\":\"response2\"}", 200));
        server3.enqueue(jsonResponse("{\"message\":\"response3\"}", 200));

        JsonNode payload = mapper.createObjectNode().put("key", "value");

        StepVerifier.create(routingService.forward(payload))
                .expectNextMatches(resp -> resp.get("message").asText().equals("response1"))
                .verifyComplete();

        StepVerifier.create(routingService.forward(payload))
                .expectNextMatches(resp -> resp.get("message").asText().equals("response2"))
                .verifyComplete();

        StepVerifier.create(routingService.forward(payload))
                .expectNextMatches(resp -> resp.get("message").asText().equals("response3"))
                .verifyComplete();
    }

    @Test
    void testCircuitBreakerFallback() {
        server1.enqueue(new MockResponse().setResponseCode(500)); // fail
        server2.enqueue(jsonResponse("{\"message\":\"fallback2\"}", 200)); // succeed
        server3.enqueue(jsonResponse("{\"message\":\"fallback3\"}", 200)); // backup success

        JsonNode payload = mapper.createObjectNode().put("key", "value");

        StepVerifier.create(routingService.forward(payload))
                .expectNextMatches(resp -> resp.get("message").asText().equals("fallback2"))
                .verifyComplete();
    }

    @Test
    void testAllInstancesFail() {
        server1.enqueue(new MockResponse().setResponseCode(500));
        server2.enqueue(new MockResponse().setResponseCode(500));
        server3.enqueue(new MockResponse().setResponseCode(500));

        JsonNode payload = mapper.createObjectNode().put("key", "value");

        StepVerifier.create(routingService.forward(payload))
                .expectErrorMatches(e -> e instanceof RuntimeException &&
                        e.getMessage().equals("All healthy instances failed or are in OPEN state"))
                .verify();
    }

    @Test
    void testCircuitBreakerOpen() throws Exception {
        JsonNode payload = mapper.createObjectNode().put("key", "value");

        for (int i = 0; i < 5; i++) {
            server1.enqueue(new MockResponse().setResponseCode(500));
            server2.enqueue(new MockResponse().setResponseCode(500));
            server3.enqueue(new MockResponse().setResponseCode(500));

            StepVerifier.create(routingService.forward(payload))
                    .expectError()
                    .verify();
        }

        // Assert CB open
        Field cbField = RoutingService.class.getDeclaredField("circuitBreakers");
        cbField.setAccessible(true);
        Map<String, CircuitBreaker> cbMap = (Map<String, CircuitBreaker>) cbField.get(routingService);

        CircuitBreaker cb1 = cbMap.get(server1.url("/").toString());
        assertEquals(CircuitBreaker.State.OPEN, cb1.getState());
    }

    @Test
    void testConcurrentRoundRobin() throws InterruptedException, ExecutionException {
        server1.enqueue(jsonResponse("{\"instance\":\"server1\"}", 200));
        server2.enqueue(jsonResponse("{\"instance\":\"server2\"}", 200));
        server3.enqueue(jsonResponse("{\"instance\":\"server3\"}", 200));

        JsonNode payload = mapper.createObjectNode().put("key", "value");

        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            futures.add(executor.submit(() -> routingService.forward(payload)
                    .block(Duration.ofSeconds(5)).get("instance").asText()));
        }

        Set<String> results = new HashSet<>();
        for (Future<String> f : futures) {
            try {
                results.add(f.get());
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        assertEquals(Set.of("server1", "server2", "server3"), results);

        executor.shutdown();
    }

    private MockResponse jsonResponse(String jsonBody, int statusCode) {
        return new MockResponse()
                .setResponseCode(statusCode)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonBody);
    }

    private void waitForAsyncCompletion() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
