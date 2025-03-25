package com.coda.routingapi.service;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HealthCheckServiceTest {

    private MockWebServer mockServer1;
    private MockWebServer mockServer2;

    @BeforeEach
    void setUp() throws IOException {
        mockServer1 = new MockWebServer();
        mockServer2 = new MockWebServer();

        mockServer1.start();
        mockServer2.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer1.shutdown();
        mockServer2.shutdown();
    }

    @Test
    void shouldReturnHealthyInstances_whenSomeAreHealthy() {
        // instance 1 healthy
        mockServer1.enqueue(new MockResponse().setBody("{\"status\":\"UP\"}").setResponseCode(200));

        //instance2 unhealthy
        mockServer2.enqueue(new MockResponse().setResponseCode(500));

        String url1 = mockServer1.url("").toString();
        String url2 = mockServer2.url("").toString();

        HealthCheckService service = new HealthCheckService(
                WebClient.builder(),
                List.of(url1, url2)
        );

        service.init();

        List<String> healthy = service.getHealthyInstances();

        assertEquals(1, healthy.size());
        assertTrue(healthy.contains(url1));
        assertFalse(healthy.contains(url2));
    }

    @Test
    void shouldReturnNoHealthyInstances_whenAllFail() {
        mockServer1.enqueue(new MockResponse().setResponseCode(500));
        mockServer2.enqueue(new MockResponse().setResponseCode(500));

        String url1 = mockServer1.url("").toString();
        String url2 = mockServer2.url("").toString();

        HealthCheckService service = new HealthCheckService(
                WebClient.builder(),
                List.of(url1, url2)
        );

        service.init();

        List<String> healthy = service.getHealthyInstances();

        assertTrue(healthy.isEmpty());
    }

    @Test
    void shouldReturnAllHealthyInstances_whenAllAreHealthy() {
        mockServer1.enqueue(new MockResponse().setBody("{\"status\":\"UP\"}").setResponseCode(200));
        mockServer2.enqueue(new MockResponse().setBody("{\"status\":\"UP\"}").setResponseCode(200));

        String url1 = mockServer1.url("").toString();
        String url2 = mockServer2.url("").toString();

        HealthCheckService service = new HealthCheckService(
                WebClient.builder(),
                List.of(url1, url2)
        );

        service.init();

        List<String> healthy = service.getHealthyInstances();

        assertEquals(2, healthy.size());
        assertTrue(healthy.contains(url1));
        assertTrue(healthy.contains(url2));
    }
}
