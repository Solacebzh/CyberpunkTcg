package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.Zone;

import java.util.UUID;

/**
 * Incline une Legend pour gagner un Eddie (« €$ ») — R3 / R6.
 *
 * <p>Mini-Feature 4 : alias léger de la règle unifiée {@link SpendResourceCommand}
 * (R4), qui accepte en cible soit une Legend ({@link Zone#LEGENDS_AREA}), soit
 * une carte vendue ({@link Zone#EDDIES_AREA}). Cette classe ne conserve que
 * l'action filaire {@code SPEND_LEGEND} du protocole WebSocket et la
 * granularité du journal de diagnostic : toute la logique (validations,
 * mutation, journal) est héritée de {@link SpendResourceCommand}.</p>
 *
 * <p>Règle R4 : pendant la Main Phase du joueur actif, la Legend ciblée (face
 * cachée ou non) passe à {@code exhausted = true} et le joueur gagne
 * {@code +1} Eddie ; la carte est redressée au début du tour suivant
 * (START PHASE) et la réserve d'Eddies est remise à 0.</p>
 */
public class SpendLegendCommand extends SpendResourceCommand {

    public SpendLegendCommand(String playerId, UUID legendInstanceId) {
        super(playerId, legendInstanceId);
    }

    @Override
    public String actionType() {
        return "SPEND_LEGEND";
    }

    @Override
    public String describe() {
        return "incliner une Legend pour 1 Eddie";
    }

    /** Accesseur de compatibilité (API précédente). */
    public UUID getLegendInstanceId() {
        return getResourceInstanceId();
    }
}
