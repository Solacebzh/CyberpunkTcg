package com.cyberpunktcg.service;

import com.cyberpunktcg.api.dto.ws.GameCommandDTO;
import com.cyberpunktcg.engine.GameRuleException;
import com.cyberpunktcg.engine.command.AttackCommand;
import com.cyberpunktcg.engine.command.BlockCommand;
import com.cyberpunktcg.engine.command.DeclineBlockCommand;
import com.cyberpunktcg.engine.command.DrawCardCommand;
import com.cyberpunktcg.engine.command.EndTurnCommand;
import com.cyberpunktcg.engine.command.GameCommand;
import com.cyberpunktcg.engine.command.PlayCardCommand;
import com.cyberpunktcg.engine.command.SelectDieCommand;
import com.cyberpunktcg.engine.command.SellCardCommand;
import com.cyberpunktcg.engine.command.SpendEddiesCommand;
import com.cyberpunktcg.engine.command.SpendLegendCommand;
import com.cyberpunktcg.engine.command.SpendResourceCommand;
import com.cyberpunktcg.engine.command.StealGigCommand;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * {@code dice[0]} ou {@code chosen}), {@code STEAL_GIG}, {@code USE_BLOCKER}
 * (alias {@code BLOCK}) et {@code DECLINE_BLOCK} (Mini-Feature 6 — combat
 * interactif : interception par un/des Blocker(s) au choix du défenseur, puis
 * choix par l'attaquant des {@code M} dés Gigs à voler, identifiants transmis
 * dans {@code dice} ou {@code cardIds}). {@code CONCEDE} est traité par le
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
    /** Mini-Feature 6 : choix des dés Gigs à voler (attaque directe, plafond strict M). */
    public static final String STEAL_GIG = "STEAL_GIG";
    /** Mini-Feature 6 : le défenseur intercepte avec un ou plusieurs {Blocker} prêts. */
    public static final String USE_BLOCKER = "USE_BLOCKER";
    /** Alias de {@link #USE_BLOCKER}. */
    public static final String BLOCK = "BLOCK";
    /** Mini-Feature 6 : le défenseur renonce à intercepter. */
    public static final String DECLINE_BLOCK = "DECLINE_BLOCK";
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
            case STEAL_GIG -> buildStealGig(dto, playerId);
            case USE_BLOCKER, BLOCK -> buildBlock(dto, playerId);
            case DECLINE_BLOCK -> new DeclineBlockCommand(playerId);
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

    /**
     * Mini-Feature 6 (règle C) : les dés Gigs choisis par l'attaquant arrivent
     * dans {@code dice} (identifiants texte), à défaut dans {@code cardIds}
     * (UUID) ou dans {@code chosen} (liste séparée par des virgules / espaces).
     * Une liste vide est transmise telle quelle : {@code StealGigCommand} la
     * refuse avec le nombre attendu (plafond strict M).
     */
    private GameCommand buildStealGig(GameCommandDTO dto, String playerId) {
        List<String> dieIds = new ArrayList<String>();
        if (dto.dice() != null) {
            for (String die : dto.dice()) {
                if (die != null && !die.isBlank()) {
                    dieIds.add(die.trim());
                }
            }
        }
        if (dieIds.isEmpty() && dto.cardIds() != null) {
            for (UUID id : dto.cardIds()) {
                if (id != null) {
                    dieIds.add(id.toString());
                }
            }
        }
        if (dieIds.isEmpty() && dto.chosen() != null && !dto.chosen().isBlank()) {
            for (String id : dto.chosen().split("[,\\s]+")) {
                if (!id.isBlank()) {
                    dieIds.add(id.trim());
                }
            }
        }
        return new StealGigCommand(playerId, dieIds);
    }

    /**
     * Mini-Feature 6 (règle B) : Blockers à dépenser, dans l'ordre de
     * déclaration (le DERNIER encaisse les dégâts). Source : {@code cardIds}
     * (blocage multiple) ou {@code instanceId} (blocage simple).
     */
    private GameCommand buildBlock(GameCommandDTO dto, String playerId) {
        List<UUID> blockers = new ArrayList<UUID>();
        if (dto.cardIds() != null) {
            for (UUID id : dto.cardIds()) {
                if (id != null) {
                    blockers.add(id);
                }
            }
        }
        if (blockers.isEmpty() && dto.instanceId() != null) {
            blockers.add(dto.instanceId());
        }
        if (blockers.isEmpty()) {
            throw new GameRuleException("USE_BLOCKER exige au moins un Blocker dans 'cardIds' "
                    + "(ou 'instanceId' pour un blocage simple)");
        }
        return new BlockCommand(playerId, blockers);
    }

    private GameCommand buildSellCard(GameCommandDTO dto, String playerId) {
        if (dto.instanceId() == null) {
            throw new GameRuleException("SELL_CARD exige 'instanceId' (carte à vendre)");
        }
        return new SellCardCommand(playerId, dto.instanceId());
    }
}
