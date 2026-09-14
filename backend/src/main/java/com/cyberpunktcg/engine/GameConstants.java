package com.cyberpunktcg.engine;

/**
 * Constantes de règles imposées (cf. {@code docs/game-rules.md} §9).
 * Elles ne sont volontairement pas configurables.
 */
public final class GameConstants {

    /** Gigs contrôlés au début de son tour pour gagner immédiatement. */
    public static final int GIGS_TO_WIN = 7;

    /** Ventes autorisées par tour et par joueur. */
    public static final int SALES_PER_TURN = 1;

    /** Cartes distribuées à chaque joueur à la création de la partie. */
    public static final int STARTING_HAND_SIZE = 6;

    /** Profondeur maximale des déclencheurs en cascade (garde-fou anti-boucle). */
    public static final int MAX_TRIGGER_DEPTH = 32;

    private GameConstants() {
        // Classe utilitaire non instanciable.
    }
}
