package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.Zone;

import java.util.UUID;

/**
 * Incline une carte de l'Eddies Area pour gagner 1 Eddie — R6.
 *
 * <p>Mini-Feature 4 : alias léger de la règle unifiée {@link SpendResourceCommand}
 * (R4), qui accepte en cible soit une carte vendue ({@link Zone#EDDIES_AREA}),
 * soit une Legend ({@link Zone#LEGENDS_AREA}). Cette classe ne conserve que
 * l'action filaire {@code SPEND_EDDIES} du protocole WebSocket et la
 * granularité du journal de diagnostic : toute la logique (validations,
 * mutation, journal) est héritée de {@link SpendResourceCommand}.</p>
 *
 * <p>Règle R4 : pendant la Main Phase du joueur actif, chaque carte face-down
 * de l'Eddies Area vaut 1 €$ par tour (Guide § EDDIES : « Each face-down card
 * in your Eddies area is 1 Eddie. Spend them (turn them sideways) to pay ») ;
 * la carte passe à {@code exhausted = true} et le joueur gagne
 * {@code +1} Eddie, redressée au début du tour suivant (START PHASE).</p>
 */
public class SpendEddiesCommand extends SpendResourceCommand {

    public SpendEddiesCommand(String playerId, UUID eddiesInstanceId) {
        super(playerId, eddiesInstanceId);
    }

    @Override
    public String actionType() {
        return "SPEND_EDDIES";
    }

    @Override
    public String describe() {
        return "incliner une carte Eddies pour 1 Eddie";
    }

    /** Accesseur de compatibilité (API précédente). */
    public UUID getEddiesInstanceId() {
        return getResourceInstanceId();
    }
}
