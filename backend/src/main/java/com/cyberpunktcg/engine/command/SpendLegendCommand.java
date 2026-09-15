package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.domain.game.Zone;
import com.cyberpunktcg.engine.GameConstants;
import com.cyberpunktcg.engine.GameRuleException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Incline une Legend pour gagner un Eddie (« €$ »).
 *
 * <p>Économie confirmée (feature 6.5) : les Legends face cachée de la Legends Area
 * forment la réserve d'Eddies du joueur. Incliner une Legend (l'épuiser) rapporte
 * {@link GameConstants#EDDIES_PER_LEGEND} Eddie et la dépense
 * <strong>définitivement</strong> — elle n'est pas redressée au début du tour
 * suivant, contrairement aux Units du Field. Une Legend retournée (jouée) ne
 * sert plus de réserve. Le premier joueur subit le malus de mise en place : ses
 * {@link GameConstants#FIRST_PLAYER_SPENT_LEGENDS} premières Legends sont déjà
 * inclinées, il ne peut donc encaisser qu'un seul Eddie de cette réserve.</p>
 *
 * <p>Timing : phase {@code MAIN} ou {@code COMBAT} du joueur actif (l'inclinaison
 * sert à payer les cartes jouées dans le même tour).</p>
 */
public class SpendLegendCommand implements GameCommand {

    private final String playerId;
    private final UUID legendInstanceId;

    public SpendLegendCommand(String playerId, UUID legendInstanceId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Le joueur est obligatoire");
        }
        if (legendInstanceId == null) {
            throw new IllegalArgumentException("La Legend à incliner est obligatoire");
        }
        this.playerId = playerId;
        this.legendInstanceId = legendInstanceId;
    }

    @Override
    public String getPlayerId() {
        return playerId;
    }

    public UUID getLegendInstanceId() {
        return legendInstanceId;
    }

    @Override
    public String actionType() {
        return "SPEND_LEGEND";
    }

    @Override
    public String describe() {
        return "incliner une Legend pour 1 Eddie";
    }

    @Override
    public void validate(GameState state) throws GameRuleException {
        GameCommand.requireGameOngoing(state);
        Player player = GameCommand.requireActivePlayer(state, playerId);
        Phase phase = state.getPhase();
        if (phase != Phase.MAIN && phase != Phase.COMBAT) {
            throw new GameRuleException("On n'incline une Legend qu'en phase Main ou Combat");
        }
        Optional<CardInstance> legend = player.findIn(Zone.LEGENDS_AREA, legendInstanceId);
        if (!legend.isPresent()) {
            throw new GameRuleException("Legend introuvable dans la Legends Area");
        }
        if (!legend.get().isFaceDown()) {
            throw new GameRuleException("Cette Legend est retournée (jouée) : elle ne peut plus servir d'Eddie");
        }
        if (legend.get().isExhausted()) {
            throw new GameRuleException("Cette Legend est déjà inclinée (Eddies déjà perçus)");
        }
    }

    @Override
    public List<GameEvent> execute(GameState state) throws GameRuleException {
        validate(state);
        int mark = state.getEventLog().size();
        Player player = state.getPlayer(playerId);
        CardInstance legend = player.spendLegendForEddies(legendInstanceId);

        state.appendEvent(GameEventType.EFFECT_RESOLVED, playerId,
                "legend inclinée : " + legend.getName() + " (+" + GameConstants.EDDIES_PER_LEGEND
                        + " Eddie, total " + player.getEddies() + ")");
        state.logSuccess(playerId, actionType(),
                "Joueur " + playerId + " incline " + legend.getName() + " → +"
                        + GameConstants.EDDIES_PER_LEGEND + " Eddie (total " + player.getEddies()
                        + " Eddies, Legends prêtes " + player.legendsAvailableForEddies().size()
                        + "/" + player.getLegendsArea().size() + ")",
                com.cyberpunktcg.domain.game.GameLog.details(
                        "legend", legend.getName(),
                        "legendId", legend.getCardId(),
                        "eddiesTotal", player.getEddies(),
                        "legendsReady", player.legendsAvailableForEddies().size()));
        return GameCommand.eventsSince(state, mark);
    }
}
