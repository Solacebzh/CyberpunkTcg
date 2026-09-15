package com.cyberpunktcg.domain.game;

import com.cyberpunktcg.domain.card.CardColor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * État d'un joueur pendant une partie : zones de cartes, dés et ressources.
 *
 * <p>Conventions :</p>
 * <ul>
 *   <li>le deck est une liste dont l'index 0 est le dessus ; piocher retire
 *   l'index 0, remettre dans le deck ajoute en dessous (fin de liste) ;</li>
 *   <li>les dés Gig restants à lancer ({@code fixerDice}) sont dans l'ordre
 *   {@code d4, d6, d8, d10, d12, d20} : le {@code d20} est donc lancé en
 *   dernier, conformément aux règles ;</li>
 *   <li>les Eddies (notés « €$ ») forment une réserve persistante ; ils
 *   s'obtiennent en inclinant une Legend (épuisée, +1 Eddie) ou en vendant une
 *   carte de sa main (+1 Eddie, 1 vente par tour), et se dépensent pour payer
 *   les coûts imprimés ;</li>
 *   <li>la Legends Area sert aussi de réserve d'Eddies : une Legend inclinée
 *   ({@link #spendLegendForEddies}) est dépensée jusqu'au début du tour suivant
 *   ({@link #startTurn()} la redresse) ;</li>
 *   <li>la RAM n'est pas une ressource consommée : chaque couleur a un
 *   <em>plafond</em> dérivé des Legends ({@link #ramCeilingFor(CardColor)}),
 *   qui borne la RAM imprimée des cartes jouables ;</li>
 *   <li>le Street Cred est dérivé (somme des dés de la Gig Area), jamais stocké.</li>
 * </ul>
 */
public class Player {

    private final String id;
    private String name;

    private List<CardInstance> deck;
    private List<CardInstance> hand;
    private List<CardInstance> field;
    private List<CardInstance> trash;
    private List<CardInstance> eddiesArea;
    private List<CardInstance> legendsArea;

    /** Valeurs des dés dans la Gig Area (chaque entrée = 1 Gig contrôlé). */
    private List<Integer> gigs;
    /** Dés restants dans la Fixer Area, dans l'ordre de lancer. */
    private List<String> fixerDice;

    private int eddies;
    private int costDiscount;
    private boolean hasSoldThisTurn;
    private boolean hasCalledLegendThisTurn;

    public Player(String id, String name) {
        if (id == null) {
            throw new IllegalArgumentException("L'identifiant du joueur est obligatoire");
        }
        this.id = id;
        this.name = name == null ? id : name;
        this.deck = new ArrayList<CardInstance>();
        this.hand = new ArrayList<CardInstance>();
        this.field = new ArrayList<CardInstance>();
        this.trash = new ArrayList<CardInstance>();
        this.eddiesArea = new ArrayList<CardInstance>();
        this.legendsArea = new ArrayList<CardInstance>();
        this.gigs = new ArrayList<Integer>();
        this.fixerDice = freshFixerDice();
        this.eddies = 0;
        this.costDiscount = 0;
        this.hasSoldThisTurn = false;
        this.hasCalledLegendThisTurn = false;
    }

    /** Les 6 dés Gig de départ, dans l'ordre de lancer imposé (d20 en dernier). */
    public static List<String> freshFixerDice() {
        return new ArrayList<String>(Arrays.asList("d4", "d6", "d8", "d10", "d12", "d20"));
    }

    /** Nombre de faces d'un dé Gig. */
    public static int sidesOf(String die) {
        if (die == null) {
            throw new IllegalArgumentException("Le dé est obligatoire");
        }
        switch (die) {
            case "d4":
                return 4;
            case "d6":
                return 6;
            case "d8":
                return 8;
            case "d10":
                return 10;
            case "d12":
                return 12;
            case "d20":
                return 20;
            default:
                throw new IllegalArgumentException("Dé Gig inconnu : " + die);
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** Dessus du deck à l'index 0. Liste modifiable (réservée au moteur). */
    public List<CardInstance> getDeck() {
        return deck;
    }

    public List<CardInstance> getHand() {
        return hand;
    }

    public List<CardInstance> getField() {
        return field;
    }

    public List<CardInstance> getTrash() {
        return trash;
    }

    public List<CardInstance> getEddiesArea() {
        return eddiesArea;
    }

    public List<CardInstance> getLegendsArea() {
        return legendsArea;
    }

    public List<Integer> getGigs() {
        return gigs;
    }

    public List<String> getFixerDice() {
        return fixerDice;
    }

    public int getEddies() {
        return eddies;
    }

    public void setEddies(int eddies) {
        this.eddies = Math.max(0, eddies);
    }

    /** Eddies disponibles (la réserve est dépensée directement en V1). */
    public int getAvailableEddies() {
        return eddies;
    }

    public void addEddy() {
        this.eddies += 1;
    }

    public void spendEddies(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Montant négatif : " + amount);
        }
        if (amount > eddies) {
            throw new IllegalStateException("Eddies insuffisants : " + eddies + " pour " + amount);
        }
        this.eddies -= amount;
    }

    /**
     * Incline une Legend de la Legends Area pour gagner 1 Eddie.
     * <p>Règle officielle : Whether face-up or face-down, you can also spend a Legend to pay 1 €$ (Guide § LEGENDS AREA).
     * Matérialisé par +1 au compteur Eddies et exhaustion ; redressée au début du tour suivant.</p>
     */
    public CardInstance spendLegendForEddies(java.util.UUID legendInstanceId) {
        CardInstance legend = findIn(Zone.LEGENDS_AREA, legendInstanceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Legend introuvable dans la Legends Area : " + legendInstanceId));
        if (legend.isExhausted()) {
            throw new IllegalStateException("Cette Legend est déjà inclinée (Eddies déjà perçus)");
        }
        legend.setExhausted(true);
        this.eddies += 1;
        return legend;
    }

    /**
     * Incline une carte de l'Eddies Area pour gagner 1 Eddie (R6).
     * Même mécanique que les Legends : spend sideways = +1, redress au début du tour.
     */
    public CardInstance spendEddiesCardForEddies(java.util.UUID eddiesInstanceId) {
        CardInstance eddiesCard = findIn(Zone.EDDIES_AREA, eddiesInstanceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Carte Eddies introuvable : " + eddiesInstanceId));
        if (eddiesCard.isExhausted()) {
            throw new IllegalStateException("Cette carte Eddies est déjà inclinée");
        }
        eddiesCard.setExhausted(true);
        this.eddies += 1;
        return eddiesCard;
    }

    /** Cartes Eddies encore disponibles pour gagner un Eddie ce tour. */
    public List<CardInstance> eddiesAvailableForEddies() {
        List<CardInstance> available = new ArrayList<CardInstance>();
        for (CardInstance c : eddiesArea) {
            if (!c.isExhausted()) {
                available.add(c);
            }
        }
        return available;
    }

    /** Legends de la Legends Area encore disponibles pour gagner un Eddie. */
    public List<CardInstance> legendsAvailableForEddies() {
        List<CardInstance> available = new ArrayList<CardInstance>();
        for (CardInstance legend : legendsArea) {
            if (!legend.isExhausted()) {
                available.add(legend);
            }
        }
        return available;
    }

    /** Nombre de Legends de la Legends Area actuellement inclinées. */
    public int countSpentLegends() {
        int spent = 0;
        for (CardInstance legend : legendsArea) {
            if (legend.isExhausted()) {
                spent++;
            }
        }
        return spent;
    }

    /**
     * Plafond de RAM d'une couleur, dérivé des Legends (règles officielles §2 :
     * les RAM des Legends s'additionnent par couleur). Une carte est jouable si
     * sa RAM imprimée ne dépasse pas ce plafond.
     *
     * @return la somme des RAM des Legends de cette couleur ({@code 0} si la
     *         couleur n'est couverte par aucune Legend)
     */
    public int ramCeilingFor(CardColor color) {
        if (color == null) {
            return 0;
        }
        int total = 0;
        for (CardInstance legend : legendsArea) {
            if (legend.getColor() == color) {
                total += legend.getRam();
            }
        }
        return total;
    }

    /**
     * {@code true} si la Legends Area permet de calculer un plafond de RAM
     * (parties créées par {@code GameService} : 3 Legends). Les duels de test
     * sans Legends n'appliquent donc aucun plafond (V1 permissive).
     */
    public boolean hasLegendCeiling() {
        return !legendsArea.isEmpty();
    }

    /** Remise de coût active jusqu'au début du prochain tour du joueur (effet {@code REDUCE_COST}). */
    public int getCostDiscount() {
        return costDiscount;
    }

    public void setCostDiscount(int costDiscount) {
        this.costDiscount = Math.max(0, costDiscount);
    }

    public boolean hasSoldThisTurn() {
        return hasSoldThisTurn;
    }

    public void setHasSoldThisTurn(boolean hasSoldThisTurn) {
        this.hasSoldThisTurn = hasSoldThisTurn;
    }

    public boolean hasCalledLegendThisTurn() {
        return hasCalledLegendThisTurn;
    }

    public void setHasCalledLegendThisTurn(boolean hasCalledLegendThisTurn) {
        this.hasCalledLegendThisTurn = hasCalledLegendThisTurn;
    }

    /** Nombre de Gigs contrôlés (condition de victoire : 7 au début du tour). */
    public int getGigCount() {
        return gigs.size();
    }

    /** Street Cred = somme des valeurs visibles des dés de la Gig Area. */
    public int getStreetCred() {
        int total = 0;
        for (Integer gig : gigs) {
            total += gig;
        }
        return total;
    }

    /**
     * Liste modifiable des cartes d'une zone.
     *
     * @throws IllegalArgumentException si la zone est inconnue
     */
    public List<CardInstance> cardsIn(Zone zone) {
        switch (zone) {
            case DECK:
                return deck;
            case HAND:
                return hand;
            case FIELD:
                return field;
            case TRASH:
                return trash;
            case EDDIES_AREA:
                return eddiesArea;
            case LEGENDS_AREA:
                return legendsArea;
            case REMOVED:
            default:
                throw new IllegalArgumentException("Zone sans liste associée : " + zone);
        }
    }

    /** Cherche un exemplaire dans une zone précise. */
    public Optional<CardInstance> findIn(Zone zone, UUID instanceId) {
        for (CardInstance card : cardsIn(zone)) {
            if (card.getInstanceId().equals(instanceId)) {
                return Optional.of(card);
            }
        }
        return Optional.empty();
    }

    /** Cherche un exemplaire dans toutes les zones du joueur. */
    public Optional<CardInstance> findAnywhere(UUID instanceId) {
        for (Zone zone : Zone.values()) {
            if (zone == Zone.REMOVED) {
                continue;
            }
            Optional<CardInstance> found = findIn(zone, instanceId);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * Déplace un exemplaire vers une zone (ajout en dessous du deck, sinon en fin de liste).
     *
     * @throws IllegalStateException si l'exemplaire n'est pas dans sa zone déclarée
     */
    public void moveToZone(CardInstance card, Zone target) {
        if (card.getZone() == target) {
            return;
        }
        List<CardInstance> source = cardsIn(card.getZone());
        boolean removed = false;
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i).getInstanceId().equals(card.getInstanceId())) {
                source.remove(i);
                removed = true;
                break;
            }
        }
        if (!removed) {
            throw new IllegalStateException("Carte " + card.getInstanceId()
                    + " absente de sa zone déclarée " + card.getZone());
        }
        card.setZone(target);
        cardsIn(target).add(card);
    }

    /** Retire et retourne le prochain dé à lancer, ou vide s'il n'y en a plus. */
    public Optional<String> popFixerDie() {
        if (fixerDice.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fixerDice.remove(0));
    }

    /** Units BLOCKER prêtes (non épuisées) sur le Field. */
    public List<CardInstance> readyBlockers() {
        List<CardInstance> blockers = new ArrayList<CardInstance>();
        for (CardInstance card : field) {
            if (card.isUnit() && card.isBlocker() && !card.isExhausted()) {
                blockers.add(card);
            }
        }
        return blockers;
    }

    public boolean controlsReadyBlocker() {
        return !readyBlockers().isEmpty();
    }

    /**
     * Redresse toutes les cartes dépensées au début du tour (R2/R3/R6).
     * <p>Règle officielle : START PHASE — READY SPENT CARDS Return all your spent (sideways) cards to the ready position.
     * Cela inclut Field, Legends Area et Eddies Area. Les Legends ne sont plus définitivement inclinées.</p>
     */
    public void readyAll() {
        for (CardInstance card : field) {
            card.setExhausted(false);
        }
        for (CardInstance card : legendsArea) {
            card.setExhausted(false);
        }
        for (CardInstance card : eddiesArea) {
            card.setExhausted(false);
        }
    }

    /** Dissipe les mals d'invocation (début de tour). */
    public void clearSummoningSickness() {
        for (CardInstance card : field) {
            card.setSummoningSickness(false);
        }
    }

    /**
     * Réinitialise les marqueurs de début de tour (R2/R8) :
     * - Eddies remis à 0 (cycle mana : début 0, fin perdus)
     * - vente et Call réinitialisés
     * - remise de coût remise à 0
     * - redressement de toutes les cartes dépensées (Field + Legends + Eddies)
     * - Lag/mal d'invocation dissipé
     */
    public void startTurn() {
        this.eddies = 0;
        this.hasSoldThisTurn = false;
        this.hasCalledLegendThisTurn = false;
        this.costDiscount = 0;
        readyAll();
        clearSummoningSickness();
    }

    /**
     * Copie profonde et détachée.
     *
     * @param maskSecrets {@code true} pour masquer les informations secrètes
     *                    (main, cartes vendues, Legends face cachée) ; le deck
     *                    est toujours masqué, même pour son propriétaire
     *                    (seule sa taille est publique)
     */
    public Player copy(boolean maskSecrets) {
        Player copy = new Player(this.id, this.name);
        copy.deck = maskedCopies(this.deck);
        copy.hand = maskSecrets ? maskedCopies(this.hand) : deepCopies(this.hand);
        copy.field = deepCopies(this.field);
        copy.trash = deepCopies(this.trash);
        copy.eddiesArea = maskSecrets ? maskedCopies(this.eddiesArea) : deepCopies(this.eddiesArea);
        copy.legendsArea = new ArrayList<CardInstance>();
        for (CardInstance card : this.legendsArea) {
            if (maskSecrets && card.isFaceDown()) {
                copy.legendsArea.add(card.masked());
            } else {
                copy.legendsArea.add(card.copy());
            }
        }
        copy.gigs = new ArrayList<Integer>(this.gigs);
        copy.fixerDice = new ArrayList<String>(this.fixerDice);
        copy.eddies = this.eddies;
        copy.costDiscount = this.costDiscount;
        copy.hasSoldThisTurn = this.hasSoldThisTurn;
        copy.hasCalledLegendThisTurn = this.hasCalledLegendThisTurn;
        return copy;
    }

    private static List<CardInstance> deepCopies(List<CardInstance> cards) {
        List<CardInstance> copies = new ArrayList<CardInstance>(cards.size());
        for (CardInstance card : cards) {
            copies.add(card.copy());
        }
        return copies;
    }

    private static List<CardInstance> maskedCopies(List<CardInstance> cards) {
        List<CardInstance> copies = new ArrayList<CardInstance>(cards.size());
        for (CardInstance card : cards) {
            copies.add(card.masked());
        }
        return copies;
    }

    @Override
    public String toString() {
        return "Player{id='" + id + "', hand=" + hand.size() + ", field=" + field.size()
                + ", legends=" + legendsArea.size() + ", gigs=" + gigs.size()
                + ", eddies=" + eddies + '}';
    }
}
