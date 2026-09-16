package com.cyberpunktcg.domain.game;

/**
 * Un dé Gig <strong>actif</strong> (déjà lancé) de la Gig Area — Mini-Feature 6.
 *
 * <p>Chaque dé possède un identifiant stable (chaîne UUID) : c'est cet
 * identifiant que l'attaquant désigne quand il choisit les dés à voler
 * ({@code StealGigCommand}) et que le frontend affiche/sélectionne. Un dé
 * transféré conserve son identifiant, son type ({@code "d8"}…) et sa valeur
 * exacte (« un D8 affichant 5 reste un D8 affichant 5 »).</p>
 *
 * <p>Les dés encore dans la Fixer Area ({@link Player#getFixerDice()}) ne sont
 * <strong>pas</strong> des {@code GigDie} : ils ne sont pas lancés et ne peuvent
 * donc jamais être volés.</p>
 *
 * @param id    identifiant stable du dé (chaîne UUID)
 * @param die   type de dé ({@code d4}, {@code d6}, {@code d8}, {@code d10},
 *              {@code d12}, {@code d20}, ou {@link Player#UNKNOWN_DIE} pour un
 *              Gig injecté hors lancer)
 * @param value valeur affichée (Street Cred)
 */
public record GigDie(String id, String die, int value) {

    public GigDie {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("L'identifiant du dé Gig est obligatoire");
        }
    }

    @Override
    public String toString() {
        return die + " → " + value;
    }
}
