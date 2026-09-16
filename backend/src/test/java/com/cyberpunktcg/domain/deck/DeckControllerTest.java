package com.cyberpunktcg.domain.deck;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.card.CardType;
import com.cyberpunktcg.domain.deck.dto.DeckRequest;
import com.cyberpunktcg.domain.user.AuthController;
import com.cyberpunktcg.domain.user.UserRepository;
import com.cyberpunktcg.domain.user.dto.AuthResponse;
import com.cyberpunktcg.domain.user.dto.RegisterRequest;
import com.cyberpunktcg.engine.DeckValidator;
import com.cyberpunktcg.repository.CardRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests d'intégration de la persistance des decks (Mini-Feature 9C).
 *
 * <p>Couvre les trois exigences de la feature :</p>
 * <ol>
 *   <li>{@link #testSaveValidDeck()} — un deck légal est sauvegardé sur le compte
 *       du joueur ({@code 201}) et relu à l'identique ;</li>
 *   <li>{@link #testSaveInvalidDeck_Rejected()} — les règles officielles
 *       (3 Legends, Main Deck 40-50, max 3 copies) s'appliquent <em>avant</em>
 *       la sauvegarde : {@code 400 Bad Request}, messages explicites, rien en base ;</li>
 *   <li>{@link #testUserCanOnlySeeHisDecks()} — chaque joueur ne voit que ses
 *       decks et ne peut ni lire ni modifier ceux d'un autre.</li>
 * </ol>
 *
 * <p>Les decks de test sont construits depuis le vrai catalogue embarqué
 * ({@code data/cards.json}), comme le fait le deck builder frontend.</p>
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class DeckControllerTest {

    private static final String DECKS_URL = "/api/decks";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DeckRepository deckRepository;

    @Autowired
    private CardRepository cardRepository;

    /** Catalogue embarqué, chargé au démarrage par {@code CardDataInitializer}. */
    private List<Card> catalog;

    @BeforeEach
    void setUp() {
        deckRepository.deleteAll();
        userRepository.deleteAll();
        catalog = cardRepository.findAll();
        assertThat(catalog).as("Le catalogue embarqué doit être chargé").isNotEmpty();
    }

    // -----------------------------------------------------------------
    // 1. Sauvegarde d'un deck valide
    // -----------------------------------------------------------------

    @Test
    @DisplayName("testSaveValidDeck : un deck respectant les règles officielles est sauvegardé (201)")
    void testSaveValidDeck() throws Exception {
        AuthResponse player = register("netrunner_v", "supersecret123");
        List<String> legalDeck = legalDeckCardIds();

        MvcResult result = mockMvc.perform(post(DECKS_URL)
                        .header("Authorization", "Bearer " + player.getToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DeckRequest("Netrunner Prime", legalDeck))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Netrunner Prime"))
                .andExpect(jsonPath("$.userId").value(player.getId().intValue()))
                .andExpect(jsonPath("$.cardIds").isArray())
                .andExpect(jsonPath("$.totalCards").value(legalDeck.size()))
                .andReturn();

        // La liste renvoyée est exactement celle soumise : ordre et exemplaires compris.
        assertThat(cardIdsOf(result)).containsExactlyElementsOf(legalDeck);

        // Le deck est bien persisté, rattaché au compte du joueur.
        assertThat(deckRepository.count()).isEqualTo(1);
        Deck persisted = deckRepository.findAll().get(0);
        assertThat(persisted.getName()).isEqualTo("Netrunner Prime");
        assertThat(persisted.getUserId()).isEqualTo(player.getId());
        assertThat(persisted.getCardIds()).containsExactlyElementsOf(legalDeck);
        assertThat(persisted.getCreatedAt()).isNotNull();

        // Et il réapparaît dans « Mes Decks ».
        mockMvc.perform(get(DECKS_URL).header("Authorization", "Bearer " + player.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Netrunner Prime"))
                .andExpect(jsonPath("$[0].totalCards").value(legalDeck.size()));
    }

    // -----------------------------------------------------------------
    // 2. Un deck invalide est refusé avant toute sauvegarde
    // -----------------------------------------------------------------

    @Test
    @DisplayName("testSaveInvalidDeck_Rejected : règles officielles appliquées avant sauvegarde (400)")
    void testSaveInvalidDeck_Rejected() throws Exception {
        AuthResponse player = register("solo_rogue", "arassaka123");
        String authorization = "Bearer " + player.getToken();
        List<String> legalDeck = legalDeckCardIds();

        // Cas 1 : trop peu de cartes (3 Legends seules, Main Deck vide).
        List<String> tooSmall = new ArrayList<>(legalDeck.subList(0, DeckValidator.REQUIRED_LEGENDS));
        MvcResult tooSmallResult = mockMvc.perform(post(DECKS_URL)
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DeckRequest("Deck trop court", tooSmall))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("DECK_INVALID"))
                .andExpect(jsonPath("$.errors").isArray())
                .andReturn();
        assertThat(errorsOf(tooSmallResult))
                .anyMatch(error -> error.contains("entre " + DeckValidator.MAIN_DECK_MIN_SIZE
                        + " et " + DeckValidator.MAIN_DECK_MAX_SIZE));

        // Cas 2 : seulement 2 Legends (règle des 3 Legends uniques).
        List<String> twoLegends = new ArrayList<>(legalDeck.subList(0, DeckValidator.REQUIRED_LEGENDS - 1));
        twoLegends.addAll(legalDeck.subList(DeckValidator.REQUIRED_LEGENDS, legalDeck.size()));
        MvcResult twoLegendsResult = mockMvc.perform(post(DECKS_URL)
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DeckRequest("Deux Legends", twoLegends))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(errorsOf(twoLegendsResult))
                .anyMatch(error -> error.contains("exactement " + DeckValidator.REQUIRED_LEGENDS + " Legends"));

        // Cas 3 : plus de 3 exemplaires d'une même carte du Main Deck.
        String overCopiedCard = legalDeck.get(DeckValidator.REQUIRED_LEGENDS);
        List<String> overCopies = new ArrayList<>(legalDeck);
        overCopies.add(overCopiedCard);
        overCopies.add(overCopiedCard);
        overCopies.add(overCopiedCard);
        MvcResult overCopiesResult = mockMvc.perform(post(DECKS_URL)
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DeckRequest("Trop de copies", overCopies))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(errorsOf(overCopiesResult))
                .anyMatch(error -> error.contains("Maximum " + DeckValidator.MAX_COPIES_PER_CARD + " exemplaires")
                        && error.contains(nameOf(overCopiedCard)));

        // Cas 4 : une carte inconnue du catalogue.
        List<String> unknownCard = new ArrayList<>(legalDeck);
        unknownCard.add("carte-qui-n-existe-pas");
        MvcResult unknownResult = mockMvc.perform(post(DECKS_URL)
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DeckRequest("Carte fantome", unknownCard))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(errorsOf(unknownResult))
                .anyMatch(error -> error.contains("inconnue") && error.contains("carte-qui-n-existe-pas"));

        // Aucune de ces tentatives n'a rien écrit en base.
        assertThat(deckRepository.count()).isZero();

        // La mise à jour est soumise aux mêmes règles.
        Deck savedDirectly = deckRepository.save(new Deck("Deck existant", player.getId(), legalDeck));
        mockMvc.perform(put(DECKS_URL + "/" + savedDirectly.getId())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DeckRequest("Deck casse", tooSmall))))
                .andExpect(status().isBadRequest());

        Deck unchanged = deckRepository.findById(savedDirectly.getId()).orElseThrow();
        assertThat(unchanged.getName()).isEqualTo("Deck existant");
        assertThat(unchanged.getCardIds()).containsExactlyElementsOf(legalDeck);
    }

    // -----------------------------------------------------------------
    // 3. Isolation entre comptes
    // -----------------------------------------------------------------

    @Test
    @DisplayName("testUserCanOnlySeeHisDecks : chaque joueur ne voit que ses propres decks")
    void testUserCanOnlySeeHisDecks() throws Exception {
        AuthResponse alice = register("alice_v", "alice-secret-1");
        AuthResponse bob = register("bob_solo", "bob-secret-2");
        List<String> legalDeck = legalDeckCardIds();

        long aliceDeckId = createDeck(alice, "Deck Alice", legalDeck);
        long bobDeckId = createDeck(bob, "Deck de Bob", legalDeck);
        assertThat(aliceDeckId).isNotEqualTo(bobDeckId);

        // Alice ne voit que son deck, et pas celui de Bob.
        MvcResult aliceList = mockMvc.perform(get(DECKS_URL)
                        .header("Authorization", "Bearer " + alice.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value((int) aliceDeckId))
                .andExpect(jsonPath("$[0].name").value("Deck Alice"))
                .andExpect(jsonPath("$[0].userId").value(alice.getId().intValue()))
                .andReturn();
        assertThat(namesOf(aliceList)).containsExactly("Deck Alice");

        // Bob ne voit que le sien.
        MvcResult bobList = mockMvc.perform(get(DECKS_URL)
                        .header("Authorization", "Bearer " + bob.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Deck de Bob"))
                .andExpect(jsonPath("$[0].userId").value(bob.getId().intValue()))
                .andReturn();
        assertThat(namesOf(bobList)).containsExactly("Deck de Bob");

        // Bob ne peut ni lire, ni modifier, ni supprimer le deck d'Alice : 404.
        mockMvc.perform(get(DECKS_URL + "/" + aliceDeckId)
                        .header("Authorization", "Bearer " + bob.getToken()))
                .andExpect(status().isNotFound());

        mockMvc.perform(put(DECKS_URL + "/" + aliceDeckId)
                        .header("Authorization", "Bearer " + bob.getToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DeckRequest("Pris par Bob", legalDeck))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete(DECKS_URL + "/" + aliceDeckId)
                        .header("Authorization", "Bearer " + bob.getToken()))
                .andExpect(status().isNotFound());

        // Le deck d'Alice est intact, et Bob n'a toujours qu'un seul deck.
        assertThat(deckRepository.count()).isEqualTo(2);
        assertThat(deckRepository.findById(aliceDeckId)).isPresent();
        assertThat(deckRepository.findById(aliceDeckId).orElseThrow().getName()).isEqualTo("Deck Alice");
        assertThat(namesOf(mockMvc.perform(get(DECKS_URL)
                        .header("Authorization", "Bearer " + bob.getToken()))
                .andExpect(status().isOk())
                .andReturn())).containsExactly("Deck de Bob");

        // Alice, elle, peut lire et supprimer son propre deck.
        mockMvc.perform(get(DECKS_URL + "/" + aliceDeckId)
                        .header("Authorization", "Bearer " + alice.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Deck Alice"));

        mockMvc.perform(delete(DECKS_URL + "/" + aliceDeckId)
                        .header("Authorization", "Bearer " + alice.getToken()))
                .andExpect(status().isNoContent());
        assertThat(deckRepository.findById(aliceDeckId)).isEmpty();
        assertThat(deckRepository.findById(bobDeckId)).isPresent();
    }

    // -----------------------------------------------------------------
    // Compléments : JWT obligatoire, ordre/exemplaires, cycle de vie
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Les routes /api/decks exigent un JWT : 403 sans jeton")
    void testDeckEndpointsRequireJwt() throws Exception {
        mockMvc.perform(get(DECKS_URL)).andExpect(status().isForbidden());
        mockMvc.perform(post(DECKS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DeckRequest("Anonyme", legalDeckCardIds()))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(DECKS_URL).header("Authorization", "Bearer jeton.invalide.signature"))
                .andExpect(status().isForbidden());
        assertThat(deckRepository.count()).isZero();
    }

    @Test
    @DisplayName("Le nom du deck est obligatoire (Bean Validation) : 400 avec le champ en cause")
    void testSaveDeckWithoutName_Rejected() throws Exception {
        AuthResponse player = register("nameless_v", "secret-123456");

        mockMvc.perform(post(DECKS_URL)
                        .header("Authorization", "Bearer " + player.getToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DeckRequest("   ", legalDeckCardIds()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("name")));

        assertThat(deckRepository.count()).isZero();
    }

    @Test
    @DisplayName("L'ordre des cartes et les exemplaires multiples survivent à l'aller-retour en base")
    void testCardOrderAndDuplicatesArePreserved() throws Exception {
        AuthResponse player = register("order_matters", "secret-123456");
        List<String> legalDeck = legalDeckCardIds();
        List<String> shuffled = new ArrayList<>(legalDeck.subList(DeckValidator.REQUIRED_LEGENDS, legalDeck.size()));
        Collections.reverse(shuffled);
        List<String> deck = new ArrayList<>(legalDeck.subList(0, DeckValidator.REQUIRED_LEGENDS));
        deck.addAll(shuffled);
        assertThat(deck).hasSameSizeAs(legalDeck).isNotEqualTo(legalDeck);

        long deckId = createDeck(player, "Ordre inverse", deck);

        MvcResult result = mockMvc.perform(get(DECKS_URL + "/" + deckId)
                        .header("Authorization", "Bearer " + player.getToken()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(cardIdsOf(result)).containsExactlyElementsOf(deck);
        assertThat(deckRepository.findById(deckId).orElseThrow().getCardIds()).containsExactlyElementsOf(deck);
    }

    @Test
    @DisplayName("Un deck sauvegardé peut être renommé, modifié puis supprimé")
    void testUpdateAndDeleteOwnDeck() throws Exception {
        AuthResponse player = register("editor_v", "secret-123456");
        String authorization = "Bearer " + player.getToken();
        List<String> legalDeck = legalDeckCardIds();
        long deckId = createDeck(player, "Version 1", legalDeck);

        // Un Main Deck de 45 cartes : toujours légal, mais différent de la version 1.
        List<String> extended = legalDeckCardIds(45);
        assertThat(extended).isNotEqualTo(legalDeck);
        MvcResult updated = mockMvc.perform(put(DECKS_URL + "/" + deckId)
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DeckRequest("Version 2", extended))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Version 2"))
                .andExpect(jsonPath("$.totalCards").value(extended.size()))
                .andReturn();
        assertThat(cardIdsOf(updated)).containsExactlyElementsOf(extended);
        assertThat(deckRepository.count()).isEqualTo(1);

        mockMvc.perform(get(DECKS_URL).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Version 2"));

        mockMvc.perform(delete(DECKS_URL + "/" + deckId).header("Authorization", authorization))
                .andExpect(status().isNoContent());
        assertThat(deckRepository.count()).isZero();

        mockMvc.perform(get(DECKS_URL).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(delete(DECKS_URL + "/" + deckId).header("Authorization", authorization))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------
    // Utilitaires
    // -----------------------------------------------------------------

    /** Inscrit un joueur via {@link AuthController} et renvoie son jeton + son identifiant. */
    private AuthResponse register(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest(username, password))))
                .andExpect(status().isCreated())
                .andReturn();
        AuthResponse response = objectMapper.readValue(body(result), AuthResponse.class);
        assertThat(response.getToken()).isNotBlank();
        assertThat(response.getId()).isNotNull();
        return response;
    }

    /** Sauvegarde un deck via l'API et renvoie son identifiant. */
    private long createDeck(AuthResponse player, String name, List<String> cardIds) throws Exception {
        MvcResult result = mockMvc.perform(post(DECKS_URL)
                        .header("Authorization", "Bearer " + player.getToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DeckRequest(name, cardIds))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(body(result)).get("id").asLong();
    }

    /**
     * Deck légal construit depuis le catalogue embarqué : 3 Legends uniques d'une
     * même couleur (celle dont le plafond de RAM cumulé est le plus haut) puis
     * {@link DeckValidator#MAIN_DECK_MIN_SIZE} cartes de cette couleur, dans la
     * limite de {@link DeckValidator#MAX_COPIES_PER_CARD} exemplaires chacune.
     */
    private List<String> legalDeckCardIds() {
        return legalDeckCardIds(DeckValidator.MAIN_DECK_MIN_SIZE);
    }

    /**
     * Même construction, avec une taille de Main Deck choisie (entre
     * {@link DeckValidator#MAIN_DECK_MIN_SIZE} et {@link DeckValidator#MAIN_DECK_MAX_SIZE}).
     */
    private List<String> legalDeckCardIds(int mainDeckSize) {
        List<Card> legends = new ArrayList<>();
        for (Card card : catalog) {
            if (card.getType() == CardType.LEGEND) {
                legends.add(card);
            }
        }

        CardColor bestColor = null;
        List<Card> bestLegends = new ArrayList<>();
        int bestCeiling = -1;

        for (CardColor color : CardColor.values()) {
            // Une seule Legend par nom imprimé : les doublons de nom sont interdits.
            Map<String, Card> uniqueByName = new LinkedHashMap<>();
            for (Card legend : legends) {
                if (legend.getColor() != color || legend.getRam() <= 0) {
                    continue;
                }
                String key = legend.getName().trim().toLowerCase();
                Card already = uniqueByName.get(key);
                if (already == null || legend.getRam() > already.getRam()) {
                    uniqueByName.put(key, legend);
                }
            }
            if (uniqueByName.size() < DeckValidator.REQUIRED_LEGENDS) {
                continue;
            }
            List<Card> candidates = new ArrayList<>(uniqueByName.values());
            candidates.sort((left, right) -> Integer.compare(right.getRam(), left.getRam()));
            List<Card> chosen = new ArrayList<>(candidates.subList(0, DeckValidator.REQUIRED_LEGENDS));

            int ceiling = 0;
            for (Card legend : chosen) {
                ceiling += legend.getRam();
            }
            if (ceiling > bestCeiling) {
                bestCeiling = ceiling;
                bestColor = color;
                bestLegends = chosen;
            }
        }

        assertThat(bestColor)
                .as("Le catalogue doit fournir %d Legends uniques d'une même couleur", DeckValidator.REQUIRED_LEGENDS)
                .isNotNull();

        List<Card> pool = new ArrayList<>();
        for (Card card : catalog) {
            if (card.getType() == CardType.LEGEND || card.getColor() != bestColor || card.getRam() > bestCeiling) {
                continue;
            }
            pool.add(card);
        }
        pool.sort((left, right) -> left.getId().compareTo(right.getId()));
        assertThat(pool)
                .as("Le catalogue doit fournir assez de cartes %s pour un Main Deck de %d",
                        bestColor.label(), mainDeckSize)
                .isNotEmpty();

        int target = DeckValidator.REQUIRED_LEGENDS + mainDeckSize;
        List<String> deck = new ArrayList<>(target);
        for (Card legend : bestLegends) {
            deck.add(legend.getId());
        }
        for (int copies = 0; copies < DeckValidator.MAX_COPIES_PER_CARD && deck.size() < target; copies++) {
            for (Card card : pool) {
                if (deck.size() >= target) {
                    break;
                }
                deck.add(card.getId());
            }
        }

        assertThat(deck).hasSize(target);
        return deck;
    }

    /** Nom imprimé d'une carte du catalogue (évite un proxy JPA hors session). */
    private String nameOf(String cardId) {
        for (Card card : catalog) {
            if (card.getId().equals(cardId)) {
                return card.getName();
            }
        }
        return cardId;
    }

    private String json(Object payload) throws Exception {
        return objectMapper.writeValueAsString(payload);
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private List<String> cardIdsOf(MvcResult result) throws Exception {
        return stringList(objectMapper.readTree(body(result)).get("cardIds"));
    }

    /** Noms des decks d'une réponse liste (`findValuesAsText` renvoie déjà une {@code List}). */
    private List<String> namesOf(MvcResult result) throws Exception {
        return objectMapper.readTree(body(result)).findValuesAsText("name");
    }

    private List<String> errorsOf(MvcResult result) throws Exception {
        return stringList(objectMapper.readTree(body(result)).get("errors"));
    }

    private List<String> stringList(JsonNode array) {
        if (array == null || !array.isArray()) {
            return fail("Le corps de réponse doit porter une liste JSON, reçu : " + array);
        }
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
