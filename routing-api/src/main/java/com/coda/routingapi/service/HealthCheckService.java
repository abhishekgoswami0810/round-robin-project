package com.coda.routingapi.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

@Component
@Slf4j
public class HealthCheckService {

    private final WebClient webClient;
    private final List<String> allInstances;

    //TODO: do i need concurrency right now
    private final Set<String> healthyInstances = new ConcurrentSkipListSet<>();

    public HealthCheckService(WebClient.Builder builder,
                              @Value("#{'${application.api.instances}'.split(',')}") List<String> instances) {
        this.webClient = builder.build();
        this.allInstances = instances;
    }

    @PostConstruct
    @Scheduled(fixedDelayString = "${healthcheck.interval:60000}")
    public void init() {
        for (String baseUrl : allInstances) {
            String healthUrl = baseUrl + "/actuator/health";
            webClient.get()
                    .uri(healthUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(2))
                    .subscribe(
                            response -> {
                                healthyInstances.add(baseUrl);
                                log.info("{} is healthy", baseUrl);
                            },
                            error -> {
                                healthyInstances.remove(baseUrl);
                                log.error("{} is unhealthy: {}", baseUrl, error.getMessage());
                            }
                    );
        }
    }

    public List<String> getHealthyInstances() {
        return List.copyOf(healthyInstances);
    }
}


