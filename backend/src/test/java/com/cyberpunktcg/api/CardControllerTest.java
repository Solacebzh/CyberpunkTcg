package com.cyberpunktcg.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class CardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void cards_shouldReturnTheBundledCatalog() throws Exception {
        mockMvc.perform(get("/api/cards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].abilities").isArray())
                .andExpect(jsonPath("$[0].tags").isArray());
    }

    @Test
    void cards_shouldFilterByTypeAndColor() throws Exception {
        mockMvc.perform(get("/api/cards").queryParam("type", "legend").queryParam("color", "red"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("yorinobu-arasaka-embracing-destruction"))
                .andExpect(jsonPath("$[0].type").value("legend"))
                .andExpect(jsonPath("$[0].color").value("red"));
    }

    @Test
    void cards_shouldRejectAnUnknownFilter() throws Exception {
        mockMvc.perform(get("/api/cards").queryParam("color", "purple"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cardById_shouldReturnOneCardOr404() throws Exception {
        mockMvc.perform(get("/api/cards/breach-protocol"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Breach Protocol"))
                .andExpect(jsonPath("$.subtitle").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.keywords[0]").value("quick"));

        mockMvc.perform(get("/api/cards/not-a-card"))
                .andExpect(status().isNotFound());
    }

    @Test
    void stats_shouldCountCardsByTypeAndColor() throws Exception {
        mockMvc.perform(get("/api/cards/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.byType.legend").value(2))
                .andExpect(jsonPath("$.byType.unit").value(1))
                .andExpect(jsonPath("$.byColor.red").value(2))
                .andExpect(jsonPath("$.byColor.blue").value(1));
    }
}
