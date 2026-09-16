package com.cyberpunktcg.api;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardType;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.repository.CardRepository;
import com.cyberpunktcg.service.GameService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests des endpoints de debug (feature 6.5) : état complet <strong>non masqué</strong>,
 * détail d'un joueur, forçage de phase et bornes d'erreur.
 *
 * <p>Ces routes ne sont exposées que sous les profils {@code test}/{@code dev} :
 * la classe s'exécute donc sous le profil {@code test} (comme le reste de la
 * suite).</p>
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class DebugControllerTest {

    private static final String HOST = "Val";
    private static final String GUEST = "Johnny";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GameService gameService;

    @Autowired
    private CardRepository cardRepository;

    private String gameId;

    @BeforeEach
    void setUp() {
        List<Card> catalog = cardRepository.findAll();
        List<String> hostDeck = deckFor(catalog);
        List<String> guestDeck = deckFor(catalog);
        GameState state = gameService.createGame(HOST, GUEST, hostDeck, guestDeck);
        gameId = state.getGameId();
    }

    @Test
    @DisplayName("état complet : les deux mains sont visibles, le journal est joint")
    void game_shouldReturnFullUnmaskedState() throws Exception {
        mockMvc.perform(get("/api/debug/game/{gameId}", gameId).queryParam("logs", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(gameId))
                // Mini-Feature 5.1 : une partie neuve s'ouvre en phase DRAW (sous-étape
                // AWAITING_DRAW) — le premier joueur doit piocher puis lancer son dé Gig
                // avant d'entrer en phase MAIN.
                .andExpect(jsonPath("$.phase").value("DRAW"))
                .andExpect(jsonPath("$.drawStep").value("AWAITING_DRAW"))
                .andExpect(jsonPath("$.turnNumber").value(1))
                .andExpect(jsonPath("$.activePlayerId").value(org.hamcrest.Matchers.anyOf(is(HOST), is(GUEST))))
                .andExpect(jsonPath("$.gameOver").value(false))
                .andExpect(jsonPath("$.players", hasSize(2)))
                // Rien n'est masqué : les cartes de chaque main ont un vrai cardId.
                .andExpect(jsonPath("$.players[*].hand", everyItem(hasSize(6))))
                .andExpect(jsonPath("$.players[*].hand[*].cardId", everyItem(not(is("hidden")))))
                .andExpect(jsonPath("$.players[*].hand[*].name", everyItem(not(is("Carte masquée")))))
                .andExpect(jsonPath("$.players[*].ramCeilings").exists())
                // Mini-Feature 6 : aucun combat en suspens sur une partie neuve
                // (`pendingAttack` absent — le JSON omet les champs nuls).
                .andExpect(jsonPath("$.pendingAttack").doesNotExist())
                // Journal de diagnostic : mise en place + premier joueur désigné.
                .andExpect(jsonPath("$.gameLog[*].actionType", hasItem("GAME_START")))
                .andExpect(jsonPath("$.gameLog[*].actionType", hasItem("SETUP")))
                .andExpect(jsonPath("$.gameLog[*].description", hasItem(
                        org.hamcrest.Matchers.containsString("premier joueur"))));
    }

    @Test
    @DisplayName("détail joueur : pioche, Eddies, Legends et plafonds de RAM")
    void player_shouldExposePrivateDetails() throws Exception {
        mockMvc.perform(get("/api/debug/game/{gameId}/player/{playerId}", gameId, HOST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value(HOST))
                .andExpect(jsonPath("$.hand", hasSize(6)))
                .andExpect(jsonPath("$.deckCount", greaterThan(0)))
                .andExpect(jsonPath("$.legendsArea", hasSize(3)))
                // Le premier joueur (tiré au sort) subit le malus de mise en place :
                // 2 Legends déjà inclinées, il n'en reste qu'une pour gagner un Eddie.
                .andExpect(jsonPath("$.legendsSpent", oneOf(0, 2)))
                .andExpect(jsonPath("$.legendsReady", oneOf(1, 3)))
                // Plafonds de RAM dérivés des Legends : une entrée par couleur.
                .andExpect(jsonPath("$.ramCeilings", aMapWithSize(4)))
                .andExpect(jsonPath("$.ramCeilings[*]", everyItem(greaterThanOrEqualTo(0))))
                .andExpect(jsonPath("$.hand[*].cardId", everyItem(not(is("hidden")))));
    }

    @Test
    @DisplayName("parties en mémoire : la partie créée est listable")
    void games_shouldListRunningGames() throws Exception {
        mockMvc.perform(get("/api/debug/games"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count", greaterThan(0)))
                .andExpect(jsonPath("$.games[*].gameId", hasItem(gameId)));
    }

    @Test
    @DisplayName("force-phase : la phase change et la manipulation est journalisée")
    void forcePhase_shouldChangePhaseAndLogIt() throws Exception {
        mockMvc.perform(post("/api/debug/game/{gameId}/force-phase", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phase\":\"COMBAT\",\"playerId\":\"" + HOST + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("COMBAT"))
                .andExpect(jsonPath("$.gameLog[*].actionType", hasItem("DEBUG_FORCE_PHASE")))
                .andExpect(jsonPath("$.gameLog[*].description", hasItem(
                        org.hamcrest.Matchers.containsString("phase forcée"))));
    }

    @Test
    @DisplayName("erreurs : partie inconnue, joueur inconnu, phase invalide")
    void debugEndpoints_shouldRejectInvalidRequests() throws Exception {
        mockMvc.perform(get("/api/debug/game/{gameId}", "partie-inexistante"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/debug/game/{gameId}/player/{playerId}", gameId, "Personne"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/debug/game/{gameId}/force-phase", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phase\":\"NUIT\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/debug/game/{gameId}/force-phase", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /** Deck de test : 3 Legends du catalogue embarqué + 10 autres cartes. */
    private List<String> deckFor(List<Card> catalog) {
        List<String> ids = new ArrayList<String>();
        for (Card card : catalog) {
            if (card.getType() == CardType.LEGEND && ids.size() < 3) {
                ids.add(card.getId());
            }
        }
        for (Card card : catalog) {
            if (card.getType() != CardType.LEGEND && ids.size() < 13) {
                ids.add(card.getId());
            }
        }
        return ids;
    }
}
