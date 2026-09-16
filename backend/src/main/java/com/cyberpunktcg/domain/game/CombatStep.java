package com.cyberpunktcg.domain.game;

/**
 * Sous-étapes de résolution d'une attaque (Mini-Feature 6).
 *
 * <p>Une attaque n'est plus résolue d'un bloc : elle reste « en cours »
 * ({@link PendingAttack}) tant qu'une décision de joueur est attendue,
 * conformément aux règles officielles § ATTACKING (« Each Unit attacks
 * individually, and completes all the attacking steps before another Unit can
 * attack »).</p>
 *
 * <ul>
 *   <li>{@link #AWAITING_BLOCK} — le défenseur possède au moins un
 *   {@code BLOCKER} prêt : fenêtre « Utiliser Blocker ? » ({@code USE_BLOCKER}
 *   avec un ou plusieurs Blockers, ou {@code DECLINE_BLOCK}) ;</li>
 *   <li>{@link #AWAITING_STEAL_CHOICE} — attaque directe non bloquée avec
 *   {@code M > 0} : l'attaquant choisit les {@code M} dés Gigs actifs à voler
 *   ({@code STEAL_GIG}).</li>
 * </ul>
 *
 * <p>L'absence d'attaque en cours ({@code PendingAttack == null}) signifie que
 * le combat est résolu. Une fin de tour résout automatiquement toute attaque
 * encore en attente (voir {@code CombatResolver}).</p>
 */
public enum CombatStep {
    AWAITING_BLOCK,
    AWAITING_STEAL_CHOICE
}
