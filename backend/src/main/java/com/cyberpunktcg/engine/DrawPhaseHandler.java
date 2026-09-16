package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.DieRoll;
import com.cyberpunktcg.domain.game.DrawStep;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameLog;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Player;

import java.util.List;
import java.util.Optional;

/**
 * Phase {@link Phase#DRAW} <strong>interactive</strong> (Mini-Feature 5) : pilote
 * la machine à états {@link DrawStep} du START PHASE des règles officielles.
 *
 * <pre>
 *   EndTurnCommand ──▶ DRAW_START ──▶ AWAITING_DRAW ──(DRAW_CARD)──▶ AWAITING_DIE_SELECT
 *                                                                           │
 *                              MAIN ◀── DRAW_COMPLETE ◀── ROLLING_DIE ◀──(SELECT_DIE)
 * </pre>
 *
 * <ul>
 *   <li>{@link #beginDrawPhase} — ouverture du tour ({@code DRAW_START}) : la
 *   phase passe à {@code DRAW} ; {@code EndTurnCommand} vérifie ensuite la
 *   victoire à 7 Gigs (avant toute pioche) ;</li>
 *   <li>{@link #readyAndAwaitDraw} — toutes les cartes sont redressées, la réserve
 *   d'Eddies retombe à 0, puis la partie attend le clic sur la pioche
 *   ({@code AWAITING_DRAW}) ;</li>
 *   <li>{@link #drawCard} — le joueur pioche 1 carte (deck vide = défaite
 *   immédiate) puis la partie attend le choix du dé ({@code AWAITING_DIE_SELECT}) ;</li>
 *   <li>{@link #rollSelectedDie} — le serveur lance le dé choisi
 *   ({@code ROLLING_DIE}), range le résultat dans la Gig Area, puis passe
 *   automatiquement en {@link Phase#MAIN} ({@code DRAW_COMPLETE}).</li>
 * </ul>
 *
 * <p>La validation des commandes ({@code DrawCardCommand}, {@code SelectDieCommand})
 * s'appuie sur {@link #requireStep} ; ce gestionnaire ne fait que muter l'état et
 * journaliser (événements publics + journal de diagnostic).</p>
 */
public final class DrawPhaseHandler {

    private DrawPhaseHandler() {
        // Classe utilitaire non instanciable.
    }

    /** {@code DRAW_START} : ouvre la phase DRAW du joueur entrant (avant la vérification de victoire). */
    public static void beginDrawPhase(GameState state, String playerId) {
        state.setPhase(Phase.DRAW);
        state.setDrawStep(DrawStep.DRAW_START);
        state.appendEvent(GameEventType.PHASE_CHANGED, playerId, "phase Draw");
        state.logInfo(playerId, "PHASE",
                "Début du tour " + state.getTurn().getNumber() + " — Joueur " + playerId
                        + " (phase DRAW)",
                GameLog.details("turn", state.getTurn().getNumber(), "phase", "DRAW",
                        "step", DrawStep.DRAW_START.name()));
    }

    /**
     * Ouverture de la phase DRAW du tout premier tour du premier joueur
     * (Mini-Feature 5.1 — « Tour 1 du Premier Joueur »).
     *
     * <p>Identique à {@link #readyAndAwaitDraw} MAIS <strong>sans redresser les
     * cartes</strong> : le malus de mise en place du premier joueur (R1.4 — ses 2
     * Legends les plus à gauche déjà inclinées, « doesn't ready them on their
     * first turn ») doit demeurer pendant tout son tour 1. On place donc le
     * joueur entrant directement en {@link DrawStep#AWAITING_DRAW}, prêt à cliquer
     * sur sa pioche puis à choisir son dé Gig. La phase {@link Phase#MAIN} ne
     * s'ouvre qu'après ces deux actions (cf. {@link #drawCard},
     * {@link #rollSelectedDie}). Le redressement (et donc la levée du malus)
     * intervient au tour suivant du premier joueur, via
     * {@link #readyAndAwaitDraw} appelé par {@code EndTurnCommand}.</p>
     *
     * @param state    état de la partie (tour 1, joueur entrant = premier joueur)
     * @param playerId identifiant du premier joueur
     */
    public static void beginFirstPlayerDrawPhase(GameState state, String playerId) {
        Player incoming = state.getPlayer(playerId);
        state.setPhase(Phase.DRAW);
        state.setDrawStep(DrawStep.AWAITING_DRAW);
        state.appendEvent(GameEventType.PHASE_CHANGED, playerId, "phase Draw");
        state.logInfo(playerId, "PHASE",
                "Début du tour 1 — premier joueur " + playerId
                        + " (phase DRAW, malus de mise en place maintenu : "
                        + incoming.countSpentLegends() + " Legend(s) inclinée(s))",
                GameLog.details("turn", 1, "phase", "DRAW", "step", DrawStep.AWAITING_DRAW.name(),
                        "firstPlayerMalus", true, "legendsSpent", incoming.countSpentLegends()));
    }

    /** Étape 1 « READY SPENT CARDS » puis attente de la pioche ({@code AWAITING_DRAW}). */
    public static void readyAndAwaitDraw(GameState state, String playerId) {
        Player incoming = state.getPlayer(playerId);
        // Règle officielle § START PHASE, étape 1 « READY SPENT CARDS » (R2/R3/R6/R8) :
        // ON REDRESSE TOUT (Field, Legends Area, Eddies Area) et la réserve d'Eddies
        // retombe à 0. C'est aussi ce qui lève le malus de mise en place du premier
        // joueur (R1.4) : ses 2 Legends inclinées pendant le tour 1 sont redressées
        // au début de son tour 2.
        incoming.startTurn();
        state.logInfo(playerId, "TURN_RESET",
                "Début de tour : 0 Eddie, cartes redressées ; "
                        + incoming.legendsAvailableForEddies().size() + "/"
                        + incoming.getLegendsArea().size() + " Legend(s) prêtes, "
                        + incoming.eddiesAvailableForEddies().size() + "/"
                        + incoming.getEddiesArea().size() + " Eddies prêtes",
                GameLog.details("legendsReady", incoming.legendsAvailableForEddies().size(),
                        "legendsTotal", incoming.getLegendsArea().size(),
                        "eddiesReady", incoming.eddiesAvailableForEddies().size(),
                        "eddiesTotal", incoming.getEddiesArea().size(),
                        "eddies", incoming.getEddies()));

        state.setDrawStep(DrawStep.AWAITING_DRAW);
        state.logInfo(playerId, "DRAW_STEP",
                "Phase DRAW : Joueur " + playerId + " doit cliquer sur sa pioche (" + incoming.getDeck().size()
                        + " carte(s) dans le deck)",
                GameLog.details("step", DrawStep.AWAITING_DRAW.name(), "deck", incoming.getDeck().size(),
                        "phase", "DRAW"));
    }

    /**
     * Étape 2 « DRAW 1 » : pioche une carte pour le joueur actif puis attend le
     * choix du dé. Un deck vide désigne le rival vainqueur (règle officielle §7).
     */
    public static void drawCard(GameState state, String playerId) {
        Player player = state.getPlayer(playerId);
        int drawn = state.drawCards(playerId, 1);
        if (state.isGameOver()) {
            state.logFailed(playerId, "DRAW",
                    "Phase DRAW : Joueur " + playerId + " doit piocher mais son deck est vide → DÉFAITE",
                    GameLog.details("deck", 0, "phase", "DRAW", "step", DrawStep.AWAITING_DRAW.name()));
            state.appendEvent(GameEventType.GAME_WON, state.getWinnerId(), state.getEndReason());
            state.logSuccess(state.getWinnerId(), "VICTORY",
                    "VICTOIRE : Joueur " + state.getWinnerId() + " gagne par deck-out de " + playerId,
                    GameLog.details("reason", state.getEndReason()));
            return;
        }
        if (drawn > 0) {
            CardInstance drawnCard = player.getHand().get(player.getHand().size() - 1);
            state.appendEvent(GameEventType.CARD_DRAWN, playerId, "pioche 1 carte");
            state.logSuccess(playerId, "DRAW",
                    "Phase DRAW : Joueur " + playerId + " pioche " + drawnCard.getName(),
                    GameLog.details("card", drawnCard.getName(), "cardId", drawnCard.getCardId(),
                            "hand", player.getHand().size(), "deck", player.getDeck().size(),
                            "phase", "DRAW"));
        }

        List<String> selectable = player.selectableFixerDice();
        if (selectable.isEmpty()) {
            // Plus aucun dé dans la Fixer Area (cas limite : 7e tour et plus) : rien à
            // choisir, la phase DRAW se termine d'elle-même.
            state.logInfo(playerId, "GIG_ROLL",
                    "Phase DRAW : plus aucun dé Gig dans la Fixer Area, pas de lancer ce tour",
                    GameLog.details("fixerDice", 0, "phase", "DRAW"));
            completeDrawPhase(state, playerId);
            return;
        }
        state.setDrawStep(DrawStep.AWAITING_DIE_SELECT);
        state.logInfo(playerId, "DRAW_STEP",
                "Phase DRAW : Joueur " + playerId + " doit choisir un dé Gig parmi " + selectable
                        + (selectable.contains(Player.LAST_DIE) ? "" : " (le d20 se lance en dernier)"),
                GameLog.details("step", DrawStep.AWAITING_DIE_SELECT.name(),
                        "selectable", String.join(",", selectable),
                        "fixerDice", String.join(",", player.getFixerDice()), "phase", "DRAW"));
    }

    /**
     * Étape 3 « GAIN A GIG » : lance le dé choisi (déjà validé par la commande),
     * range le résultat dans la Gig Area puis passe en {@link Phase#MAIN}.
     *
     * @param die dé normalisé, présent dans la Fixer Area et autorisé par la règle du d20
     */
    public static DieRoll rollSelectedDie(GameState state, String playerId, String die) {
        Player player = state.getPlayer(playerId);
        state.setDrawStep(DrawStep.ROLLING_DIE);
        Optional<DieRoll> roll = state.rollFixerDie(playerId, die);
        if (!roll.isPresent()) {
            throw new GameRuleException("Le dé " + die + " n'est plus dans la Fixer Area");
        }
        state.appendEvent(GameEventType.GIG_ROLLED, playerId,
                "lancer " + roll.get().getDie() + " → " + roll.get().getValue()
                        + " (total " + player.getGigCount() + " Gigs)");
        state.logSuccess(playerId, "GIG_ROLL",
                "Lancer de Gig : " + roll.get().getDie() + " → " + roll.get().getValue()
                        + " (total " + player.getGigCount() + " Gigs, Street Cred "
                        + player.getStreetCred() + ")",
                GameLog.details("die", roll.get().getDie(), "value", roll.get().getValue(),
                        "gigs", player.getGigCount(), "streetCred", player.getStreetCred(),
                        "step", DrawStep.ROLLING_DIE.name()));
        completeDrawPhase(state, playerId);
        return roll.get();
    }

    /** {@code DRAW_COMPLETE} : passage automatique en phase MAIN. */
    private static void completeDrawPhase(GameState state, String playerId) {
        Player player = state.getPlayer(playerId);
        state.setDrawStep(DrawStep.DRAW_COMPLETE);
        state.setPhase(Phase.MAIN);
        state.appendEvent(GameEventType.PHASE_CHANGED, playerId, "phase Main");
        state.logInfo(playerId, "PHASE", "Phase MAIN : Joueur " + playerId
                        + " peut jouer, incliner ses Legends, vendre 1 carte et attaquer",
                GameLog.details("phase", "MAIN", "hand", player.getHand().size(),
                        "eddies", player.getEddies()));
    }

    /**
     * Garde partagée des commandes de la phase DRAW : la partie doit être en
     * phase {@code DRAW} et exactement à l'étape attendue.
     */
    public static void requireStep(GameState state, DrawStep expected) throws GameRuleException {
        if (state.getPhase() != Phase.DRAW) {
            throw new GameRuleException("Cette action n'est possible qu'en phase Draw (phase courante : "
                    + state.getPhase() + ")");
        }
        DrawStep current = state.getDrawStep();
        if (current != expected) {
            throw new GameRuleException(describeExpectation(expected) + " (étape courante : "
                    + (current == null ? "aucune" : current) + ")");
        }
    }

    private static String describeExpectation(DrawStep expected) {
        switch (expected) {
            case AWAITING_DRAW:
                return "La pioche n'est attendue qu'à l'étape AWAITING_DRAW";
            case AWAITING_DIE_SELECT:
                return "Le choix du dé n'est attendu qu'à l'étape AWAITING_DIE_SELECT (piochez d'abord)";
            default:
                return "Étape attendue : " + expected;
        }
    }
}
