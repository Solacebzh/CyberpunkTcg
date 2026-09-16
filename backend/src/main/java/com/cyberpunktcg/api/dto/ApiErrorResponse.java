package com.cyberpunktcg.api.dto;

import java.util.List;

/**
 * Corps d'erreur REST homogène pour les requêtes refusées par le serveur.
 *
 * <p>{@code errors} porte le détail ligne par ligne (règles de deckbuilding
 * violées, champs invalides…) : le frontend l'affiche tel quel, en rouge,
 * sans avoir à reformater le message.</p>
 */
public record ApiErrorResponse(
        int status,
        String code,
        String message,
        List<String> errors
) {

    public static ApiErrorResponse of(int status, String code, String message, List<String> errors) {
        return new ApiErrorResponse(status, code, message, errors != null ? List.copyOf(errors) : List.of());
    }
}
