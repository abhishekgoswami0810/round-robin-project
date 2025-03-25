package com.coda.routingapi.controller;

import com.coda.routingapi.service.RoutingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;

@WebFluxTest(RoutingController.class)
class RoutingControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private RoutingService routingService;

    @Test
    void testRouteSuccess() {
        ObjectNode requestBody = JsonNodeFactory.instance.objectNode();
        requestBody.put("game", "Mobile Legends");

        ObjectNode responseBody = JsonNodeFactory.instance.objectNode();
        responseBody.put("status", "ok");

        Mockito.when(routingService.forward(any(JsonNode.class)))
                .thenReturn(Mono.just(responseBody));

        webTestClient.post()
                .uri("/route")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok");
    }

    @Test
    void testRouteFailure() {
        ObjectNode requestBody = JsonNodeFactory.instance.objectNode();
        requestBody.put("game", "PUBG");

        Mockito.when(routingService.forward(any(JsonNode.class)))
                .thenReturn(Mono.error(new RuntimeException("No healthy instances")));

        webTestClient.post()
                .uri("/route")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.status").isEqualTo("error")
                .jsonPath("$.message").isEqualTo("No healthy instances");
    }

    @TestConfiguration
    static class WebClientTestConfig {
        @Bean
        public WebClient.Builder webClientBuilder() {
            return WebClient.builder();
        }
    }
}
