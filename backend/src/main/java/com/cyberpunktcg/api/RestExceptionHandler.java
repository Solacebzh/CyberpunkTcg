package com.cyberpunktcg.api;

import com.cyberpunktcg.api.dto.ApiErrorResponse;
import com.cyberpunktcg.domain.deck.DeckValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.List;

/**
 * Traduction des exceptions métier en réponses REST lisibles.
 *
 * <p>Le frontend affiche {@code errors} tel quel (en rouge) : les messages sont
 * donc rédigés en français, une ligne par infraction, et le code HTTP reste
 * celui attendu par les tests d'intégration.</p>
 *
 * <p>Ce conseil ne traite que les erreurs de validation ; les
 * {@link org.springframework.web.server.ResponseStatusException} (401, 404, 409…)
 * conservent le traitement par défaut de Spring Boot.</p>
 */
@RestControllerAdvice
public class RestExceptionHandler {

    /** Deck refusé par les règles officielles : {@code 400} + liste des infractions. */
    @ExceptionHandler(DeckValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleDeckValidation(DeckValidationException ex) {
        return error(HttpStatus.BAD_REQUEST, "DECK_INVALID", joined(ex.getMessage(), ex.getErrors()), ex.getErrors());
    }

    /** Payload malformé (Bean Validation sur {@code @Valid}) : {@code 400} + champs en cause. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex) {
        List<String> errors = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.add(fieldError.getField() + " : " + fieldError.getDefaultMessage());
        }
        for (ObjectError globalError : ex.getBindingResult().getGlobalErrors()) {
            errors.add(globalError.getObjectName() + " : " + globalError.getDefaultMessage());
        }
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", joined("Requête invalide", errors), errors);
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            List<String> errors
    ) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(status.value(), code, message, errors));
    }

    /** Message synthétique : « Deck invalide : infraction 1 ; infraction 2 ». */
    private String joined(String prefix, List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return prefix;
        }
        return prefix + " : " + String.join(" ; ", errors);
    }
}
