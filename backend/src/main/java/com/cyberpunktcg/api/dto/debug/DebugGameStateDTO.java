package com.cyberpunktcg.api.dto.debug;

import com.cyberpunktcg.api.dto.ws.GameActionLogDTO;
import com.cyberpunktcg.api.dto.ws.GameLogEntryDTO;
import com.cyberpunktcg.api.dto.ws.PendingAttackDTO;

import java.time.Instant;
import java.util.List;

/**
 * Vue <strong>non masquée</strong> d'une partie, réservée au debug
 * ({@code GET /api/debug/game/{gameId}}, profils {@code test}/{@code dev}).
 *
 * <p>Contrairement à {@code GameStateDTO}, rien n'est masqué : mains des deux
 * joueurs, pioches, Legends face cachée et cartes vendues sont visibles. Ces
 * données ne doivent jamais être diffusées à un client de jeu.</p>
 *
 * @param gameId         identifiant de la partie
 * @param turnNumber     numéro du tour courant
 * @param phase          phase courante ({@code DRAW}, {@code MAIN}, {@code COMBAT}, {@code END})
 * @param drawStep       sous-étape de la phase DRAW interactive (Mini-Feature 5), absente hors DRAW
 * @param activePlayerId joueur actif
 * @param gameOver       partie terminée
 * @param winnerId       gagnant (absent si la partie continue)
 * @param endReason      motif de fin
 * @param seed           graine des tirages (rejeu déterministe)
 * @param createdAt      date de création
 * @param reactionWindow fenêtre de réaction ouverte ({@code null} sinon)
 * @param pendingAttack  attaque en cours de résolution (Mini-Feature 6, {@code null} sinon)
 * @param players        état complet des deux joueurs
 * @param log            journal public (événements, comme les clients)
 * @param gameLog        journal de diagnostic (dernières entrées)
 */
public record DebugGameStateDTO(
        String gameId,
        int turnNumber,
        String phase,
        String drawStep,
        String activePlayerId,
        boolean gameOver,
        String winnerId,
        String endReason,
        long seed,
        Instant createdAt,
        DebugReactionWindowDTO reactionWindow,
        PendingAttackDTO pendingAttack,
        List<DebugPlayerStateDTO> players,
        List<GameLogEntryDTO> log,
        List<GameActionLogDTO> gameLog
) {
}
