package com.cyberpunktcg.engine;

/**
 * Cibles possibles d'un effet.
 */
public enum EffectTarget {
    /** La carte source de l'effet elle-même. */
    SELF,
    /** La cible désignée par la commande (peut être absente → effet sans effet). */
    TARGET_UNIT,
    /** Toutes les Units rivales sur le Field. */
    EACH_RIVAL_UNIT,
    /** Le contrôleur de la source (joueur). */
    SELF_PLAYER,
    /** Le rival du contrôleur (joueur). */
    RIVAL_PLAYER,
    /**
     * Une Unit alliée choisie automatiquement (la plus puissante du Field) :
     * utilisé par les textes génériques « give a friendly Unit +N power ».
     */
    FRIENDLY_UNIT,
    /**
     * Une Unit rivale choisie automatiquement (la plus puissante éligible) :
     * utilisé par les textes génériques « defeat a rival Unit [with power N or less] ».
     */
    RIVAL_UNIT
}
