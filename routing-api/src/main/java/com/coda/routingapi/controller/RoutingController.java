package com.coda.routingapi.controller;

import com.coda.routingapi.service.RoutingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
public class RoutingController {

    private final RoutingService routingService;

    public RoutingController(RoutingService routingService) {
        this.routingService = routingService;
    }

    @PostMapping("/route")
    //As mentioned can be any JSON
    public Mono<ResponseEntity<JsonNode>> route(@RequestBody JsonNode payload) {
        return routingService.forward(payload)
                .map(ResponseEntity::ok)
                .onErrorResume(ex -> Mono.just(
                        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(createErrorResponse(ex.getMessage()))
                ));
    }

    private JsonNode createErrorResponse(String message) {
        return new ObjectNode(JsonNodeFactory.instance)
                .put("status", "error")
                .put("message", message);
    }
}
