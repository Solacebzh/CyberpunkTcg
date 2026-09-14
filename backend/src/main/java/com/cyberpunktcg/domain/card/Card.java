package com.cyberpunktcg.domain.card;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "cards",
        indexes = {
                @Index(name = "idx_cards_type", columnList = "card_type"),
                @Index(name = "idx_cards_color", columnList = "color"),
                @Index(name = "idx_cards_set_number", columnList = "set_code,collector_number")
        }
)
public class Card {

    @Id
    @Column(length = 180, nullable = false, updatable = false)
    private String id;

    @Column(length = 160, nullable = false)
    private String name;

    @Column(length = 200)
    private String subtitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_type", length = 16, nullable = false)
    private CardType type;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private CardColor color;

    @Column(nullable = false)
    private int ram;

    private Integer cost;
    private Integer power;

    @Column(name = "street_cred")
    private Integer streetCred;

    @ElementCollection
    @CollectionTable(name = "card_tags", joinColumns = @JoinColumn(name = "card_id"))
    @OrderColumn(name = "position")
    @Column(name = "tag", length = 80, nullable = false)
    private List<String> tags = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "card_keywords", joinColumns = @JoinColumn(name = "card_id"))
    @OrderColumn(name = "position")
    @Enumerated(EnumType.STRING)
    @Column(name = "keyword", length = 24, nullable = false)
    private List<CardKeyword> keywords = new ArrayList<>();

    @Column(name = "rules_text", length = 10_000, nullable = false)
    private String text;

    @ElementCollection
    @CollectionTable(name = "card_abilities", joinColumns = @JoinColumn(name = "card_id"))
    @OrderColumn(name = "position")
    @Column(name = "ability", length = 2_000, nullable = false)
    private List<String> abilities = new ArrayList<>();

    @Column(name = "image_url", length = 2_048)
    private String imageUrl;

    @Column(name = "set_code", length = 64, nullable = false)
    private String setCode;

    @Column(name = "collector_number", length = 32, nullable = false)
    private String collectorNumber;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private CardRarity rarity;

    protected Card() {
        // Constructeur JPA.
    }

    public Card(
            String id,
            String name,
            String subtitle,
            CardType type,
            CardColor color,
            int ram,
            Integer cost,
            Integer power,
            Integer streetCred,
            List<String> tags,
            List<CardKeyword> keywords,
            String text,
            List<String> abilities,
            String imageUrl,
            String setCode,
            String collectorNumber,
            CardRarity rarity
    ) {
        this.id = id;
        this.name = name;
        this.subtitle = subtitle;
        this.type = type;
        this.color = color;
        this.ram = ram;
        this.cost = cost;
        this.power = power;
        this.streetCred = streetCred;
        this.tags = new ArrayList<>(tags);
        this.keywords = new ArrayList<>(keywords);
        this.text = text;
        this.abilities = new ArrayList<>(abilities);
        this.imageUrl = imageUrl;
        this.setCode = setCode;
        this.collectorNumber = collectorNumber;
        this.rarity = rarity;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getSubtitle() { return subtitle; }
    public CardType getType() { return type; }
    public CardColor getColor() { return color; }
    public int getRam() { return ram; }
    public Integer getCost() { return cost; }
    public Integer getPower() { return power; }
    public Integer getStreetCred() { return streetCred; }
    public List<String> getTags() { return List.copyOf(tags); }
    public List<CardKeyword> getKeywords() { return List.copyOf(keywords); }
    public String getText() { return text; }
    public List<String> getAbilities() { return List.copyOf(abilities); }
    public String getImageUrl() { return imageUrl; }
    public String getSetCode() { return setCode; }
    public String getCollectorNumber() { return collectorNumber; }
    public CardRarity getRarity() { return rarity; }
}
