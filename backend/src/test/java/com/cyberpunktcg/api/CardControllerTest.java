package com.cyberpunktcg.api;

import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.card.CardType;
import com.cyberpunktcg.repository.CardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class CardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CardRepository repository;

    @Test
    void cards_shouldReturnTheBundledCatalog() throws Exception {
        mockMvc.perform(get("/api/cards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].abilities").isArray())
                .andExpect(jsonPath("$[0].tags").isArray());
    }

    @Test
    void cards_shouldFilterByTypeAndColor() throws Exception {
        mockMvc.perform(get("/api/cards").queryParam("type", "legend").queryParam("color", "red"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[*].type", everyItem(is("legend"))))
                .andExpect(jsonPath("$[*].color", everyItem(is("red"))));
    }

    @Test
    void cards_shouldRejectAnUnknownFilter() throws Exception {
        mockMvc.perform(get("/api/cards").queryParam("color", "purple"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cardById_shouldReturnOneCardOr404() throws Exception {
        mockMvc.perform(get("/api/cards/cyberpsychosis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cyberpsychosis"))
                .andExpect(jsonPath("$.subtitle").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.keywords[0]").value("quick"));

        mockMvc.perform(get("/api/cards/not-a-card"))
                .andExpect(status().isNotFound());
    }

    @Test
    void stats_shouldCountCardsByTypeAndColor() throws Exception {
        mockMvc.perform(get("/api/cards/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value((int) repository.count()))
                .andExpect(jsonPath("$.byType.legend").value((int) repository.countByType(CardType.LEGEND)))
                .andExpect(jsonPath("$.byType.unit").value((int) repository.countByType(CardType.UNIT)))
                .andExpect(jsonPath("$.byType.program").value((int) repository.countByType(CardType.PROGRAM)))
                .andExpect(jsonPath("$.byType.gear").value((int) repository.countByType(CardType.GEAR)))
                .andExpect(jsonPath("$.byColor.red").value((int) repository.countByColor(CardColor.RED)))
                .andExpect(jsonPath("$.byColor.green").value((int) repository.countByColor(CardColor.GREEN)))
                .andExpect(jsonPath("$.byColor.blue").value((int) repository.countByColor(CardColor.BLUE)))
                .andExpect(jsonPath("$.byColor.yellow").value((int) repository.countByColor(CardColor.YELLOW)));
    }
}
