package com.cyberpunktcg.api.dto.ws;

import com.cyberpunktcg.domain.game.PendingAttack;

import java.util.List;
import java.util.UUID;

/**
 * Attaque en cours de résolution (Mini-Feature 6) — {@code null} quand aucun
 * combat n'attend de décision.
 *
 * <p>C'est le signal qui pilote l'interface :</p>
 * <ul>
 *   <li>{@code step = AWAITING_BLOCK} → le défenseur voit la fenêtre
 *   « Utiliser Blocker ? » et répond par {@code USE_BLOCKER} (un ou plusieurs
 *   Blockers, ordre significatif) ou {@code DECLINE_BLOCK} ;</li>
 *   <li>{@code step = AWAITING_STEAL_CHOICE} → l'attaquant voit la modale
 *   « Choisissez M dé(s) Gig à voler » ({@code stealableCount} = M, quota
 *   théorique {@code quota} = N) et répond par {@code STEAL_GIG} avec exactement
 *   M identifiants de dés pris dans {@code players[defenseur].gigDieIds}.</li>
 * </ul>
 *
 * @param attackerPlayerId     joueur à l'origine de l'attaque
 * @param defendingPlayerId    joueur qui défend (fenêtre de réaction)
 * @param attackerInstanceId   exemplaire attaquant (déjà incliné)
 * @param targetInstanceId     cible déclarée, {@code null} pour une attaque directe (Gig Area)
 * @param step                 {@code AWAITING_BLOCK} ou {@code AWAITING_STEAL_CHOICE}
 * @param quota                quota théorique {@code N = (power / 10) + 1} (0 si power ≤ 0)
 * @param stealableCount       plafond strict {@code M = min(N, dés Gigs actifs du défenseur)}
 * @param blockerInstanceIds   Blockers déjà dépensés pour cette attaque (blocage multiple)
 */
public record PendingAttackDTO(
        String attackerPlayerId,
        String defendingPlayerId,
        String attackerInstanceId,
        String targetInstanceId,
        String step,
        int quota,
        int stealableCount,
        List<String> blockerInstanceIds
) {
    public static PendingAttackDTO from(PendingAttack pending) {
        if (pending == null) {
            return null;
        }
        UUID target = pending.getTargetInstanceId();
        List<String> blockers = pending.getBlockerInstanceIds().stream()
                .map(UUID::toString)
                .toList();
        return new PendingAttackDTO(
                pending.getAttackerPlayerId(),
                pending.getDefendingPlayerId(),
                pending.getAttackerInstanceId().toString(),
                target == null ? null : target.toString(),
                pending.getStep().name(),
                pending.getQuota(),
                pending.getStealable(),
                List.copyOf(blockers));
    }
}
