package com.cyberpunktcg.domain.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Attaque en cours de résolution (Mini-Feature 6).
 *
 * <p>Portée par {@link GameState#getPendingAttack()} : elle matérialise les
 * étapes interactives d'une attaque — la décision de blocage du défenseur
 * ({@link CombatStep#AWAITING_BLOCK}) puis, pour une attaque directe non
 * bloquée, le choix par l'attaquant des {@code M} dés Gigs à voler
 * ({@link CombatStep#AWAITING_STEAL_CHOICE}).</p>
 *
 * <p>Le quota théorique {@code N} et le nombre réellement volable {@code M}
 * sont recalculés par le moteur au moment de la résolution (les réactions
 * {@code QUICK} peuvent changer la puissance de l'attaquant ou les dés du
 * défenseur) ; {@link #getStealable()} expose la dernière valeur calculée.</p>
 */
public class PendingAttack {

    private final String attackerPlayerId;
    private final String defendingPlayerId;
    private final UUID attackerInstanceId;
    /** {@code null} pour une attaque directe vers la Gig Area (vol de dés). */
    private final UUID targetInstanceId;
    private final List<UUID> blockerInstanceIds;

    private CombatStep step;
    private int quota;
    private int stealable;

    public PendingAttack(String attackerPlayerId, String defendingPlayerId,
                         UUID attackerInstanceId, UUID targetInstanceId) {
        if (attackerPlayerId == null) {
            throw new IllegalArgumentException("L'attaquant est obligatoire");
        }
        if (defendingPlayerId == null) {
            throw new IllegalArgumentException("Le défenseur est obligatoire");
        }
        if (attackerInstanceId == null) {
            throw new IllegalArgumentException("L'Unité attaquante est obligatoire");
        }
        this.attackerPlayerId = attackerPlayerId;
        this.defendingPlayerId = defendingPlayerId;
        this.attackerInstanceId = attackerInstanceId;
        this.targetInstanceId = targetInstanceId;
        this.blockerInstanceIds = new ArrayList<UUID>();
        this.step = CombatStep.AWAITING_BLOCK;
        this.quota = 0;
        this.stealable = 0;
    }

    public String getAttackerPlayerId() {
        return attackerPlayerId;
    }

    public String getDefendingPlayerId() {
        return defendingPlayerId;
    }

    public UUID getAttackerInstanceId() {
        return attackerInstanceId;
    }

    /** Cible déclarée, {@code null} pour une attaque directe (Gig Area adverse). */
    public UUID getTargetInstanceId() {
        return targetInstanceId;
    }

    /** {@code true} pour une attaque directe vers la Gig Area (vol de dés). */
    public boolean isDirect() {
        return targetInstanceId == null;
    }

    public CombatStep getStep() {
        return step;
    }

    public void setStep(CombatStep step) {
        if (step == null) {
            throw new IllegalArgumentException("L'étape de combat est obligatoire");
        }
        this.step = step;
    }

    /** Quota théorique {@code N = (power / 10) + 1} (0 si puissance ≤ 0). */
    public int getQuota() {
        return quota;
    }

    /** Nombre de dés réellement volables {@code M = min(N, dés actifs du défenseur)}. */
    public int getStealable() {
        return stealable;
    }

    /** Mémorise le quota et le plafond calculés au moment d'ouvrir le choix des dés. */
    public void prepareStealChoice(int quota, int stealable) {
        this.step = CombatStep.AWAITING_STEAL_CHOICE;
        this.quota = Math.max(0, quota);
        this.stealable = Math.max(0, stealable);
    }

    /** Blockers dépensés pour intercepter cette attaque, dans l'ordre de déclaration. */
    public List<UUID> getBlockerInstanceIds() {
        return Collections.unmodifiableList(blockerInstanceIds);
    }

    public void addBlocker(UUID blockerInstanceId) {
        if (blockerInstanceId != null) {
            blockerInstanceIds.add(blockerInstanceId);
        }
    }

    /** Copie détachée (vues masquées). */
    public PendingAttack copy() {
        PendingAttack copy = new PendingAttack(attackerPlayerId, defendingPlayerId,
                attackerInstanceId, targetInstanceId);
        copy.step = this.step;
        copy.quota = this.quota;
        copy.stealable = this.stealable;
        copy.blockerInstanceIds.addAll(this.blockerInstanceIds);
        return copy;
    }

    @Override
    public String toString() {
        return "PendingAttack{step=" + step + ", attacker=" + attackerPlayerId
                + ", defender=" + defendingPlayerId
                + ", target=" + (isDirect() ? "GIG_AREA" : targetInstanceId)
                + ", quota=" + quota + ", stealable=" + stealable + '}';
    }
}
