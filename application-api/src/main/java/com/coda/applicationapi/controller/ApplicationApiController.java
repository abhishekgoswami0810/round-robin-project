package com.coda.applicationapi.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApplicationApiController {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationApiController.class);

    @Value("${server.port}")
    private String port;

    @PostMapping("/echo")
    public ResponseEntity<JsonNode> echo(@RequestBody JsonNode payload) {
        logger.info("[Instance Port: {}] Received payload: {}", port, payload);
        return ResponseEntity.ok(payload);
    }
}
