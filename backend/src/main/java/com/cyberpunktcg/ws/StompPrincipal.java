package com.cyberpunktcg.ws;

import java.security.Principal;

/**
 * Identité simplifiée d'une session STOMP : le pseudo fourni dans l'en-tête
 * {@code pseudo} du frame CONNECT. Pas de JWT dans cette version V1.
 *
 * <p>Le pseudo sert aussi d'identifiant de joueur en partie : le serveur
 * n'autorise jamais un client à déclarer son identité dans le corps d'un
 * message, seule la session fait foi.</p>
 */
public record StompPrincipal(String name) implements Principal {

    @Override
    public String getName() {
        return name;
    }
}
