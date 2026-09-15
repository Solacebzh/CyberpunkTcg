package com.cyberpunktcg.domain.game;

/**
 * Verdict d'une action de jeu consignée dans le {@link GameLog}.
 *
 * <p>Ce verdict est purement diagnostique : il ne modifie jamais l'état de la
 * partie (la légalité est décidée par les commandes du moteur). Il sert au
 * panneau de debug (couleur des lignes) et aux tests d'intégration.</p>
 */
public enum GameActionResult {
    /** L'action a été acceptée et appliquée. */
    SUCCESS,
    /** L'action était légale mais n'a produit aucun effet (cible invalide, deck vide…). */
    FAILED,
    /** L'action a été refusée par le moteur (règle violée) : aucune mutation. */
    ILLEGAL,
    /** Ligne narrative (phase, vérification de victoire, état interne…). */
    INFO
}
