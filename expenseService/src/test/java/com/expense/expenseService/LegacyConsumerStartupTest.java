package com.expense.expenseService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The legacy Kafka consumer (rewritten in the ingestion and reliability stages) is left as it is. It must not stop
 * the application from starting: here its listener container is ENABLED and points at a broker that does not exist,
 * and the context still loads and the API still answers.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:1",
        "spring.kafka.consumer.properties.default.api.timeout.ms=2000",
        "spring.kafka.consumer.properties.request.timeout.ms=2000"
})
class LegacyConsumerStartupTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Test
    void contextStartsAndServesRequestsWhileTheLegacyListenerHasNoBroker() throws Exception {
        assertFalse(listeners.getListenerContainers().isEmpty(), "the legacy @KafkaListener is registered");

        HttpResponse<String> health = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode(), health.body());

        HttpResponse<String> unauthenticated = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/expense/v1/expenses")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthenticated.statusCode());
    }
}
