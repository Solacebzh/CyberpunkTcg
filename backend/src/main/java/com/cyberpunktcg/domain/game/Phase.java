package com.cyberpunktcg.domain.game;

/**
 * Phases d'un tour, dans l'ordre imposé : Draw → Main → Combat → End.
 *
 * <p>{@code END} est résolue automatiquement par {@code EndTurnCommand}
 * (déclencheurs {@code ON_TURN_END} à la fermeture). {@code DRAW} est
 * <strong>interactive</strong> depuis la Mini-Feature 5 : {@code EndTurnCommand}
 * l'ouvre (cartes redressées) puis le joueur entrant pioche ({@code DRAW_CARD})
 * et choisit son dé Gig ({@code SELECT_DIE}) — la sous-étape courante est portée
 * par {@link DrawStep}. Le passage {@code MAIN → COMBAT} est déclenché par la
 * première attaque. Voir {@code docs/RULE-ENGINE.md}.</p>
 */
public enum Phase {
    DRAW,
    MAIN,
    COMBAT,
    END;

    /**
     * Phase suivante dans l'ordre du tour.
     *
     * @return la phase suivante ({@code END} boucle vers {@code DRAW} du tour suivant)
     */
    public Phase next() {
        switch (this) {
            case DRAW:
                return MAIN;
            case MAIN:
                return COMBAT;
            case COMBAT:
                return END;
            case END:
            default:
                return DRAW;
        }
    }
}
