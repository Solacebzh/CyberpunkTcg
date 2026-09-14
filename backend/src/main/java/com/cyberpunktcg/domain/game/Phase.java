package com.cyberpunktcg.domain.game;

/**
 * Phases d'un tour, dans l'ordre imposé : Draw → Main → Combat → End.
 *
 * <p>En V1, {@code DRAW} et {@code END} sont résolues automatiquement par
 * {@code EndTurnCommand} (pioche + lancer de Gig à l'ouverture, déclencheurs
 * {@code ON_TURN_END} à la fermeture). Le passage {@code MAIN → COMBAT} est
 * déclenché par la première attaque. Voir {@code docs/RULE-ENGINE.md}.</p>
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
