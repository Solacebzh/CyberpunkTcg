package com.cyberpunktcg.domain.game;

/**
 * Sous-étapes de la phase {@link Phase#DRAW} (Mini-Feature 5 — phase DRAW
 * interactive), dans l'ordre imposé par le START PHASE des règles officielles :
 * <em>ready spent cards → draw 1 → gain a Gig</em>.
 *
 * <p>Le serveur fait autorité sur la progression ; deux étapes attendent une
 * action explicite du joueur actif :</p>
 * <ul>
 *   <li>{@link #AWAITING_DRAW} : le joueur doit cliquer sur sa pioche
 *   ({@code DRAW_CARD}) ;</li>
 *   <li>{@link #AWAITING_DIE_SELECT} : le joueur doit choisir le dé Gig à lancer
 *   ({@code SELECT_DIE}) — le {@code d20} n'est proposé que lorsqu'il est le
 *   dernier dé de la Fixer Area.</li>
 * </ul>
 *
 * <p>Les autres étapes ({@link #DRAW_START}, {@link #ROLLING_DIE},
 * {@link #DRAW_COMPLETE}) sont résolues par le serveur dans la foulée de la
 * commande qui les déclenche : elles apparaissent dans le journal de
 * diagnostic mais ne sont jamais des états d'attente. Hors phase {@code DRAW},
 * l'étape est {@code null}.</p>
 */
public enum DrawStep {
    /** Ouverture du tour : cartes redressées, Eddies à 0 (transitoire). */
    DRAW_START,
    /** En attente du clic du joueur sur sa pioche. */
    AWAITING_DRAW,
    /** Carte piochée : en attente du choix du dé Gig. */
    AWAITING_DIE_SELECT,
    /** Lancer du dé choisi par le serveur (transitoire). */
    ROLLING_DIE,
    /** Phase DRAW terminée : passage automatique en {@link Phase#MAIN} (transitoire). */
    DRAW_COMPLETE;

    /** {@code true} si l'étape attend une action du joueur actif. */
    public boolean awaitsPlayer() {
        return this == AWAITING_DRAW || this == AWAITING_DIE_SELECT;
    }
}
