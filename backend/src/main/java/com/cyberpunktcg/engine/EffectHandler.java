package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameState;

/**
 * Stratégie d'application d'un type d'effet (pattern Strategy).
 * Chaque {@link EffectType} est associé à un handler dans {@link RuleEngine}.
 */
public interface EffectHandler {

    /**
     * Applique un effet.
     *
     * @param state  état de partie (muté)
     * @param source carte à l'origine de l'effet (contrôleur = effets joueur)
     * @param effect effet à appliquer
     * @param target cible désignée par la commande (peut être {@code null})
     */
    void apply(GameState state, CardInstance source, GameEffect effect, CardInstance target);
}
