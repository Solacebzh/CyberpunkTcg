package com.cyberpunktcg.domain.game;

/**
 * Tour courant d'une partie : numéro, joueur actif, phase et — pendant la
 * phase {@link Phase#DRAW} — sous-étape interactive ({@link DrawStep}).
 *
 * <p>Le tour {@code 1} commence directement en {@link Phase#MAIN} (la main de
 * départ est distribuée à la création, sans pioche ni lancer de Gig).</p>
 */
public class Turn {

    private int number;
    private String activePlayerId;
    private Phase phase;
    /** Sous-étape de la phase DRAW (Mini-Feature 5) ; {@code null} hors DRAW. */
    private DrawStep drawStep;

    public Turn(int number, String activePlayerId, Phase phase) {
        this(number, activePlayerId, phase, null);
    }

    public Turn(int number, String activePlayerId, Phase phase, DrawStep drawStep) {
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
        this.drawStep = phase == Phase.DRAW ? drawStep : null;
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

    /**
     * Change de phase. Quitter {@link Phase#DRAW} efface la sous-étape : hors
     * DRAW, {@link #getDrawStep()} vaut toujours {@code null}.
     */
    public void setPhase(Phase phase) {
        this.phase = phase;
        if (phase != Phase.DRAW) {
            this.drawStep = null;
        }
    }

    /** Sous-étape courante de la phase DRAW, ou {@code null} hors DRAW. */
    public DrawStep getDrawStep() {
        return drawStep;
    }

    public void setDrawStep(DrawStep drawStep) {
        this.drawStep = drawStep;
    }

    /** Copie détachée (vues masquées). */
    public Turn copy() {
        return new Turn(number, activePlayerId, phase, drawStep);
    }

    @Override
    public String toString() {
        return "Turn{number=" + number + ", activePlayerId='" + activePlayerId + "', phase=" + phase
                + (drawStep == null ? "" : ", drawStep=" + drawStep) + '}';
    }
}
