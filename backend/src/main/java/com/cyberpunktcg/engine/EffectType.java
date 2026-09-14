package com.cyberpunktcg.engine;

/**
 * Effets de cartes interprétés par le {@link RuleEngine} (V1).
 */
public enum EffectType {
    /** Inflige des dégâts (réduisent la puissance ; létaux → défausse + {@code ON_DEATH}). */
    DAMAGE,
    /** Soigne des dégâts subis, sans dépasser la puissance imprimée + buffs. */
    HEAL,
    /** Pioche des cartes (deck vide → défaite immédiate). */
    DRAW,
    /** Ajoute un bonus de puissance (buff, conservé jusqu'à la défausse). */
    GRANT_POWER,
    /** Vole un Gig au rival (le dé de plus forte valeur). */
    STEAL_GIG,
    /** Remise sur les coûts d'Eddies jusqu'au début du prochain tour du contrôleur. */
    REDUCE_COST
}
