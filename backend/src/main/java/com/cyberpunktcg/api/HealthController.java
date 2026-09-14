package com.cyberpunktcg.api;

import com.cyberpunktcg.api.dto.HealthResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;

/**
 * Sonde de santé : permet au frontend (et à la checklist de la PR) de vérifier
 * en une requête que l'API répond et que la base est joignable.
 *
 * <p>Volontairement tolérant : une base indisponible n'entraîne pas d'erreur 500,
 * elle est signalée par {@code database: "DOWN"} — pratique pendant le développement.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class HealthController {

    private final DataSource dataSource;
    private final String applicationName;
    private final String version;

    public HealthController(DataSource dataSource,
                            @Value("${spring.application.name}") String applicationName,
                            @Value("${app.version:0.1.0-SNAPSHOT}") String version) {
        this.dataSource = dataSource;
        this.applicationName = applicationName;
        this.version = version;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", applicationName, version, databaseStatus(), Instant.now());
    }

    private String databaseStatus() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2) ? "UP" : "DOWN";
        } catch (Exception e) {
            log.warn("Base de données injoignable : {}", e.getMessage());
            return "DOWN";
        }
    }
}
