package com.coda.routingapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RoutingServiceTest {

    private MockWebServer server1, server2, server3;
    private HealthCheckService healthCheckService;
    private RoutingService routingService;
    private String baseUrl1, baseUrl2, baseUrl3;
    private Map<String, MockWebServer> serverByUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setup() throws Exception {
        server1 = new MockWebServer();
        server2 = new MockWebServer();
        server3 = new MockWebServer();
        server1.start();
        server2.start();
        server3.start();

        baseUrl1 = server1.url("/").toString();
        baseUrl2 = server2.url("/").toString();
        baseUrl3 = server3.url("/").toString();

        // Enqueue health check responses for each server.
        server1.enqueue(new MockResponse().setResponseCode(200).setBody("OK"));
        server2.enqueue(new MockResponse().setResponseCode(200).setBody("OK"));
        server3.enqueue(new MockResponse().setResponseCode(200).setBody("OK"));

        WebClient.Builder webClientBuilder = WebClient.builder();
        healthCheckService = new HealthCheckService(webClientBuilder, List.of(baseUrl1, baseUrl2, baseUrl3));
        healthCheckService.init();

        serverByUrl = new HashMap<>();
        serverByUrl.put(baseUrl1, server1);
        serverByUrl.put(baseUrl2, server2);
        serverByUrl.put(baseUrl3, server3);

        routingService = new RoutingService(webClientBuilder, healthCheckService);
    }

    @AfterEach
    public void tearDown() throws Exception {
        server1.shutdown();
        server2.shutdown();
        server3.shutdown();
    }

    @Test
    public void testRoundRobinBehaviour() throws Exception {
        List<String> healthyInstances = healthCheckService.getHealthyInstances();
        Map<String, String> expectedResponses = new HashMap<>();
        expectedResponses.put(baseUrl1, "server1");
        expectedResponses.put(baseUrl2, "server2");
        expectedResponses.put(baseUrl3, "server3");

        // For the first call, enqueue a success on the instance chosen by round-robin.
        String instance0 = healthyInstances.getFirst();
        serverByUrl.get(instance0).enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":\"" + expectedResponses.get(instance0) + "\"}"));
        JsonNode payload = objectMapper.readTree("{\"key\":\"value\"}");
        Mono<JsonNode> result1 = routingService.forward(payload);
        StepVerifier.create(result1)
                .expectNextMatches(json -> json.has("message") &&
                        json.get("message").asText().equals(expectedResponses.get(instance0)))
                .verifyComplete();

        // For the second call, the next instance in sorted order is used.
        String instance1 = healthyInstances.get(1);
        serverByUrl.get(instance1).enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":\"" + expectedResponses.get(instance1) + "\"}"));
        Mono<JsonNode> result2 = routingService.forward(payload);
        StepVerifier.create(result2)
                .expectNextMatches(json -> json.has("message") &&
                        json.get("message").asText().equals(expectedResponses.get(instance1)))
                .verifyComplete();

        // Third call should go to the remaining instance.
        String instance2 = healthyInstances.get(2);
        serverByUrl.get(instance2).enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":\"" + expectedResponses.get(instance2) + "\"}"));
        Mono<JsonNode> result3 = routingService.forward(payload);
        StepVerifier.create(result3)
                .expectNextMatches(json -> json.has("message") &&
                        json.get("message").asText().equals(expectedResponses.get(instance2)))
                .verifyComplete();
    }

    @Test
    void testConcurrentRoundRobin() throws Exception {
        // Enqueue application responses for each server
        server1.enqueue(new MockResponse().setBody("{\"instance\":\"server1\"}").addHeader("Content-Type", "application/json"));
        server2.enqueue(new MockResponse().setBody("{\"instance\":\"server2\"}").addHeader("Content-Type", "application/json"));
        server3.enqueue(new MockResponse().setBody("{\"instance\":\"server3\"}").addHeader("Content-Type", "application/json"));

        // Prepare the payload
        ObjectMapper mapper = new ObjectMapper();
        JsonNode payload = mapper.createObjectNode()
                .put("game", "TestGame")
                .put("gamerID", "concurrent_user")
                .put("points", 100);

        // parallel req
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            results.add(executor.submit(() -> {
                JsonNode response = routingService.forward(payload).block(Duration.ofSeconds(5));
                return response.get("instance").asText();
            }));
        }

        Set<String> instancesHit = new HashSet<>();
        for (Future<String> future : results) {
            instancesHit.add(future.get());
        }
        executor.shutdown();

        assertEquals(Set.of("server1", "server2", "server3"), instancesHit);
    }

    @Test
    public void testCircuitBreakerFallback() throws Exception {
        // failing 1st instance here
        List<String> healthyInstances = healthCheckService.getHealthyInstances();
        serverByUrl.get(healthyInstances.get(0)).enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"failure\"}"));

        // checking next success
        serverByUrl.get(healthyInstances.get(1)).enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":\"fallback-success\"}"));

        JsonNode payload = objectMapper.readTree("{\"key\":\"value\"}");
        Mono<JsonNode> result = routingService.forward(payload);
        StepVerifier.create(result)
                .expectNextMatches(json -> json.has("message") &&
                        json.get("message").asText().equals("fallback-success"))
                .verifyComplete();
    }

    @Test
    public void testAllInstancesDown() throws Exception {
        List<String> healthyInstances = healthCheckService.getHealthyInstances();
        for (String instanceUrl : healthyInstances) {
            serverByUrl.get(instanceUrl).enqueue(new MockResponse()
                    .setResponseCode(500)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"error\":\"failure\"}"));
        }

        JsonNode payload = objectMapper.readTree("{\"key\":\"value\"}");
        Mono<JsonNode> result = routingService.forward(payload);
        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof RuntimeException &&
                        throwable.getMessage().equals("All healthy instances failed or are in OPEN state"))
                .verify();
    }

    @Test
    public void testCircuitBreakerOpen() throws Exception {
        // triggering CB
        int numForwardCalls = 5;
        List<String> healthyInstances = healthCheckService.getHealthyInstances();
        for (int i = 0; i < numForwardCalls; i++) {
            for (String instanceUrl : healthyInstances) {
                serverByUrl.get(instanceUrl).enqueue(new MockResponse()
                        .setResponseCode(500)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"error\":\"failure\"}"));
            }
        }

        JsonNode payload = objectMapper.readTree("{\"key\":\"value\"}");
        // checking if all failed here
        for (int i = 0; i < numForwardCalls; i++) {
            Mono<JsonNode> result = routingService.forward(payload);
            StepVerifier.create(result)
                    .expectErrorMatches(throwable -> throwable instanceof RuntimeException &&
                            throwable.getMessage().equals("All healthy instances failed or are in OPEN state"))
                    .verify();
        }

        // Asserting CB open here
        Field field = RoutingService.class.getDeclaredField("circuitBreakers");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, CircuitBreaker> cbMap = (Map<String, CircuitBreaker>) field.get(routingService);
        for (String instanceUrl : healthyInstances) {
            CircuitBreaker cb = cbMap.get(instanceUrl);
            assertEquals(CircuitBreaker.State.OPEN, cb.getState(),
                    "CircuitBreaker for instance " + instanceUrl + " should be OPEN after repeated failures.");
        }
    }
}
