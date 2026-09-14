package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.domain.game.Zone;
import com.cyberpunktcg.engine.GameRuleException;
import com.cyberpunktcg.engine.RuleEngine;
import com.cyberpunktcg.engine.TriggerType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Joue une carte :
 * <ul>
   *   <li>Legend face cachée de la Legends Area → retournée gratuitement (effet {@code FLIP}, pas {@code ON_PLAY}), pas de coût Eddies ;</li>
 *   <li>Unit de la main → Field (mal d'invocation sauf {@code GO_SOLO}), coût payé ;</li>
 *   <li>Program de la main → effet {@code ON_PLAY} puis défausse, coût payé ;</li>
 *   <li>Gear de la main → attaché à une Unit alliée du Field, coût payé.</li>
 * </ul>
 *
 * <p>Timing : le joueur actif joue en {@code MAIN} ou {@code COMBAT}. Pendant une
 * fenêtre de réaction, le défenseur désigné ne peut jouer que des cartes
 * {@code QUICK} (hors de son tour), qui résolvent en plus le déclencheur
 * {@code QUICK}.</p>
 */
public class PlayCardCommand implements GameCommand {

    private final String playerId;
    private final UUID cardInstanceId;
    private final UUID targetInstanceId;

    public PlayCardCommand(String playerId, UUID cardInstanceId) {
        this(playerId, cardInstanceId, null);
    }

    /**
     * @param playerId         ordonnateur
     * @param cardInstanceId   carte à jouer (main, ou Legends Area pour une Legend)
     * @param targetInstanceId cible : hôte du Gear (obligatoire) ou cible d'effet (optionnelle)
     */
    public PlayCardCommand(String playerId, UUID cardInstanceId, UUID targetInstanceId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Le joueur est obligatoire");
        }
        if (cardInstanceId == null) {
            throw new IllegalArgumentException("La carte à jouer est obligatoire");
        }
        this.playerId = playerId;
        this.cardInstanceId = cardInstanceId;
        this.targetInstanceId = targetInstanceId;
    }

    @Override
    public String getPlayerId() {
        return playerId;
    }

    public UUID getCardInstanceId() {
        return cardInstanceId;
    }

    public UUID getTargetInstanceId() {
        return targetInstanceId;
    }

    @Override
    public void validate(GameState state) throws GameRuleException {
        GameCommand.requireGameOngoing(state);
        Player player = GameCommand.requireKnownPlayer(state, playerId);

        boolean reacting = state.isReactionWindowOpen()
                && state.getReactionWindow().getDefendingPlayerId().equals(playerId);
        if (!reacting) {
            GameCommand.requireActivePlayer(state, playerId);
            Phase phase = state.getPhase();
            if (phase != Phase.MAIN && phase != Phase.COMBAT) {
                throw new GameRuleException("On ne joue des cartes qu'en phase Main ou Combat");
            }
        }

        CardInstance card = findPlayable(state, player);

        if (reacting && !card.isQuick()) {
            throw new GameRuleException("En réaction, seules les cartes QUICK peuvent être jouées");
        }

        if (card.isLegend()) {
            if (card.getZone() != Zone.LEGENDS_AREA || !card.isFaceDown()) {
                throw new GameRuleException("Seule une Legend face cachée de la Legends Area peut être retournée");
            }
            requireStreetCred(player, card);
            return;
        }

        if (card.getZone() != Zone.HAND) {
            throw new GameRuleException("Cette carte n'est pas jouable depuis " + card.getZone());
        }
        requireStreetCred(player, card);
        int toPay = Math.max(0, card.getEffectiveCost() - player.getCostDiscount());
        if (player.getAvailableEddies() < toPay) {
            throw new GameRuleException("Eddies insuffisants : " + player.getAvailableEddies()
                    + " pour un coût de " + toPay);
        }

        if (card.isGear()) {
            requireGearHost(state, player);
        }
    }

    @Override
    public List<GameEvent> execute(GameState state) throws GameRuleException {
        validate(state);
        int mark = state.getEventLog().size();
        Player player = state.getPlayer(playerId);
        CardInstance card = findPlayable(state, player);
        RuleEngine engine = new RuleEngine();

        boolean reacting = state.isReactionWindowOpen()
                && state.getReactionWindow().getDefendingPlayerId().equals(playerId);

        if (card.isLegend()) {
            card.setFaceDown(false);
            state.appendEvent(GameEventType.LEGEND_FLIPPED, playerId,
                    "legend retournée : " + card.getName());
            engine.resolveEffects(state, card, TriggerType.FLIP, null);
            return GameCommand.eventsSince(state, mark);
        }

        int toPay = Math.max(0, card.getEffectiveCost() - player.getCostDiscount());
        player.spendEddies(toPay);

        CardInstance effectTarget = null;
        if (targetInstanceId != null) {
            Optional<CardInstance> lookup = state.findInstance(targetInstanceId);
            if (lookup.isPresent()) {
                effectTarget = lookup.get();
            }
        }

        if (card.isUnit()) {
            player.moveToZone(card, Zone.FIELD);
            card.setExhausted(false);
            card.setFaceDown(false);
            card.setSummoningSickness(!card.hasGoSolo());
            state.appendEvent(GameEventType.CARD_PLAYED, playerId,
                    "unit jouée : " + card.getName() + " (coût " + toPay + ")");
            engine.resolveEffects(state, card, TriggerType.ON_PLAY, effectTarget);
            if (reacting && card.isQuick()) {
                engine.resolveEffects(state, card, TriggerType.QUICK, effectTarget);
            }
            return GameCommand.eventsSince(state, mark);
        }

        if (card.isProgram()) {
            state.appendEvent(GameEventType.CARD_PLAYED, playerId,
                    "program joué : " + card.getName() + " (coût " + toPay + ")");
            engine.resolveEffects(state, card, TriggerType.ON_PLAY, effectTarget);
            if (reacting && card.isQuick()) {
                engine.resolveEffects(state, card, TriggerType.QUICK, effectTarget);
            }
            if (card.getZone() == Zone.HAND) {
                player.moveToZone(card, Zone.TRASH);
            }
            return GameCommand.eventsSince(state, mark);
        }

        // Gear : attachement à l'hôte validé.
        CardInstance host = state.findInstance(targetInstanceId).get();
        player.moveToZone(card, Zone.FIELD);
        card.setExhausted(false);
        card.setFaceDown(false);
        card.setSummoningSickness(false);
        card.setAttachedTo(host.getInstanceId());
        host.getAttachments().add(card.getInstanceId());
        state.appendEvent(GameEventType.CARD_PLAYED, playerId,
                "gear équipé : " + card.getName() + " (coût " + toPay + ")");
        engine.resolveEffects(state, card, TriggerType.ON_PLAY, host);
        if (reacting && card.isQuick()) {
            engine.resolveEffects(state, card, TriggerType.QUICK, host);
        }
        return GameCommand.eventsSince(state, mark);
    }

    private CardInstance findPlayable(GameState state, Player player) throws GameRuleException {
        Optional<CardInstance> inHand = player.findIn(Zone.HAND, cardInstanceId);
        if (inHand.isPresent()) {
            return inHand.get();
        }
        Optional<CardInstance> legend = player.findIn(Zone.LEGENDS_AREA, cardInstanceId);
        if (legend.isPresent()) {
            return legend.get();
        }
        Optional<Player> owner = state.findInstanceOwner(cardInstanceId);
        if (!owner.isPresent() || !owner.get().getId().equals(playerId)) {
            throw new GameRuleException("Carte introuvable ou contrôlée par le rival");
        }
        throw new GameRuleException("Cette carte n'est pas jouable depuis sa zone");
    }

    private void requireStreetCred(Player player, CardInstance card) throws GameRuleException {
        Integer threshold = card.getStreetCredThreshold();
        if (threshold != null && player.getStreetCred() < threshold) {
            throw new GameRuleException("Street Cred insuffisant : " + player.getStreetCred()
                    + " pour un seuil de " + threshold);
        }
    }

    private CardInstance requireGearHost(GameState state, Player player) throws GameRuleException {
        if (targetInstanceId == null) {
            throw new GameRuleException("Un Gear doit viser une Unit alliée à équiper");
        }
        Optional<CardInstance> lookup = state.findInstance(targetInstanceId);
        if (!lookup.isPresent()) {
            throw new GameRuleException("Hôte du Gear introuvable");
        }
        CardInstance host = lookup.get();
        if (host.getZone() != Zone.FIELD || !host.isUnit()) {
            throw new GameRuleException("Un Gear ne s'équipe que sur une Unit du Field");
        }
        if (!host.getOwnerId().equals(player.getId())) {
            throw new GameRuleException("Un Gear ne s'équipe que sur une Unit alliée");
        }
        return host;
    }
}
