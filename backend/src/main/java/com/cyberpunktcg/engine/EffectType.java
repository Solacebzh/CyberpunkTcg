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
    REDUCE_COST,
    /**
     * Vainc une Unit (défausse + {@code ON_DEATH}). La valeur porte un plafond de
     * puissance ({@code 0} = aucun plafond) : « Defeat a rival Unit with power 4
     * or less » → {@code DEFEAT_UNIT:4}. Le choix de la cible est déterministe
     * (la plus puissante Unit adverse éligible).
     */
    DEFEAT_UNIT,
    /**
     * Augmente la valeur d'un Gig du bénéficiaire (« Increase a Gig by up to N »).
     * La valeur de l'effet est appliquée au Gig de plus faible valeur (choix
     * déterministe) et alimente donc le Street Cred.
     */
    BOOST_GIG,
    /**
     * Diminue la valeur d'un Gig du bénéficiaire (« Decrease a rival Gig by up to N »),
     * sans descendre sous 1.
     */
    REDUCE_GIG
}
