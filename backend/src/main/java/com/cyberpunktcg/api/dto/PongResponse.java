package com.cyberpunktcg.api.dto;

import java.time.Instant;

/**
 * Message diffusé par le serveur sur {@code /topic/pong}.
 *
 * @param type       toujours {@code pong}
 * @param echo       contenu renvoyé par le client
 * @param serverTime horodatage serveur (UTC)
 */
public record PongResponse(String type, String echo, Instant serverTime) {
}
