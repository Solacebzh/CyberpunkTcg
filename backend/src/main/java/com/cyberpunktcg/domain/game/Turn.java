package com.cyberpunktcg.domain.game;

/**
 * Tour courant d'une partie : numéro, joueur actif et phase.
 *
 * <p>Le tour {@code 1} commence directement en {@link Phase#MAIN} (la main de
 * départ est distribuée à la création, sans pioche ni lancer de Gig).</p>
 */
public class Turn {

    private int number;
    private String activePlayerId;
    private Phase phase;

    public Turn(int number, String activePlayerId, Phase phase) {
        if (number < 1) {
            throw new IllegalArgumentException("Le numéro de tour commence à 1");
        }
        if (activePlayerId == null) {
            throw new IllegalArgumentException("Le joueur actif est obligatoire");
        }
        if (phase == null) {
            throw new IllegalArgumentException("La phase est obligatoire");
        }
        this.number = number;
        this.activePlayerId = activePlayerId;
        this.phase = phase;
    }

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
    }

    public String getActivePlayerId() {
        return activePlayerId;
    }

    public void setActivePlayerId(String activePlayerId) {
        this.activePlayerId = activePlayerId;
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    /** Copie détachée (vues masquées). */
    public Turn copy() {
        return new Turn(number, activePlayerId, phase);
    }

    @Override
    public String toString() {
        return "Turn{number=" + number + ", activePlayerId='" + activePlayerId + "', phase=" + phase + '}';
    }
}
