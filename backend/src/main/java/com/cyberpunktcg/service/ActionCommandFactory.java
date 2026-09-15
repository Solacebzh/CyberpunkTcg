package com.cyberpunktcg.service;

import com.cyberpunktcg.api.dto.ws.GameCommandDTO;
import com.cyberpunktcg.engine.GameRuleException;
import com.cyberpunktcg.engine.command.AttackCommand;
import com.cyberpunktcg.engine.command.DrawCardCommand;
import com.cyberpunktcg.engine.command.EndTurnCommand;
import com.cyberpunktcg.engine.command.GameCommand;
import com.cyberpunktcg.engine.command.PlayCardCommand;
import com.cyberpunktcg.engine.command.SelectDieCommand;
import com.cyberpunktcg.engine.command.SellCardCommand;
import com.cyberpunktcg.engine.command.SpendEddiesCommand;
import com.cyberpunktcg.engine.command.SpendLegendCommand;
import com.cyberpunktcg.engine.command.SpendResourceCommand;
import org.springframework.stereotype.Component;

/**
 * Traduit le DTO STOMP en commande du moteur. Le {@code playerId} est toujours
 * injecté depuis la session STOMP (jamais relu du payload) : un client ne peut
 * pas agir à la place d'un autre.
 *
 * <p>Actions reconnues : {@code PLAY_CARD}, {@code ATTACK}, {@code SELL_CARD},
 * {@code SPEND_RESOURCE} (Mini-Feature 4 — incliner une ressource : Legend de
 * la Legends Area <em>ou</em> carte de l'Eddies Area, pour gagner 1 Eddie),
 * {@code SPEND_LEGEND} / {@code SPEND_EDDIES} (alias historiques de
 * {@code SPEND_RESOURCE} : mêmes règles, action de journal distincte),
 * {@code END_TURN}, {@code DRAW_CARD} et {@code SELECT_DIE} (Mini-Feature 5 —
 * phase DRAW interactive : pioche du tour puis choix du dé Gig, transmis dans
 * {@code dice[0]} ou {@code chosen}). {@code CONCEDE} est traité par le
 * contrôleur (pas une commande du moteur).</p>
 */
@Component
public class ActionCommandFactory {

    public static final String PLAY_CARD = "PLAY_CARD";
    public static final String ATTACK = "ATTACK";
    public static final String SELL_CARD = "SELL_CARD";
    public static final String SPEND_RESOURCE = "SPEND_RESOURCE";
    public static final String SPEND_LEGEND = "SPEND_LEGEND";
    public static final String SPEND_EDDIES = "SPEND_EDDIES";
    public static final String END_TURN = "END_TURN";
    /** Mini-Feature 5 : pioche du tour (phase DRAW, étape AWAITING_DRAW). */
    public static final String DRAW_CARD = "DRAW_CARD";
    /** Mini-Feature 5 : choix du dé Gig (phase DRAW, étape AWAITING_DIE_SELECT). */
    public static final String SELECT_DIE = "SELECT_DIE";
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
            case SPEND_RESOURCE -> buildSpendResource(dto, playerId);
            case SPEND_LEGEND -> buildSpendLegend(dto, playerId);
            case SPEND_EDDIES -> buildSpendEddies(dto, playerId);
            case END_TURN -> new EndTurnCommand(playerId);
            case DRAW_CARD -> new DrawCardCommand(playerId);
            case SELECT_DIE -> buildSelectDie(dto, playerId);
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

    private GameCommand buildSpendResource(GameCommandDTO dto, String playerId) {
        if (dto.instanceId() == null) {
            throw new GameRuleException("SPEND_RESOURCE exige 'instanceId' (Legend ou carte de l'Eddies Area à incliner)");
        }
        return new SpendResourceCommand(playerId, dto.instanceId());
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

    /**
     * Le dé choisi arrive dans {@code dice[0]} (champ prévu pour les choix de dés)
     * ou, à défaut, dans {@code chosen}.
     */
    private GameCommand buildSelectDie(GameCommandDTO dto, String playerId) {
        String die = null;
        if (dto.dice() != null && !dto.dice().isEmpty()) {
            die = dto.dice().get(0);
        }
        if (die == null || die.isBlank()) {
            die = dto.chosen();
        }
        if (die == null || die.isBlank()) {
            throw new GameRuleException("SELECT_DIE exige le dé à lancer dans 'dice' (ex. [\"d6\"])");
        }
        return new SelectDieCommand(playerId, die);
    }

    private GameCommand buildSellCard(GameCommandDTO dto, String playerId) {
        if (dto.instanceId() == null) {
            throw new GameRuleException("SELL_CARD exige 'instanceId' (carte à vendre)");
        }
        return new SellCardCommand(playerId, dto.instanceId());
    }
}
