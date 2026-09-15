package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.engine.GameRuleException;

import java.util.ArrayList;
import java.util.List;

/**
 * Intention de jeu (pattern Command) : toute mutation du {@link GameState} passe
 * par une commande qui se valide puis s'exécute.
 *
 * <p>Contrat :</p>
 * <ul>
 *   <li>{@link #validate(GameState)} ne mute rien et lève {@link GameRuleException}
 *   si l'action est illégale ;</li>
 *   <li>{@link #execute(GameState)} revalide, mute l'état, journalise et retourne
 *   les événements produits (jamais {@code null}).</li>
 * </ul>
 */
public interface GameCommand {

    /** Joueur qui ordonne l'action (dérivé du canal transport, jamais du client). */
    String getPlayerId();

    /**
     * Type technique de l'action, utilisé par le journal de diagnostic et les
     * transports ({@code PLAY_CARD}, {@code ATTACK}, {@code SELL_CARD},
     * {@code END_TURN}, {@code SPEND_LEGEND}…).
     */
    default String actionType() {
        return "COMMAND";
    }

    /**
     * Intention de l'ordonnateur en clair (« jouer 6th Street Recruits »,
     * « vendre une 2e carte »…), utilisée par le journal de diagnostic pour
     * décrire aussi bien les succès que les refus.
     */
    default String describe() {
        return actionType();
    }

    /**
     * Variante contextualisée : l'intention peut nommer la carte visée quand
     * elle est encore trouvable dans l'état (journal des refus).
     */
    default String describe(GameState state) {
        return describe();
    }

    /**
     * Vérifie la légalité de l'action sans muter l'état.
     *
     * @throws GameRuleException si l'action est illégale
     */
    void validate(GameState state) throws GameRuleException;

    /**
     * Valide puis applique l'action.
     *
     * @return les événements produits par l'exécution
     * @throws GameRuleException si l'action est illégale
     */
    List<GameEvent> execute(GameState state) throws GameRuleException;

    // ------------------------------------------------------------------
    // Gardes partagés
    // ------------------------------------------------------------------

    /** Refuse toute action sur une partie terminée. */
    static void requireGameOngoing(GameState state) throws GameRuleException {
        if (state.isGameOver()) {
            throw new GameRuleException("Partie terminée (gagnant : " + state.getWinnerId() + ")");
        }
    }

    /** Résout le joueur ordonnateur ou refuse (joueur inconnu). */
    static Player requireKnownPlayer(GameState state, String playerId) throws GameRuleException {
        if (!state.hasPlayer(playerId)) {
            throw new GameRuleException("Joueur inconnu dans cette partie : " + playerId);
        }
        return state.getPlayer(playerId);
    }

    /** Exige que l'ordonnateur soit le joueur actif. */
    static Player requireActivePlayer(GameState state, String playerId) throws GameRuleException {
        Player player = requireKnownPlayer(state, playerId);
        if (!state.getTurn().getActivePlayerId().equals(playerId)) {
            throw new GameRuleException("Ce n'est pas le tour de " + playerId);
        }
        return player;
    }

    /** Événements journalisés depuis un repère (pour construire le retour d'execute). */
    static List<GameEvent> eventsSince(GameState state, int mark) {
        return new ArrayList<GameEvent>(state.getEventLog().subList(mark, state.getEventLog().size()));
    }
}
