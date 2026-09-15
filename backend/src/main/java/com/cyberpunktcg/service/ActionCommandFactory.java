package com.cyberpunktcg.service;

import com.cyberpunktcg.api.dto.ws.GameCommandDTO;
import com.cyberpunktcg.engine.GameRuleException;
import com.cyberpunktcg.engine.command.AttackCommand;
import com.cyberpunktcg.engine.command.EndTurnCommand;
import com.cyberpunktcg.engine.command.GameCommand;
import com.cyberpunktcg.engine.command.PlayCardCommand;
import com.cyberpunktcg.engine.command.SellCardCommand;
import com.cyberpunktcg.engine.command.SpendEddiesCommand;
import com.cyberpunktcg.engine.command.SpendLegendCommand;
import org.springframework.stereotype.Component;

/**
 * Traduit le DTO STOMP en commande du moteur. Le {@code playerId} est toujours
 * injecté depuis la session STOMP (jamais relu du payload) : un client ne peut
 * pas agir à la place d'un autre.
 *
 * <p>Actions reconnues : {@code PLAY_CARD}, {@code ATTACK}, {@code SELL_CARD},
 * {@code SPEND_LEGEND} (incliner une Legend pour gagner un Eddie),
 * {@code END_TURN}. {@code CONCEDE} est traité par le contrôleur (pas une
 * commande du moteur).</p>
 */
@Component
public class ActionCommandFactory {

    public static final String PLAY_CARD = "PLAY_CARD";
    public static final String ATTACK = "ATTACK";
    public static final String SELL_CARD = "SELL_CARD";
    public static final String SPEND_LEGEND = "SPEND_LEGEND";
    public static final String SPEND_EDDIES = "SPEND_EDDIES";
    public static final String END_TURN = "END_TURN";
    public static final String CONCEDE = "CONCEDE";

    /**
     * Construit la commande.
     *
     * @throws GameRuleException si l'action est inconnue ou si une cible obligatoire manque
     */
    public GameCommand build(GameCommandDTO dto, String playerId) {
        if (dto == null || dto.action() == null || dto.action().isBlank()) {
            throw new GameRuleException("Action absente (champ 'action' requis)");
        }
        String action = dto.action().trim().toUpperCase();
        return switch (action) {
            case PLAY_CARD -> buildPlayCard(dto, playerId);
            case ATTACK -> buildAttack(dto, playerId);
            case SELL_CARD -> buildSellCard(dto, playerId);
            case SPEND_LEGEND -> buildSpendLegend(dto, playerId);
            case SPEND_EDDIES -> buildSpendEddies(dto, playerId);
            case END_TURN -> new EndTurnCommand(playerId);
            case CONCEDE -> throw new GameRuleException("CONCEDE ne passe pas par le moteur");
            default -> throw new GameRuleException("Action inconnue : " + dto.action());
        };
    }

    private GameCommand buildPlayCard(GameCommandDTO dto, String playerId) {
        if (dto.instanceId() == null) {
            throw new GameRuleException("PLAY_CARD exige 'instanceId' (carte à jouer)");
        }
        return new PlayCardCommand(playerId, dto.instanceId(), dto.targetInstanceId());
    }

    private GameCommand buildAttack(GameCommandDTO dto, String playerId) {
        if (dto.instanceId() == null) {
            throw new GameRuleException("ATTACK exige 'instanceId' (attaquant)");
        }
        // Pas de cible = attaque directe vers la Gig Area adverse (vol de Gig).
        return new AttackCommand(playerId, dto.instanceId(), dto.targetInstanceId());
    }

    private GameCommand buildSpendEddies(GameCommandDTO dto, String playerId) {
        if (dto.instanceId() == null) {
            throw new GameRuleException("SPEND_EDDIES exige 'instanceId' (carte Eddies à incliner)");
        }
        return new SpendEddiesCommand(playerId, dto.instanceId());
    }

    private GameCommand buildSpendLegend(GameCommandDTO dto, String playerId) {
        if (dto.instanceId() == null) {
            throw new GameRuleException("SPEND_LEGEND exige 'instanceId' (Legend à incliner)");
        }
        return new SpendLegendCommand(playerId, dto.instanceId());
    }

    private GameCommand buildSellCard(GameCommandDTO dto, String playerId) {
        if (dto.instanceId() == null) {
            throw new GameRuleException("SELL_CARD exige 'instanceId' (carte à vendre)");
        }
        return new SellCardCommand(playerId, dto.instanceId());
    }
}
