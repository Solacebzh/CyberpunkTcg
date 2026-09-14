package com.cyberpunktcg.engine;

/**
 * Action illégale selon les règles (commande invalide, coût impayé, timing interdit…).
 * La couche transport la traduira en erreur privée pour le seul joueur fautif.
 */
public class GameRuleException extends RuntimeException {

    public GameRuleException(String message) {
        super(message);
    }
}
