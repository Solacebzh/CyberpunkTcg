package com.cyberpunktcg.api.dto;

/**
 * Message envoyé par le client sur {@code /app/ping}.
 *
 * @param message texte libre renvoyé en écho (facultatif)
 */
public record PingRequest(String message) {
}
