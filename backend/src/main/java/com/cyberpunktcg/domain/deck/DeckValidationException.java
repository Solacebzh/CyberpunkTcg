package com.cyberpunktcg.domain.deck;

import java.util.List;

/**
 * Exception métier signalant qu'un deck refuse les règles officielles de
 * deckbuilding (Mini-Feature 8) : elle est traduite en
 * {@code 400 Bad Request} par {@link com.cyberpunktcg.api.RestExceptionHandler}
 * et porte la liste des infractions à afficher côté client.
 *
 * <p>Elle est levée <strong>avant</strong> toute écriture en base : un deck
 * invalide n'est jamais persisté.</p>
 */
public class DeckValidationException extends RuntimeException {

    private final transient List<String> errors;

    public DeckValidationException(List<String> errors) {
        this("Deck invalide", errors);
    }

    public DeckValidationException(String message, List<String> errors) {
        super(message);
        this.errors = errors != null ? List.copyOf(errors) : List.of();
    }

    /** Infractions détaillées, en français, prêtes à être affichées. */
    public List<String> getErrors() {
        return errors;
    }
}
