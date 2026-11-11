package com.tesla.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CardLifecycleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void performsCardLifecycleForDifferentNetworks() throws Exception {
        for (String network : new String[]{"VISA", "MASTERCARD", "AMEX"}) {
            String request = request(network, "card-" + network);
            mockMvc.perform(post("/api/cards/issue")
                            .header("X-Tesla-API-Key", "local-development-only")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.network").value(network))
                    .andExpect(jsonPath("$.status").value("ISSUED"));
        }
    }

    @Test
    void enforcesLifecycleTransitions() throws Exception {
        String request = request("VISA", "lifecycle-card");

        mockMvc.perform(post("/api/cards/issue")
                        .header("X-Tesla-API-Key", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));
        mockMvc.perform(post("/api/cards/activate")
                        .header("X-Tesla-API-Key", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/api/cards/suspend")
                        .header("X-Tesla-API-Key", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mockMvc.perform(post("/api/cards/unblock")
                        .header("X-Tesla-API-Key", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/api/cards/replace")
                        .header("X-Tesla-API-Key", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLACED"));
        mockMvc.perform(post("/api/cards/suspend")
                        .header("X-Tesla-API-Key", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void closesAnIssuedCard() throws Exception {
        String request = request("AMEX", "close-card");
        mockMvc.perform(post("/api/cards/issue")
                        .header("X-Tesla-API-Key", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/cards/close")
                        .header("X-Tesla-API-Key", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void rejectsUnknownOperationsAndInvalidRequests() throws Exception {
        mockMvc.perform(post("/api/cards/unknown")
                        .header("X-Tesla-API-Key", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("VISA", "invalid-card")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/cards/issue")
                        .header("X-Tesla-API-Key", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"network\":\"VISA\",\"externalCardReference\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresAnApiKey() throws Exception {
        mockMvc.perform(post("/api/cards/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("VISA", "unauthenticated-card")))
                .andExpect(status().isUnauthorized());
    }

    private String request(String network, String cardReference) throws Exception {
        return objectMapper.writeValueAsString(
                new CardRequest(network, cardReference, "customer-1", "key-" + cardReference));
    }

    private record CardRequest(
            String network,
            String externalCardReference,
            String customerReference,
            String idempotencyKey) {
    }
}
