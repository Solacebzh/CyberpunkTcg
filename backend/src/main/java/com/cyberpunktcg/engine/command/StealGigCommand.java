package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.CombatStep;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameLog;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.GigDie;
import com.cyberpunktcg.domain.game.PendingAttack;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.engine.CombatResolver;
import com.cyberpunktcg.engine.GameRuleException;
import com.cyberpunktcg.engine.RuleEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Mini-Feature 6 (règle C) — l'attaquant choisit les dés Gigs à voler après une
 * attaque directe non bloquée.
 *
 * <p>Règle officielle § ATTACKING — « <strong>STEAL!</strong> Choose a rival Gig
 * die and move it to your friendly Gig area. Units steal an extra Gig for every
 * 10 power (and 0 Gigs at power 0) » :</p>
 * <ul>
 *   <li>quota théorique {@code N = (power / 10) + 1} si power ≥ 1, sinon 0
 *   ({@link RuleEngine#calculateQuota}) ;</li>
 *   <li><strong>plafond strict</strong> {@code M = min(N, dés Gigs actifs du
 *   défenseur)} ({@link RuleEngine#calculateActualStealable}) : on ne vole que des
 *   dés <em>déjà lancés</em>, jamais un dé de la Fixer Area, et on ne crée jamais
 *   de dé ;</li>
 *   <li>le joueur désigne exactement {@code M} dés ({@code chosenDieIds}) ; chaque
 *   dé transféré conserve son identifiant, son type et sa valeur exacte
 *   (« un D8 affichant 5 reste un D8 affichant 5 ») ;</li>
 *   <li>{@code M = 0} n'ouvre aucun choix : l'attaque a déjà été résolue sans vol
 *   (voir {@code CombatResolver}).</li>
 * </ul>
 *
 * <p>Un vol ne déclenche jamais la victoire : les 7 dés sont vérifiés au début de
 * la phase DRAW ({@code EndTurnCommand}).</p>
 *
 * <p>Action filaire : {@code STEAL_GIG}, identifiants de dés transmis dans
 * {@code dice} (repli : {@code cardIds} ou {@code chosen} séparé par des
 * virgules). Le {@code gameId} vient du canal STOMP
 * ({@code /app/game/{gameId}/action}), comme pour toutes les commandes : le
 * moteur ne le porte pas.</p>
 */
public class StealGigCommand implements GameCommand {

    private final String playerId;
    private final List<String> chosenDieIds;

    public StealGigCommand(String playerId, List<String> chosenDieIds) {
        if (playerId == null) {
            throw new IllegalArgumentException("Le joueur est obligatoire");
        }
        this.playerId = playerId;
        this.chosenDieIds = chosenDieIds == null
                ? new ArrayList<String>()
                : new ArrayList<String>(chosenDieIds);
    }

    @Override
    public String getPlayerId() {
        return playerId;
    }

    @Override
    public String actionType() {
        return "STEAL_GIG";
    }

    /** Identifiants des dés Gigs choisis par l'attaquant. */
    public List<String> getChosenDieIds() {
        return Collections.unmodifiableList(chosenDieIds);
    }

    @Override
    public String describe() {
        return "voler " + chosenDieIds.size() + " dé(s) Gig au rival";
    }

    @Override
    public String describe(GameState state) {
        StringBuilder values = new StringBuilder();
        for (String dieId : chosenDieIds) {
            Optional<GigDie> die = findActiveDie(state, dieId);
            if (values.length() > 0) {
                values.append(", ");
            }
            values.append(die.isPresent() ? die.get().die() + " → " + die.get().value() : "dé inconnu");
        }
        return "voler " + (values.length() == 0 ? "aucun dé Gig" : values.toString());
    }

    @Override
    public void validate(GameState state) throws GameRuleException {
        GameCommand.requireGameOngoing(state);
        GameCommand.requireActivePlayer(state, playerId);
        PendingAttack pending = state.getPendingAttack();
        if (pending == null || pending.getStep() != CombatStep.AWAITING_STEAL_CHOICE) {
            throw new GameRuleException("Aucun vol de Gig en attente du choix des dés "
                    + "(attaque directe non bloquée uniquement)");
        }
        if (!pending.getAttackerPlayerId().equals(playerId)) {
            throw new GameRuleException("Seul l'attaquant (" + pending.getAttackerPlayerId()
                    + ") choisit les dés Gigs à voler");
        }

        Player defender = state.getPlayer(pending.getDefendingPlayerId());
        RuleEngine engine = new RuleEngine();
        CardInstance attacker = state.findInstance(pending.getAttackerInstanceId()).orElse(null);
        int power = attacker == null ? 0 : state.totalPowerFor(attacker);
        int quota = engine.calculateQuota(power);
        int expected = engine.calculateActualStealable(power, defender.getActiveGigCount());
        if (chosenDieIds.size() != expected) {
            throw new GameRuleException("Tu dois choisir exactement " + expected + " dé(s) Gig"
                    + " (quota N = " + quota + " pour power " + power + ", plafond strict M = "
                    + expected + " car le défenseur n'a que " + defender.getActiveGigCount()
                    + " dé(s) Gig actif(s))");
        }

        List<String> distinct = new ArrayList<String>();
        for (String dieId : chosenDieIds) {
            if (dieId == null || dieId.isBlank()) {
                throw new GameRuleException("Identifiant de dé Gig absent");
            }
            String id = dieId.trim();
            if (distinct.contains(id)) {
                throw new GameRuleException("Dé Gig choisi en double : " + id);
            }
            distinct.add(id);
            if (!defender.findActiveGig(id).isPresent()) {
                throw new GameRuleException("Le dé " + id + " n'est pas un dé Gig actif de la Gig Area "
                        + "du défenseur (seuls les dés déjà lancés sont volables)");
            }
        }
    }

    @Override
    public List<GameEvent> execute(GameState state) throws GameRuleException {
        validate(state);
        int mark = state.getEventLog().size();
        PendingAttack pending = state.getPendingAttack();
        List<String> ids = new ArrayList<String>(chosenDieIds.size());
        for (String dieId : chosenDieIds) {
            ids.add(dieId == null ? null : dieId.trim());
        }
        List<GigDie> stolen = CombatResolver.chooseStolenDice(state, ids);
        if (stolen.isEmpty()) {
            state.logFailed(playerId, actionType(),
                    "Vol de Gig sans effet (aucun dé transféré)",
                    GameLog.details("requested", ids.size(),
                            "defender", pending == null ? null : pending.getDefendingPlayerId()));
        }
        return GameCommand.eventsSince(state, mark);
    }

    /** Cherche un dé actif dans les deux Gig Areas (description de l'intention). */
    private Optional<GigDie> findActiveDie(GameState state, String dieId) {
        if (dieId == null) {
            return Optional.empty();
        }
        for (Player player : state.getPlayers()) {
            Optional<GigDie> found = player.findActiveGig(dieId.trim());
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
