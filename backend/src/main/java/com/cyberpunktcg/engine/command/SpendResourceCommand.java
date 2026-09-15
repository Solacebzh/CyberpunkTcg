package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameLog;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.domain.game.Zone;
import com.cyberpunktcg.engine.GameConstants;
import com.cyberpunktcg.engine.GameRuleException;

import java.util.List;
import java.util.UUID;

/**
 * Génère des Eddies — Mini-Feature 4 (règle R4) : la commande unifiée qui
 * remplace et englobe {@link SpendLegendCommand} / {@link SpendEddiesCommand}.
 *
 * <p>Règle officielle : pour obtenir 1 Eddie
 * ({@link Player#getAvailableEddies()}), le joueur utilise une ACTION pendant
 * sa <strong>Main Phase</strong>. Il peut choisir soit une Legend non inclinée
 * de sa {@link Zone#LEGENDS_AREA}, soit une carte vendue non inclinée de sa
 * {@link Zone#EDDIES_AREA}. La carte ciblée passe à {@code exhausted = true}
 * et le joueur gagne {@code +1} Eddie ({@link GameConstants#EDDIES_PER_RESOURCE}).</p>
 *
 * <p>Cible : un unique {@code instanceId} — soit une Legend, soit une carte de
 * l'Eddies Area. Les validations imposées (dans l'ordre) :</p>
 * <ol>
 *   <li>partie en cours et ordonnateur = joueur actif ;</li>
 *   <li>phase {@code MAIN} ;</li>
 *   <li>la carte existe dans la partie ;</li>
 *   <li>elle <strong>appartient au joueur</strong> ordonnateur (pas la carte du rival) ;</li>
 *   <li>elle est dans la <strong>bonne zone</strong> : {@code LEGENDS_AREA} ou {@code EDDIES_AREA} ;</li>
 *   <li>elle est <strong>non inclinée</strong> ({@code exhausted == false}) :
 *   1 €$ par tour et par carte, redressée au début du tour suivant (START PHASE).</li>
 * </ol>
 *
 * <p>Timing : phase {@code MAIN} du joueur actif, conformément à la règle R4
 * (« le joueur doit utiliser une ACTION pendant sa Main Phase »). Les actions
 * filaires {@code SPEND_LEGEND} et {@code SPEND_EDDIES} (protocole WebSocket)
 * restent acceptées : leurs commandes sont devenus des alias légers de cette
 * règle unifiée (les sous-classes conservent leur {@code actionType} pour la
 * granularité du journal de diagnostic).</p>
 */
public class SpendResourceCommand implements GameCommand {

    private final String playerId;
    private final UUID resourceInstanceId;

    public SpendResourceCommand(String playerId, UUID resourceInstanceId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Le joueur est obligatoire");
        }
        if (resourceInstanceId == null) {
            throw new IllegalArgumentException("La carte à incliner est obligatoire");
        }
        this.playerId = playerId;
        this.resourceInstanceId = resourceInstanceId;
    }

    @Override
    public String getPlayerId() {
        return playerId;
    }

    /** ID de la carte à incliner (Legend de la Legends Area ou carte de l'Eddies Area). */
    public UUID getResourceInstanceId() {
        return resourceInstanceId;
    }

    @Override
    public String actionType() {
        return "SPEND_RESOURCE";
    }

    @Override
    public String describe() {
        return "incliner une ressource (Legend ou carte de l'Eddies Area) pour 1 Eddie";
    }

    @Override
    public String describe(GameState state) {
        return state.findInstance(resourceInstanceId)
                .map(card -> "incliner " + card.getName() + " (Legend ou carte de l'Eddies Area) pour 1 Eddie")
                .orElseGet(this::describe);
    }

    @Override
    public void validate(GameState state) throws GameRuleException {
        GameCommand.requireGameOngoing(state);
        GameCommand.requireActivePlayer(state, playerId);
        // R4 : l'action se joue pendant la Main Phase du joueur actif.
        if (state.getPhase() != Phase.MAIN) {
            throw new GameRuleException("On incline une ressource qu'en phase Main");
        }
        // 1. La carte existe dans la partie.
        CardInstance resource = state.findInstance(resourceInstanceId)
                .orElseThrow(() -> new GameRuleException("Carte introuvable : " + resourceInstanceId));
        // 2. Elle appartient au joueur ordonnateur (pas une carte du rival).
        if (!playerId.equals(resource.getOwnerId())) {
            throw new GameRuleException("Cette carte n'appartient pas au joueur " + playerId);
        }
        // 3. Elle est dans une zone de ressource : Legends Area ou Eddies Area.
        if (resource.getZone() != Zone.LEGENDS_AREA && resource.getZone() != Zone.EDDIES_AREA) {
            throw new GameRuleException("Seule une Legend (Legends Area) ou une carte de l'Eddies Area "
                    + "peut être inclinée pour un Eddie");
        }
        // 4. Elle est prête (non inclinée) : 1 €$ par tour et par carte.
        if (resource.isExhausted()) {
            throw new GameRuleException("Cette carte est déjà inclinée (Eddie déjà perçu)");
        }
    }

    @Override
    public List<GameEvent> execute(GameState state) throws GameRuleException {
        validate(state);
        int mark = state.getEventLog().size();
        Player player = state.getPlayer(playerId);
        CardInstance resource = player.spendResourceForEddies(resourceInstanceId);
        boolean legend = resource.getZone() == Zone.LEGENDS_AREA;

        state.appendEvent(GameEventType.EFFECT_RESOLVED, playerId,
                (legend ? "legend inclinée" : "carte Eddies inclinée") + " : " + resource.getName()
                        + " (+1 Eddie, total " + player.getEddies() + ")");
        state.logSuccess(playerId, actionType(),
                "Joueur " + playerId + " incline " + (legend ? "sa Legend " : "sa carte Eddies ")
                        + resource.getName() + " → +1 Eddie (total " + player.getEddies()
                        + " Eddies, prêtes : " + player.legendsAvailableForEddies().size()
                        + " Legends / " + player.eddiesAvailableForEddies().size() + " Eddies)",
                GameLog.details(
                        "card", resource.getName(),
                        "cardId", resource.getCardId(),
                        "zone", resource.getZone().name(),
                        "exhausted", resource.isExhausted(),
                        "eddieGained", GameConstants.EDDIES_PER_RESOURCE,
                        "eddiesTotal", player.getEddies(),
                        "legendsReady", player.legendsAvailableForEddies().size(),
                        "eddiesReady", player.eddiesAvailableForEddies().size()));
        return GameCommand.eventsSince(state, mark);
    }
}
