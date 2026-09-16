package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.card.CardType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validateur officiel de deck (Mini-Feature 8).
 *
 * <p>Un deck n'est valide QUE s'il respecte strictement ces 4 conditions :</p>
 * <ol>
 *   <li><strong>Legends (exactement 3) :</strong> exactement 3 cartes de type
 *       {@link CardType#LEGEND}, uniques (1 seul exemplaire de chaque Legend dans le deck).</li>
 *   <li><strong>Main Deck (40 à 50 cartes) :</strong> entre 40 et 50 cartes au total
 *       (Units, Programs, Gears). Aucune Legend dans le Main Deck.</li>
 *   <li><strong>Limite de copies :</strong> maximum 3 exemplaires de la même carte
 *       dans le Main Deck.</li>
 *   <li><strong>Règle de RAM (calcul par couleur) :</strong> les 3 Legends choisies
 *       fournissent un plafond de RAM par couleur (somme des RAM de leurs Legends).
 *       Le Main Deck ne peut contenir QUE des cartes de couleurs dont le plafond est &gt; 0,
 *       et le coût en RAM d'une carte ne doit pas dépasser le plafond total de sa couleur.</li>
 * </ol>
 */
public class DeckValidator {

    public static final int REQUIRED_LEGENDS = 3;
    public static final int MAIN_DECK_MIN_SIZE = 40;
    public static final int MAIN_DECK_MAX_SIZE = 50;
    public static final int MAX_COPIES_PER_CARD = 3;

    public DeckValidator() {
        // Constructeur par défaut permettant l'instanciation directe ou Spring.
    }

    /**
     * Valide un deck complet (Legends + Main Deck mélangés ou ordonnés).
     *
     * @param deck liste complète des cartes du deck
     * @return résultat de validation contenant l'état valide/invalide, les erreurs et les plafonds
     */
    public static DeckValidationResult validate(List<Card> deck) {
        if (deck == null) {
            return DeckValidationResult.failure(
                    List.of("Le deck ne peut pas être null"),
                    emptyRamCeilings(),
                    0,
                    0
            );
        }

        List<Card> legends = new ArrayList<>();
        List<Card> mainDeck = new ArrayList<>();

        for (Card card : deck) {
            if (card == null) {
                continue;
            }
            if (card.getType() == CardType.LEGEND) {
                legends.add(card);
            } else {
                mainDeck.add(card);
            }
        }

        return validate(legends, mainDeck);
    }

    /**
     * Valide les Legends et le Main Deck fournis séparément.
     *
     * @param legends liste des cartes Legends
     * @param mainDeck liste des cartes du Main Deck
     * @return résultat de validation
     */
    public static DeckValidationResult validate(List<Card> legends, List<Card> mainDeck) {
        List<String> errors = new ArrayList<>();
        List<Card> safeLegends = legends != null ? legends : List.of();
        List<Card> safeMain = mainDeck != null ? mainDeck : List.of();

        // 1. Validation des Legends (exactement 3, uniques, type LEGEND uniquement)
        if (safeLegends.size() != REQUIRED_LEGENDS) {
            errors.add("Le deck doit contenir exactement " + REQUIRED_LEGENDS
                    + " Legends (actuellement : " + safeLegends.size() + ")");
        }

        Set<String> seenLegendIds = new HashSet<>();
        Set<String> seenLegendNames = new HashSet<>();
        for (Card legend : safeLegends) {
            if (legend == null) {
                errors.add("Une carte Legend est nulle");
                continue;
            }
            if (legend.getType() != CardType.LEGEND) {
                errors.add("La carte '" + legend.getName() + "' dans la zone Legends n'est pas de type Legend");
            }
            if (legend.getId() != null && !seenLegendIds.add(legend.getId())) {
                errors.add("Legend en double interdite (identifiant : " + legend.getId() + ")");
            }
            String normName = legend.getName() != null ? legend.getName().trim().toLowerCase() : "";
            if (!normName.isEmpty() && !seenLegendNames.add(normName)) {
                errors.add("Legend en double interdite (nom : " + legend.getName() + ")");
            }
        }

        // 2. Validation de la taille du Main Deck (40 à 50 cartes, aucun LEGEND)
        int mainDeckSize = safeMain.size();
        if (mainDeckSize < MAIN_DECK_MIN_SIZE || mainDeckSize > MAIN_DECK_MAX_SIZE) {
            errors.add("Le Main Deck doit contenir entre " + MAIN_DECK_MIN_SIZE + " et " + MAIN_DECK_MAX_SIZE
                    + " cartes (actuellement : " + mainDeckSize + ")");
        }

        for (Card card : safeMain) {
            if (card != null && card.getType() == CardType.LEGEND) {
                errors.add("Le Main Deck ne peut pas contenir de carte Legend ('" + card.getName() + "')");
            }
        }

        // 3. Validation de la limite de copies (maximum 3 exemplaires de la même carte)
        Map<String, Integer> copyCounts = new HashMap<>();
        Map<String, String> displayNames = new HashMap<>();
        for (Card card : safeMain) {
            if (card == null) {
                continue;
            }
            String key = card.getId() != null ? card.getId() : (card.getName() != null ? card.getName() : "unknown");
            copyCounts.put(key, copyCounts.getOrDefault(key, 0) + 1);
            if (card.getName() != null) {
                displayNames.put(key, card.getName());
            }
        }

        for (Map.Entry<String, Integer> entry : copyCounts.entrySet()) {
            if (entry.getValue() > MAX_COPIES_PER_CARD) {
                String name = displayNames.getOrDefault(entry.getKey(), entry.getKey());
                errors.add("Maximum " + MAX_COPIES_PER_CARD + " exemplaires de la même carte autorisés : '"
                        + name + "' apparaît " + entry.getValue() + " fois");
            }
        }

        // 4. Calcul du Plafond de RAM par couleur et vérification des cartes du Main Deck
        Map<CardColor, Integer> ramCeilings = calculateRamCeilings(safeLegends);

        for (Card card : safeMain) {
            if (card == null) {
                continue;
            }
            CardColor color = card.getColor();
            int ceiling = (color != null) ? ramCeilings.getOrDefault(color, 0) : 0;
            if (ceiling <= 0) {
                errors.add("La carte '" + card.getName() + "' est de couleur "
                        + (color != null ? color.label() : "inconnue")
                        + " dont le plafond de RAM est 0 (couleur interdite dans le deck)");
            } else if (card.getRam() > ceiling) {
                errors.add("Le coût en RAM de la carte '" + card.getName() + "' (" + card.getRam()
                        + " RAM) dépasse le plafond " + (color != null ? color.label() : "")
                        + " de " + ceiling + " RAM");
            }
        }

        int legendsCount = safeLegends.size();
        boolean valid = errors.isEmpty();
        return new DeckValidationResult(valid, errors, ramCeilings, legendsCount, mainDeckSize);
    }

    /**
     * Calcule le plafond de RAM par couleur à partir des Legends.
     */
    public static Map<CardColor, Integer> calculateRamCeilings(List<Card> legends) {
        Map<CardColor, Integer> ceilings = emptyRamCeilings();
        if (legends != null) {
            for (Card legend : legends) {
                if (legend != null && legend.getColor() != null && (legend.getType() == null || legend.getType() == CardType.LEGEND)) {
                    ceilings.put(legend.getColor(), ceilings.get(legend.getColor()) + legend.getRam());
                }
            }
        }
        return ceilings;
    }

    public static int getRamCeiling(List<Card> legends, CardColor color) {
        if (color == null) {
            return 0;
        }
        return calculateRamCeilings(legends).getOrDefault(color, 0);
    }

    public static boolean isValid(List<Card> deck) {
        return validate(deck).isValid();
    }

    public static boolean isValid(List<Card> legends, List<Card> mainDeck) {
        return validate(legends, mainDeck).isValid();
    }

    private static Map<CardColor, Integer> emptyRamCeilings() {
        Map<CardColor, Integer> map = new EnumMap<>(CardColor.class);
        for (CardColor color : CardColor.values()) {
            map.put(color, 0);
        }
        return map;
    }
}
