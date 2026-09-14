package com.cyberpunktcg.domain.game;

/**
 * Zones pouvant contenir des {@link CardInstance} pendant une partie.
 *
 * <p>Les dés (Fixer Area / Gig Area) ne sont pas des cartes : ils sont portés
 * directement par {@link Player} (listes {@code fixerDice} / {@code gigs}) et
 * n'apparaissent donc pas dans cette énumération.</p>
 */
public enum Zone {
    /** Pioche, index 0 = dessus. Contenu secret (seule la taille est publique). */
    DECK,
    /** Main. Visible uniquement par son propriétaire. */
    HAND,
    /** Champ de bataille (Units + Gears attachés). Information publique. */
    FIELD,
    /** Défausse (Trash). Information publique. */
    TRASH,
    /** Cartes vendues face cachée, 1 Eddie chacune. Visible par le propriétaire. */
    EDDIES_AREA,
    /** Les 3 Legends, face cachée au départ. Visible par le propriétaire. */
    LEGENDS_AREA,
    /** Hors jeu (exil, par exemple effet {@code go_solo} en V2). */
    REMOVED
}
