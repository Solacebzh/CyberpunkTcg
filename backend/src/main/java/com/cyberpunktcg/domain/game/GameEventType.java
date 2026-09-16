package com.cyberpunktcg.domain.game;

/**
 * Types d'événements journalisés pendant une partie.
 *
 * <p>Les descriptions d'événements ne contiennent jamais d'information secrète
 * (pas de nom de carte piochée, par exemple) : le journal complet peut donc être
 * exposé tel quel dans les vues masquées. C'est aussi la base du futur rejeu
 * déterministe (R10).</p>
 */
public enum GameEventType {
    CARD_PLAYED,
    CARD_DRAWN,
    CARD_SOLD,
    LEGEND_FLIPPED,
    ATTACK_DECLARED,
    /** Mini-Feature 6 : un {Blocker} dépensé redirige l'attaque vers lui. */
    ATTACK_BLOCKED,
    UNIT_DEFEATED,
    EFFECT_RESOLVED,
    GIG_STOLEN,
    GIG_ROLLED,
    TURN_STARTED,
    TURN_ENDED,
    PHASE_CHANGED,
    REACTION_WINDOW_OPENED,
    REACTION_WINDOW_CLOSED,
    GAME_WON
}
