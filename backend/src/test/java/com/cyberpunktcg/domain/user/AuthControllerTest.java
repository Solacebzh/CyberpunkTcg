package com.cyberpunktcg.domain.user;

import com.cyberpunktcg.domain.deck.DeckRepository;
import com.cyberpunktcg.domain.user.dto.AuthResponse;
import com.cyberpunktcg.domain.user.dto.LoginRequest;
import com.cyberpunktcg.domain.user.dto.RegisterRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DeckRepository deckRepository;

    @BeforeEach
    void cleanUp() {
        // Isole les comptes et leurs decks (Mini-Feature 9C) d'une classe de tests à l'autre.
        deckRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("testRegisterSuccess : inscription réussie avec code 201 et token JWT retourné")
    void testRegisterSuccess() throws Exception {
        RegisterRequest request = new RegisterRequest("netrunner_v", "supersecret123");

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.username").value("netrunner_v"))
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();

        AuthResponse authResponse = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                AuthResponse.class
        );

        assertThat(authResponse.getToken()).isNotBlank();
        assertThat(userRepository.findByUsername("netrunner_v")).isPresent();
    }

    @Test
    @DisplayName("testRegisterDuplicateUsername : rejet avec code 409 Conflict si le nom d'utilisateur est déjà pris")
    void testRegisterDuplicateUsername() throws Exception {
        RegisterRequest first = new RegisterRequest("silverhand", "arassaka123");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isCreated());

        RegisterRequest duplicate = new RegisterRequest("silverhand", "differentpassword");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("testLoginSuccess : connexion réussie avec code 200 et token JWT valide")
    void testLoginSuccess() throws Exception {
        RegisterRequest register = new RegisterRequest("lucy_kushinada", "deepnet456");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("lucy_kushinada", "deepnet456");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.username").value("lucy_kushinada"))
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();

        AuthResponse authResponse = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                AuthResponse.class
        );
        assertThat(authResponse.getToken()).isNotBlank();
    }

    @Test
    @DisplayName("testLoginFailure : mauvais mot de passe ou utilisateur inconnu retourne 401 Unauthorized")
    void testLoginFailure() throws Exception {
        RegisterRequest register = new RegisterRequest("david_martinez", "sandevistan99");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest wrongPassword = new LoginRequest("david_martinez", "wrongpass");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongPassword)))
                .andExpect(status().isUnauthorized());

        LoginRequest unknownUser = new LoginRequest("nobody", "sandevistan99");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unknownUser)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("testProtectedEndpointAccess : accès refusé sans JWT et autorisé avec JWT")
    void testProtectedEndpointAccess() throws Exception {
        // Sans token sur une route protégée (par ex. /api/decks) -> 403 Forbidden
        mockMvc.perform(get("/api/decks"))
                .andExpect(status().isForbidden());

        // Avec token valide
        RegisterRequest register = new RegisterRequest("rebecca", "shotgun777");
        MvcResult regResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse auth = objectMapper.readValue(
                regResult.getResponse().getContentAsString(),
                AuthResponse.class
        );

        // L'authentification est passée (ni 401 ni 403) : /api/decks est servi
        // (Mini-Feature 9C) et un compte neuf n'a encore aucun deck.
        mockMvc.perform(get("/api/decks")
                        .header("Authorization", "Bearer " + auth.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
