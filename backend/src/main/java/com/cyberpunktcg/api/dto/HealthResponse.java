package com.cyberpunktcg.api.dto;

import java.time.Instant;

/**
 * Réponse de {@code GET /api/health}.
 *
 * @param status     état global du service ({@code UP})
 * @param service    nom de l'application Spring
 * @param version    version du backend
 * @param database   état de la base ({@code UP}/{@code DOWN})
 * @param timestamp  horodatage serveur (UTC)
 */
public record HealthResponse(
        String status,
        String service,
        String version,
        String database,
        Instant timestamp
) {
}
