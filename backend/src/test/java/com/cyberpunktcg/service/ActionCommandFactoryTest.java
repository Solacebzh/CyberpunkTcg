package com.cyberpunktcg.service;

import com.cyberpunktcg.api.dto.ws.GameCommandDTO;
import com.cyberpunktcg.engine.GameRuleException;
import com.cyberpunktcg.engine.command.AttackCommand;
import com.cyberpunktcg.engine.command.EndTurnCommand;
import com.cyberpunktcg.engine.command.PlayCardCommand;
import com.cyberpunktcg.engine.command.SellCardCommand;
import com.cyberpunktcg.engine.command.SpendResourceCommand;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Vérifie la traduction DTO STOMP → commande du moteur, avec playerId
 * toujours injecté depuis la session (jamais depuis le payload).
 */
class ActionCommandFactoryTest {

    private final ActionCommandFactory factory = new ActionCommandFactory();

    @Test
    void playCard_avecCibleGear() {
        UUID card = UUID.randomUUID();
        UUID host = UUID.randomUUID();
        GameCommandDTO dto = new GameCommandDTO("play_card", card, null, host,
                null, null, null, null, "req-1");

        PlayCardCommand command = (PlayCardCommand) factory.build(dto, "V");

        assertThat(command.getPlayerId()).isEqualTo("V");
        assertThat(command.getCardInstanceId()).isEqualTo(card);
        assertThat(command.getTargetInstanceId()).isEqualTo(host);
    }

    @Test
    void attack_sansCible_volDeGig() {
        UUID attacker = UUID.randomUUID();
        GameCommandDTO dto = new GameCommandDTO("ATTACK", attacker, null, null,
                null, null, null, null, null);

        AttackCommand command = (AttackCommand) factory.build(dto, "V");

        assertThat(command.isGigSteal()).isTrue();
    }

    @Test
    void attack_avecCible() {
        UUID attacker = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        GameCommandDTO dto = new GameCommandDTO("ATTACK", attacker, null, target,
                null, null, null, null, null);

        AttackCommand command = (AttackCommand) factory.build(dto, "V");

        assertThat(command.isGigSteal()).isFalse();
        assertThat(command.getTargetInstanceId()).isEqualTo(target);
    }

    @Test
    void spendResource_cibleUnique_etActionsHistoriquesAliases() {
        UUID card = UUID.randomUUID();
        GameCommandDTO dto = new GameCommandDTO("SPEND_RESOURCE", card, null, null,
                null, null, null, null, null);

        SpendResourceCommand command = (SpendResourceCommand) factory.build(dto, "V");

        assertThat(command.getPlayerId()).isEqualTo("V");
        assertThat(command.getResourceInstanceId()).isEqualTo(card);
        assertThat(command.actionType()).isEqualTo("SPEND_RESOURCE");

        // Actions historiques du protocole : mêmes règles unifiées (sous-classes),
        // action de journal conservée.
        assertThat(factory.build(new GameCommandDTO("spend_legend", card, null, null,
                null, null, null, null, null), "V"))
                .isInstanceOf(SpendResourceCommand.class);
        assertThat(factory.build(new GameCommandDTO("SPEND_EDDIES", card, null, null,
                null, null, null, null, null), "V"))
                .isInstanceOf(SpendResourceCommand.class);

        // Cible obligatoire.
        assertThatThrownBy(() -> factory.build(
                new GameCommandDTO("SPEND_RESOURCE", null, null, null,
                        null, null, null, null, null), "V"))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("instanceId");
    }

    @Test
    void sellCard_etEndTurn() {
        UUID card = UUID.randomUUID();
        assertThat(factory.build(new GameCommandDTO("SELL_CARD", card, null, null,
                null, null, null, null, null), "V"))
                .isInstanceOf(SellCardCommand.class);
        assertThat(factory.build(new GameCommandDTO("end_turn", null, null, null,
                null, null, null, null, null), "V"))
                .isInstanceOf(EndTurnCommand.class);
    }

    @Test
    void actionInconnue_ouCibleManquante_rejetees() {
        assertThatThrownBy(() -> factory.build(
                new GameCommandDTO("DANCE", null, null, null, null, null, null, null, null), "V"))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("inconnue");
        assertThatThrownBy(() -> factory.build(
                new GameCommandDTO("PLAY_CARD", null, null, null, null, null, null, null, null), "V"))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("instanceId");
        assertThatThrownBy(() -> factory.build(null, "V"))
                .isInstanceOf(GameRuleException.class);
    }
}
