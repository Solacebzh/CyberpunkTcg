package com.cyberpunktcg.api;

import com.cyberpunktcg.api.dto.CardResponse;
import com.cyberpunktcg.api.dto.CardStatsResponse;
import com.cyberpunktcg.service.CardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cards")
public class CardController {

    private final CardService cardService;

    public CardController(CardService cardService) {
        this.cardService = cardService;
    }

    @GetMapping
    public List<CardResponse> findCards(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String color
    ) {
        return cardService.findCards(type, color);
    }

    @GetMapping("/stats")
    public CardStatsResponse stats() {
        return cardService.stats();
    }

    @GetMapping("/{id}")
    public CardResponse findById(@PathVariable String id) {
        return cardService.findById(id);
    }
}
