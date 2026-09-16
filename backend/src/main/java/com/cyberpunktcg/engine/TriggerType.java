package com.cyberpunktcg.engine;

/**
 * Déclencheurs d'effets (V1).
 */
public enum TriggerType {
    /** La carte est jouée (y compris un Program, résolu avant sa défausse). */
    ON_PLAY,
    /** L'Unit déclare une attaque (résolu avant la comparaison des puissances). */
    ON_ATTACK,
    /**
     * L'Unit intercepte une attaque avec le mot-clé {@code BLOCKER}
     * (Mini-Feature 6) : résolu pour CHAQUE Blocker dépensé, avant le combat
     * contre le dernier d'entre eux.
     */
    ON_BLOCK,
    /** L'Unit est vaincue (mise dans la défausse). */
    ON_DEATH,
    /** Fin du tour du contrôleur (déclenché par {@code EndTurnCommand}). */
    ON_TURN_END,
    /** Une Legend est retournée face visible (gratuit, sans coût Eddies). */
    FLIP,
    /** Carte {@code QUICK} jouée en réaction (fenêtre ouverte par une attaque). */
    QUICK,
    /** Legend Call : flip via Call a Legend (Guide § CALL A LEGEND). */
    CALL
}
