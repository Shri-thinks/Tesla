package com.tesla.api;

import com.tesla.card.CardLifecycleRequest;
import com.tesla.card.CardLifecycleResponse;
import com.tesla.card.CardLifecycleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cards")
public class CardLifecycleController {

    private final CardLifecycleService cardLifecycleService;

    public CardLifecycleController(CardLifecycleService cardLifecycleService) {
        this.cardLifecycleService = cardLifecycleService;
    }

    @PostMapping("/{operation}")
    @ResponseStatus(HttpStatus.OK)
    public CardLifecycleResponse execute(
            @PathVariable String operation,
            @Valid @RequestBody CardLifecycleRequest request) {
        return cardLifecycleService.execute(operation, request);
    }
}
