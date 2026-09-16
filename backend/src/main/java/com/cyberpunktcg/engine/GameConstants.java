package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.game.GameLog;

/**
 * Constantes de règles imposées (cf. {@code docs/OFFICIAL-RULES.md} et
 * {@code docs/game-rules.md}).
 * Elles ne sont volontairement pas configurables.
 */
public final class GameConstants {

    /** Gigs contrôlés au début de son tour pour gagner immédiatement. */
    public static final int GIGS_TO_WIN = 7;

    /**
     * Tranches de puissance donnant un Gig volé supplémentaire (Mini-Feature 6,
     * règle officielle § ATTACKING — « Units steal an extra Gig for every 10
     * power (and 0 Gigs at power 0) ») : quota {@code N = (power / 10) + 1} pour
     * toute puissance ≥ 1, plafonné aux dés Gigs <em>actifs</em> du défenseur.
     */
    public static final int POWER_PER_EXTRA_GIG = 10;

    /** Ventes autorisées par tour et par joueur. */
    public static final int SALES_PER_TURN = 1;

    /** Cartes distribuées à chaque joueur à la création de la partie (règles officielles §3.3). */
    public static final int STARTING_HAND_SIZE = 6;

    /** Profondeur maximale des déclencheurs en cascade (garde-fou anti-boucle). */
    public static final int MAX_TRIGGER_DEPTH = 32;

    /**
     * Eddies gagnés quand un joueur incline une de ses Legends (« €$ », feature 6.5).
     * Les Legends face cachée de la Legends Area servent de réserve d'Eddies :
     * incliner la carte (l'épuiser) rapporte {@code 1} Eddie.
     */
    public static final int EDDIES_PER_LEGEND = 1;

    /**
     * Eddies gagnés quand un joueur incline l'une de ses cartes-ressource
     * (Mini-Feature 4, R4) : une Legend de la Legends Area <em>ou</em> une
     * carte vendue de l'Eddies Area. Une carte inclinée ne peut être inclinée
     * qu'une fois par tour (redressée au START PHASE).
     */
    public static final int EDDIES_PER_RESOURCE = 1;

    /**
     * Legends déjà inclinées au premier tour du joueur qui commence : le premier
     * joueur a un malus de mise en place, il ne peut donc obtenir qu'un seul
     * Eddie en inclinant sa troisième Legend (arbitrage joueur, feature 6.5).
     */
    public static final int FIRST_PLAYER_SPENT_LEGENDS = 2;

    /**
     * Nombre de Legends exigées dans la Legends Area : au-delà, la vérification
     * de RAM par couleur s'applique (règles officielles §2).
     */
    public static final int REQUIRED_LEGENDS = 3;

    /**
     * Plafond de RAM activé en partie — <strong>désactivé en V0 (R7)</strong> :
     * la RAM sert UNIQUEMENT au deckbuilding (Guide § DECK BUILDING & RAM).
     * En jeu, aucune vérification de RAM n'est effectuée ({@link com.cyberpunktcg.engine.command.PlayCardCommand}
     * ne vérifie plus la RAM). Conserver la constante pour le deckbuilder frontend.
     */
    public static final boolean RAM_CEILING_ENFORCED = false;

    /** Nombre maximal d'entrées conservées par le journal de diagnostic. */
    public static final int MAX_LOG_ENTRIES = GameLog.MAX_ENTRIES;

    private GameConstants() {
        // Classe utilitaire non instanciable.
    }
}
