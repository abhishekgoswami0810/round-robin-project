package com.coda.routingapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class RoutingService {
    
    private final WebClient webClient;
    private final HealthCheckService healthCheckService;
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);

    public RoutingService(WebClient.Builder builder, HealthCheckService healthCheckService) {
        this.webClient = builder.build();
        this.healthCheckService = healthCheckService;
    }

    public Mono<JsonNode> forward(JsonNode payload) {
        List<String> healthyInstances = healthCheckService.getHealthyInstances();
        if (healthyInstances.isEmpty()) {
            return Mono.error(new RuntimeException("No healthy instances available"));
        }
        return tryForward(payload, healthyInstances, 0);
    }

    private Mono<JsonNode> tryForward(JsonNode payload, List<String> healthyInstances, int attempt) {
        if (attempt >= healthyInstances.size()) {
            return Mono.error(new RuntimeException("All healthy instances failed or are in OPEN state"));
        }

        int index = Math.abs(counter.getAndIncrement() % healthyInstances.size()); // 3%3 ->0,4%3,5%3
        String baseUrl = healthyInstances.get(index);
        String url = baseUrl + "/api/echo";

        CircuitBreaker cb = circuitBreakers.computeIfAbsent(baseUrl, key -> {
            CircuitBreaker circuitBreaker = CircuitBreaker.of(baseUrl, CircuitBreakerConfig.custom()
                    .failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofSeconds(30))
                    .slidingWindowSize(5)
                    .permittedNumberOfCallsInHalfOpenState(2)
                    .build());

            //just to log CB events here
            circuitBreaker.getEventPublisher()
                    .onStateTransition(event ->
                            log.info("CircuitBreaker '{}' transitioned from {} → {}",
                                    event.getCircuitBreakerName(),
                                    event.getStateTransition().getFromState(),
                                    event.getStateTransition().getToState()))
                    .onCallNotPermitted(event ->
                            log.info("CircuitBreaker '{}' is OPEN, call not permitted", event.getCircuitBreakerName()))
                    .onError(event ->
                            log.error("CircuitBreaker '{}' recorded error: {}", event.getCircuitBreakerName(), event.getThrowable().toString()));
            return circuitBreaker;
        });

        log.info("Routing to instance [{}]: {}", index, url);

        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(2))
                .transformDeferred(CircuitBreakerOperator.of(cb))
                .doOnSuccess(resp -> log.info("Success from {}", url))
                .onErrorResume(ex -> {
                    log.error("Error from {}: {}", url, ex.getMessage());
                    // Fallback - try next instance
                    return tryForward(payload, healthyInstances, attempt + 1);
                });
    }
}
