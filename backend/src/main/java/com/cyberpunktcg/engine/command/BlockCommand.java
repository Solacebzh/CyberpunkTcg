package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.CombatStep;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.PendingAttack;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.domain.game.Zone;
import com.cyberpunktcg.engine.CombatResolver;
import com.cyberpunktcg.engine.GameRuleException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Mini-Feature 6 (règle B) — le <strong>défenseur</strong> intercepte une attaque
 * avec un ou plusieurs {@code {Blocker}} prêts.
 *
 * <p>Règle officielle § REACT — « <strong>BLOCKER</strong> Spend a Unit with the
 * BLOCKER keyword to redirect the attack to it instead » : le blocage est un
 * <em>choix</em> du défenseur (il peut renoncer avec {@link DeclineBlockCommand}),
 * jamais une contrainte imposée à l'attaquant.</p>
 *
 * <p>Blocage multiple (arbitrage joueur confirmé) : le défenseur peut dépenser
 * <strong>tous</strong> ses Blockers prêts. Chaque Blocker désigné est incliné
 * ({@code exhausted = true}) et résout ses compétences (déclencheurs
 * {@code ON_BLOCK} puis {@code ON_ATTACK}) dans l'ordre de déclaration ;
 * <strong>seul le DERNIER Blocker</strong> de la liste encaisse les dégâts du
 * combat contre l'attaquant ({@link CombatResolver#resolveBlock}).</p>
 *
 * <p>Une attaque redirigée ne vole <strong>aucun</strong> Gig, même si le Blocker
 * est vaincu (règle officielle § ATTACKING).</p>
 *
 * <p>Action filaire : {@code USE_BLOCKER}, Blockers transmis dans {@code cardIds}
 * (ordre significatif) ou {@code instanceId} pour un blocage simple.</p>
 */
public class BlockCommand implements GameCommand {

    private final String playerId;
    private final List<UUID> blockerInstanceIds;

    public BlockCommand(String playerId, List<UUID> blockerInstanceIds) {
        if (playerId == null) {
            throw new IllegalArgumentException("Le joueur est obligatoire");
        }
        this.playerId = playerId;
        this.blockerInstanceIds = blockerInstanceIds == null
                ? new ArrayList<UUID>()
                : new ArrayList<UUID>(blockerInstanceIds);
    }

    /** Blocage simple (un seul Blocker). */
    public BlockCommand(String playerId, UUID blockerInstanceId) {
        this(playerId, blockerInstanceId == null
                ? Collections.<UUID>emptyList()
                : Collections.singletonList(blockerInstanceId));
    }

    @Override
    public String getPlayerId() {
        return playerId;
    }

    @Override
    public String actionType() {
        return "USE_BLOCKER";
    }

    /** Blockers désignés, dans l'ordre de déclaration (le dernier encaisse les dégâts). */
    public List<UUID> getBlockerInstanceIds() {
        return Collections.unmodifiableList(blockerInstanceIds);
    }

    @Override
    public String describe() {
        return "bloquer avec " + blockerInstanceIds.size() + " Blocker(s)";
    }

    @Override
    public String describe(GameState state) {
        StringBuilder names = new StringBuilder();
        for (UUID id : blockerInstanceIds) {
            Optional<CardInstance> found = state.findInstance(id);
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(found.map(CardInstance::getName).orElse("Blocker inconnu"));
        }
        return "bloquer l'attaque avec " + (names.length() == 0 ? "aucun Blocker" : names.toString());
    }

    @Override
    public void validate(GameState state) throws GameRuleException {
        GameCommand.requireGameOngoing(state);
        GameCommand.requireKnownPlayer(state, playerId);
        PendingAttack pending = requireBlockWindow(state);
        Player defender = state.getPlayer(pending.getDefendingPlayerId());

        if (blockerInstanceIds.isEmpty()) {
            throw new GameRuleException("Aucun Blocker désigné pour bloquer "
                    + "(utilise DECLINE_BLOCK pour renoncer à l'interception)");
        }
        List<UUID> distinct = new ArrayList<UUID>();
        for (UUID id : blockerInstanceIds) {
            if (id == null) {
                throw new GameRuleException("Identifiant de Blocker absent");
            }
            if (distinct.contains(id)) {
                throw new GameRuleException("Blocker désigné en double : " + id);
            }
            distinct.add(id);
            CardInstance blocker = defender.findIn(Zone.FIELD, id)
                    .orElseThrow(() -> new GameRuleException("Blocker introuvable sur ton Field : " + id));
            if (!blocker.isUnit()) {
                throw new GameRuleException("Seule une Unit peut bloquer : " + blocker.getName());
            }
            if (!blocker.isBlocker()) {
                throw new GameRuleException(blocker.getName() + " n'a pas le mot-clé BLOCKER");
            }
            if (blocker.isExhausted()) {
                throw new GameRuleException("Ce BLOCKER n'est pas prêt (déjà incliné) : " + blocker.getName());
            }
        }
    }

    @Override
    public List<GameEvent> execute(GameState state) throws GameRuleException {
        validate(state);
        int mark = state.getEventLog().size();
        PendingAttack pending = requireBlockWindow(state);
        Player defender = state.getPlayer(pending.getDefendingPlayerId());
        List<CardInstance> blockers = new ArrayList<CardInstance>(blockerInstanceIds.size());
        for (UUID id : blockerInstanceIds) {
            Optional<CardInstance> blocker = defender.findIn(Zone.FIELD, id);
            if (blocker.isPresent()) {
                blockers.add(blocker.get());
            }
        }
        CombatResolver.resolveBlock(state, blockers);
        return GameCommand.eventsSince(state, mark);
    }

    /** Fenêtre « Utiliser Blocker ? » ouverte pour ce défenseur. */
    private PendingAttack requireBlockWindow(GameState state) throws GameRuleException {
        PendingAttack pending = state.getPendingAttack();
        if (pending == null || pending.getStep() != CombatStep.AWAITING_BLOCK) {
            throw new GameRuleException("Aucune attaque à bloquer : la fenêtre « Utiliser Blocker ? » "
                    + "n'est pas ouverte");
        }
        if (!pending.getDefendingPlayerId().equals(playerId)) {
            throw new GameRuleException("Seul le défenseur (" + pending.getDefendingPlayerId()
                    + ") peut bloquer cette attaque");
        }
        return pending;
    }
}
