package com.cyberpunktcg.domain.game;

/**
 * Fenêtre de réaction ouverte par une attaque.
 *
 * <p>Tant qu'elle est ouverte, le défenseur désigné peut jouer des cartes
 * {@code QUICK} hors de son tour (les autres cartes lui sont refusées).
 * Le joueur actif n'est pas restreint par la fenêtre. Elle se referme sur
 * {@code EndTurnCommand}. Voir {@code docs/RULE-ENGINE.md} § réactions.</p>
 */
public class ReactionWindow {

    private final String defendingPlayerId;
    private final String attackerInstanceId;

    public ReactionWindow(String defendingPlayerId, String attackerInstanceId) {
        if (defendingPlayerId == null) {
            throw new IllegalArgumentException("Le défenseur est obligatoire");
        }
        this.defendingPlayerId = defendingPlayerId;
        this.attackerInstanceId = attackerInstanceId;
    }

    public String getDefendingPlayerId() {
        return defendingPlayerId;
    }

    /** Identifiant (UUID, format texte) de l'attaquant, pour le journal. */
    public String getAttackerInstanceId() {
        return attackerInstanceId;
    }

    /** Copie détachée (vues masquées). */
    public ReactionWindow copy() {
        return new ReactionWindow(defendingPlayerId, attackerInstanceId);
    }

    @Override
    public String toString() {
        return "ReactionWindow{defendingPlayerId='" + defendingPlayerId
                + "', attackerInstanceId='" + attackerInstanceId + "'}";
    }
}
