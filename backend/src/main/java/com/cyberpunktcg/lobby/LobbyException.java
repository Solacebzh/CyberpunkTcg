package com.cyberpunktcg.lobby;

/**
 * Erreur fonctionnelle du salon (code invalide, pseudo déjà utilisé, salon
 * plein, deck incorrect…). La couche WebSocket la traduit en {@code WsError}
 * privé ; en REST elle serait traduite en 4xx.
 */
public class LobbyException extends RuntimeException {

    private final String code;

    public LobbyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
