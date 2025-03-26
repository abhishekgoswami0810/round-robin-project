package com.coda.routingapi.service;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HealthCheckServiceTest {

    private MockWebServer mockServer1;
    private MockWebServer mockServer2;
    private HealthCheckService healthCheckService;

    @BeforeEach
    void setUp() throws Exception {
        mockServer1 = new MockWebServer();
        mockServer2 = new MockWebServer();
        mockServer1.start();
        mockServer2.start();

        WebClient.Builder builder = WebClient.builder();

        String instance1 = mockServer1.url("/").toString();
        String instance2 = mockServer2.url("/").toString();

        healthCheckService = new HealthCheckService(builder, List.of(instance1, instance2));
    }

    @AfterEach
    void tearDown() throws Exception {
        mockServer1.shutdown();
        mockServer2.shutdown();
    }

    @Test
    void testPeriodicHealthCheck() {
        mockServer1.enqueue(new MockResponse().setBody("UP").setResponseCode(200));
        mockServer2.enqueue(new MockResponse().setResponseCode(500));

        healthCheckService.init();
        waitForAsyncCompletion();

        List<String> healthyInstances = healthCheckService.getHealthyInstances();
        assertEquals(1, healthyInstances.size());
        assertTrue(healthyInstances.contains(mockServer1.url("/").toString()));
        assertFalse(healthyInstances.contains(mockServer2.url("/").toString()));

        // Flip server states
        mockServer1.enqueue(new MockResponse().setResponseCode(500));
        mockServer2.enqueue(new MockResponse().setBody("UP").setResponseCode(200));

        healthCheckService.init();
        waitForAsyncCompletion();

        healthyInstances = healthCheckService.getHealthyInstances();
        assertEquals(1, healthyInstances.size());
        assertFalse(healthyInstances.contains(mockServer1.url("/").toString()));
        assertTrue(healthyInstances.contains(mockServer2.url("/").toString()));
    }


    private void waitForAsyncCompletion() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
