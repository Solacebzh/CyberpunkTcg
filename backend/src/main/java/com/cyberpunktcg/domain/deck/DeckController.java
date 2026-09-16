package com.cyberpunktcg.domain.deck;

import com.cyberpunktcg.domain.deck.dto.DeckRequest;
import com.cyberpunktcg.domain.deck.dto.DeckResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API des decks sauvegardés (Mini-Feature 9C).
 *
 * <p>Toutes les routes sont protégées par JWT : {@code /api/decks/**} tombe sous
 * le {@code anyRequest().authenticated()} de
 * {@link com.cyberpunktcg.security.SecurityConfig}. Sans jeton valide, Spring
 * Security répond {@code 403} avant même d'entrer ici.</p>
 *
 * <ul>
 *   <li>{@code GET /api/decks} — mes decks ;</li>
 *   <li>{@code GET /api/decks/{id}} — un de mes decks ;</li>
 *   <li>{@code POST /api/decks} — création ({@code 201}) ;</li>
 *   <li>{@code PUT /api/decks/{id}} — mise à jour ;</li>
 *   <li>{@code DELETE /api/decks/{id}} — suppression ({@code 204}).</li>
 * </ul>
 *
 * <p>Un deck illégal est refusé en {@code 400 Bad Request} avec la liste des
 * infractions (voir {@link DeckValidationException} et
 * {@link com.cyberpunktcg.api.RestExceptionHandler}) ; le deck d'un autre
 * joueur répond {@code 404}.</p>
 */
@RestController
@RequestMapping("/api/decks")
@RequiredArgsConstructor
public class DeckController {

    private final DeckService deckService;

    @GetMapping
    public List<DeckResponse> myDecks(Authentication authentication) {
        return deckService.listDecks(currentUsername(authentication));
    }

    @GetMapping("/{id}")
    public DeckResponse myDeck(@PathVariable Long id, Authentication authentication) {
        return deckService.getDeck(currentUsername(authentication), id);
    }

    @PostMapping
    public ResponseEntity<DeckResponse> createDeck(
            @Valid @RequestBody DeckRequest request,
            Authentication authentication
    ) {
        DeckResponse created = deckService.createDeck(currentUsername(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public DeckResponse updateDeck(
            @PathVariable Long id,
            @Valid @RequestBody DeckRequest request,
            Authentication authentication
    ) {
        return deckService.updateDeck(currentUsername(authentication), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDeck(@PathVariable Long id, Authentication authentication) {
        deckService.deleteDeck(currentUsername(authentication), id);
        return ResponseEntity.noContent().build();
    }

    /** Pseudo porté par le JWT validé par {@code JwtAuthenticationFilter}. */
    private String currentUsername(Authentication authentication) {
        return authentication != null ? authentication.getName() : null;
    }
}
