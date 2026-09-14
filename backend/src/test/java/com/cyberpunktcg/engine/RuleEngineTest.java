package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.card.CardKeyword;
import com.cyberpunktcg.domain.card.CardType;
import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.engine.command.PlayCardCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests purs du moteur d'effets (sans Spring) : déclencheurs, handlers,
 * mini-langage et heuristiques.
 */
class RuleEngineTest {

    private GameState state;
    private RuleEngine engine;

    @BeforeEach
    void setUp() {
        state = GameFixtures.freshDuel();
        engine = new RuleEngine();
    }

    @Test
    void onPlayDraw_piochesDesCartes() {
        CardInstance source = GameFixtures.fieldCard(state, "p1",
                GameFixtures.unit("scribe", 1, 2, "ON_PLAY:DRAW:2"));
        int hand = state.getPlayer("p1").getHand().size();
        int deck = state.getPlayer("p1").getDeck().size();

        engine.resolveEffects(state, source, TriggerType.ON_PLAY, null);

        assertThat(state.getPlayer("p1").getHand()).hasSize(hand + 2);
        assertThat(state.getPlayer("p1").getDeck()).hasSize(deck - 2);
    }

    @Test
    void flipGrantPower_buffLaLegend() {
        CardInstance legend = GameFixtures.legendCard(state, "p1",
                GameFixtures.legend("matriarch", "FLIP:GRANT_POWER:3:SELF"), true);

        engine.resolveEffects(state, legend, TriggerType.FLIP, null);

        assertThat(legend.getEffectivePowerOrZero()).isEqualTo(5);
    }

    @Test
    void damage_reduitPuisVainc_etDeclencheOnDeath() {
        CardInstance source = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("striker", 1, 4));
        CardInstance victim = GameFixtures.fieldCard(state, "p2",
                GameFixtures.unit("victim", 1, 3, "ON_DEATH:DRAW:1"));
        int hand = state.getPlayer("p2").getHand().size();

        engine.resolveEffect(state, source,
                new GameEffect(TriggerType.ON_PLAY, EffectType.DAMAGE, 2, EffectTarget.TARGET_UNIT), victim);
        assertThat(victim.getEffectivePowerOrZero()).isEqualTo(1);
        assertThat(state.getPlayer("p2").getField()).contains(victim);

        engine.resolveEffect(state, source,
                new GameEffect(TriggerType.ON_PLAY, EffectType.DAMAGE, 2, EffectTarget.TARGET_UNIT), victim);
        assertThat(state.getPlayer("p2").getField()).doesNotContain(victim);
        assertThat(state.getPlayer("p2").getTrash()).contains(victim);
        assertThat(state.getPlayer("p2").getHand()).hasSize(hand + 1);
    }

    @Test
    void heal_soigneLesDegatsSansDepasserLaBase() {
        CardInstance source = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("medic", 1, 2));
        CardInstance patient = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("patient", 1, 3));
        patient.setDamage(2);

        engine.resolveEffect(state, source,
                new GameEffect(TriggerType.ON_PLAY, EffectType.HEAL, 5, EffectTarget.TARGET_UNIT), patient);

        assertThat(patient.getDamage()).isZero();
        assertThat(patient.getEffectivePowerOrZero()).isEqualTo(3);
    }

    @Test
    void stealGig_voleLePlusGrosGig_sansVictoireImmediate() {
        CardInstance source = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("thief", 1, 1));
        GameFixtures.addGigs(state, "p2", 2, 6, 4);

        engine.resolveEffect(state, source,
                new GameEffect(TriggerType.ON_PLAY, EffectType.STEAL_GIG, 1, EffectTarget.RIVAL_PLAYER), null);

        assertThat(state.getPlayer("p1").getGigs()).containsExactly(6);
        assertThat(state.getPlayer("p2").getGigs()).containsExactly(2, 4);
        assertThat(state.isGameOver()).isFalse();
    }

    @Test
    void reduceCost_faitBaisserLeCoutPaye() {
        CardInstance source = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("fixer", 1, 1));
        GameFixtures.giveEddies(state, "p1", 1);
        CardInstance expensive = GameFixtures.handCard(state, "p1", GameFixtures.unit("ogre", 3, 5));

        engine.resolveEffect(state, source,
                new GameEffect(TriggerType.ON_PLAY, EffectType.REDUCE_COST, 2, EffectTarget.SELF_PLAYER), null);

        new PlayCardCommand("p1", expensive.getInstanceId()).execute(state);
        assertThat(state.getPlayer("p1").getEddies()).isZero();
        assertThat(state.getPlayer("p1").getField()).contains(expensive);
    }

    @Test
    void quickTrigger_distinctDeOnPlay() {
        CardInstance quick = GameFixtures.fieldCard(state, "p1",
                GameFixtures.program("flash", 1, "QUICK:DRAW:1"));
        int hand = state.getPlayer("p1").getHand().size();

        engine.resolveEffects(state, quick, TriggerType.ON_PLAY, null);
        assertThat(state.getPlayer("p1").getHand()).hasSize(hand);

        engine.resolveEffects(state, quick, TriggerType.QUICK, null);
        assertThat(state.getPlayer("p1").getHand()).hasSize(hand + 1);
    }

    @Test
    void texteNaturel_drawReconnu_autresIgnorees() {
        CardInstance natural = GameFixtures.fieldCard(state, "p1", GameFixtures.card("natural",
                CardType.PROGRAM, 1, null,
                Collections.<CardKeyword>emptyList(),
                Arrays.asList("When you play this, draw 1.", "Deal 5 damage to everything.")));

        List<GameEffect> effects = EffectParser.parseAbilities(natural);

        assertThat(effects).containsExactly(
                new GameEffect(TriggerType.ON_PLAY, EffectType.DRAW, 1, EffectTarget.SELF_PLAYER));
    }

    @Test
    void dslInvalide_estIgnoreSansErreur() {
        assertThat(EffectParser.parseAbility("n'importe quoi")).isNull();
        assertThat(EffectParser.parseAbility("ON_PLAY:FLY:3")).isNull();
        assertThat(EffectParser.parseAbility("ON_PLAY:DAMAGE:-1")).isNull();
        assertThat(EffectParser.parseAbility("ON_PLAY:DAMAGE:2:NOWHERE")).isNull();
        assertThat(EffectParser.parseAbility(null)).isNull();
        assertThat(EffectParser.parseAbility("ON_PLAY:DRAW:2")).isEqualTo(
                new GameEffect(TriggerType.ON_PLAY, EffectType.DRAW, 2, EffectTarget.SELF_PLAYER));
    }
}
