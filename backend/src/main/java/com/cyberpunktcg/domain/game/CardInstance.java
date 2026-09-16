package com.cyberpunktcg.domain.game;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.card.CardKeyword;
import com.cyberpunktcg.domain.card.CardType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Exemplaire d'une carte en jeu.
 *
 * <p>Chaque exemplaire possède un identifiant unique ({@link UUID}) : deux copies
 * d'une même carte restent distinguables. Les caractéristiques imprimées sont
 * recopiées depuis la définition {@link Card} à la création (le moteur reste
 * ainsi pur : aucune requête base pendant la partie). L'état mutable de
 * l'exemplaire (buffs, dégâts, épuisement, attachements) vit ici.</p>
 *
 * <p>Puissance effective : {@code max(0, base + bonus - dégâts)}. Les dégâts
 * réduisent la puissance ; si les dégâts atteignent ou dépassent
 * {@code base + bonus}, l'Unit est vaincue (voir {@code RuleEngine}).</p>
 */
public class CardInstance {

    private final UUID instanceId;
    private String cardId;
    private String name;
    private CardType type;
    private CardColor color;
    /** RAM imprimée (plafond de deckbuilding par couleur, voir {@code Player#ramCeilingFor}). */
    private int ram;
    private Integer baseCost;
    private Integer basePower;
    private Integer streetCredThreshold;
    private Set<CardKeyword> keywords;
    private List<String> abilities;
    private String ownerId;
    private Zone zone;

    /** Buffs / débuffs de puissance (effet {@code GRANT_POWER}). */
    private int powerBonus;
    /** Dégâts subis (effet {@code DAMAGE}, soignés par {@code HEAL}). */
    private int damage;

    /** Carte dépensée (a attaqué ce tour). Se redresse au début du tour du propriétaire. */
    private boolean exhausted;
    /** Carte face cachée (Legend non révélée, carte vendue). */
    private boolean faceDown;
    /** Mal d'invocation : ne peut pas attaquer le tour où elle est jouée (sauf {@code GO_SOLO}). */
    private boolean summoningSickness;

    /** Pour un Gear : l'hôte (Unit) auquel il est attaché, sinon {@code null}. */
    private UUID attachedTo;
    /** Pour un hôte : les Gears attachés. */
    private List<UUID> attachments;

    public CardInstance(UUID instanceId, String cardId, String name, CardType type, CardColor color,
                        int ram, Integer baseCost, Integer basePower, Integer streetCredThreshold,
                        Set<CardKeyword> keywords, List<String> abilities, String ownerId, Zone zone) {
        if (instanceId == null) {
            throw new IllegalArgumentException("L'identifiant d'exemplaire est obligatoire");
        }
        if (cardId == null) {
            throw new IllegalArgumentException("L'identifiant de carte est obligatoire");
        }
        if (type == null) {
            throw new IllegalArgumentException("Le type de carte est obligatoire");
        }
        if (ownerId == null) {
            throw new IllegalArgumentException("Le propriétaire est obligatoire");
        }
        if (zone == null) {
            throw new IllegalArgumentException("La zone est obligatoire");
        }
        this.instanceId = instanceId;
        this.cardId = cardId;
        this.name = name;
        this.type = type;
        this.color = color;
        this.ram = Math.max(0, ram);
        this.baseCost = baseCost;
        this.basePower = basePower;
        this.streetCredThreshold = streetCredThreshold;
        this.keywords = keywords == null ? EnumSet.noneOf(CardKeyword.class) : keywords;
        this.abilities = abilities == null ? new ArrayList<String>() : abilities;
        this.ownerId = ownerId;
        this.zone = zone;
        this.powerBonus = 0;
        this.damage = 0;
        this.exhausted = false;
        this.faceDown = false;
        this.summoningSickness = false;
        this.attachedTo = null;
        this.attachments = new ArrayList<UUID>();
    }

    /**
     * Construit un exemplaire neuf depuis une définition de carte.
     *
     * @param card    définition (catalogue JPA)
     * @param ownerId joueur propriétaire
     * @param zone    zone initiale
     * @return l'exemplaire, avec un {@link UUID} frais et aucun buff
     */
    public static CardInstance fromCard(Card card, String ownerId, Zone zone) {
        Set<CardKeyword> keywords;
        if (card.getKeywords().isEmpty()) {
            keywords = EnumSet.noneOf(CardKeyword.class);
        } else {
            keywords = EnumSet.copyOf(card.getKeywords());
        }
        return new CardInstance(
                UUID.randomUUID(),
                card.getId(),
                card.getName(),
                card.getType(),
                card.getColor(),
                card.getRam(),
                card.getCost(),
                card.getPower(),
                card.getStreetCred(),
                keywords,
                new ArrayList<String>(card.getAbilities()),
                ownerId,
                zone);
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public String getCardId() {
        return cardId;
    }

    public void setCardId(String cardId) {
        this.cardId = cardId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public CardType getType() {
        return type;
    }

    public void setType(CardType type) {
        this.type = type;
    }

    public CardColor getColor() {
        return color;
    }

    /** RAM imprimée de la carte (0 si aucune : Legends, par exemple). */
    public int getRam() {
        return ram;
    }

    public Integer getBaseCost() {
        return baseCost;
    }

    public Integer getBasePower() {
        return basePower;
    }

    public Integer getStreetCredThreshold() {
        return streetCredThreshold;
    }

    public Set<CardKeyword> getKeywords() {
        return keywords;
    }

    public List<String> getAbilities() {
        return abilities;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public Zone getZone() {
        return zone;
    }

    public void setZone(Zone zone) {
        this.zone = zone;
    }

    public int getPowerBonus() {
        return powerBonus;
    }

    public void setPowerBonus(int powerBonus) {
        this.powerBonus = powerBonus;
    }

    public int getDamage() {
        return damage;
    }

    public void setDamage(int damage) {
        this.damage = Math.max(0, damage);
    }

    public boolean isExhausted() {
        return exhausted;
    }

    public void setExhausted(boolean exhausted) {
        this.exhausted = exhausted;
    }

    public boolean isFaceDown() {
        return faceDown;
    }

    public void setFaceDown(boolean faceDown) {
        this.faceDown = faceDown;
    }

    public boolean isSummoningSickness() {
        return summoningSickness;
    }

    public void setSummoningSickness(boolean summoningSickness) {
        this.summoningSickness = summoningSickness;
    }

    public UUID getAttachedTo() {
        return attachedTo;
    }

    public void setAttachedTo(UUID attachedTo) {
        this.attachedTo = attachedTo;
    }

    public List<UUID> getAttachments() {
        return attachments;
    }

    public boolean isUnit() {
        return type == CardType.UNIT;
    }

    public boolean isLegend() {
        return type == CardType.LEGEND;
    }

    public boolean isProgram() {
        return type == CardType.PROGRAM;
    }

    public boolean isGear() {
        return type == CardType.GEAR;
    }

    public boolean hasKeyword(CardKeyword keyword) {
        return keywords.contains(keyword);
    }

    public boolean isQuick() {
        return hasKeyword(CardKeyword.QUICK);
    }

    public boolean isBlocker() {
        return hasKeyword(CardKeyword.BLOCKER);
    }

    public boolean hasGoSolo() {
        return hasKeyword(CardKeyword.GO_SOLO);
    }

    public boolean hasAdrenaline() {
        return hasKeyword(CardKeyword.ADRENALINE) || hasKeyword(CardKeyword.GO_SOLO);
    }

    /** {@code HASTE} (Mini-Feature 6) : alias d'{@code ADRENALINE}, Lag ignoré. */
    public boolean hasHaste() {
        return hasKeyword(CardKeyword.HASTE);
    }

    /** Peut attaquer le tour où elle est jouée (Lag ignoré : GO_SOLO, ADRENALINE, HASTE). */
    public boolean canIgnoreSummoningSickness() {
        return hasGoSolo() || hasAdrenaline() || hasHaste();
    }

    /**
     * Coût en Eddies de l'exemplaire (0 si aucun coût imprimé).
     * La remise du joueur ({@code REDUCE_COST}) est appliquée par l'appelant.
     */
    public int getEffectiveCost() {
        return baseCost == null ? 0 : Math.max(0, baseCost);
    }

    /**
     * Puissance effective ({@code base + bonus - dégâts}, plancher 0),
     * ou {@code null} si la carte n'a pas de puissance imprimée.
     */
    public Integer getEffectivePower() {
        if (basePower == null) {
            return null;
        }
        return Math.max(0, basePower + powerBonus - damage);
    }

    /** Variante pratique pour le combat (les cartes sans puissance valent 0). */
    public int getEffectivePowerOrZero() {
        Integer power = getEffectivePower();
        return power == null ? 0 : power;
    }

    /** {@code true} si les dégâts subis sont létaux pour cet exemplaire. */
    public boolean isLethalDamage() {
        return damage > 0 && basePower != null && damage >= basePower + powerBonus;
    }

    /** Remet à zéro les marqueurs de combat (utilisé à l'arrivée dans la défausse). */
    public void clearCombatMarkers() {
        this.powerBonus = 0;
        this.damage = 0;
        this.exhausted = false;
        this.summoningSickness = false;
    }

    /** Copie profonde et détachée. */
    public CardInstance copy() {
        CardInstance copy = new CardInstance(
                instanceId, cardId, name, type, color, ram, baseCost, basePower, streetCredThreshold,
                keywords.isEmpty() ? EnumSet.noneOf(CardKeyword.class) : EnumSet.copyOf(keywords),
                new ArrayList<String>(abilities), ownerId, zone);
        copy.powerBonus = this.powerBonus;
        copy.damage = this.damage;
        copy.exhausted = this.exhausted;
        copy.faceDown = this.faceDown;
        copy.summoningSickness = this.summoningSickness;
        copy.attachedTo = this.attachedTo;
        copy.attachments = new ArrayList<UUID>(this.attachments);
        return copy;
    }

    /**
     * Copie dont toute information secrète est effacée (vues masquées).
     * L'identifiant d'exemplaire, la zone et le propriétaire sont conservés
     * (ils ne révèlent rien sans la définition).
     */
    public CardInstance masked() {
        CardInstance copy = copy();
        copy.cardId = "hidden";
        copy.name = "Carte masquée";
        copy.ram = 0;
        copy.baseCost = null;
        copy.basePower = null;
        copy.streetCredThreshold = null;
        copy.keywords = EnumSet.noneOf(CardKeyword.class);
        copy.abilities = new ArrayList<String>();
        copy.powerBonus = 0;
        copy.damage = 0;
        copy.attachedTo = null;
        copy.attachments = new ArrayList<UUID>();
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardInstance)) {
            return false;
        }
        CardInstance that = (CardInstance) other;
        return instanceId.equals(that.instanceId);
    }

    @Override
    public int hashCode() {
        return instanceId.hashCode();
    }

    @Override
    public String toString() {
        return "CardInstance{instanceId=" + instanceId + ", cardId='" + cardId + "', zone=" + zone + '}';
    }
}
