package com.tesla.card;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "tesla.network.mode", havingValue = "http")
@ConfigurationProperties(prefix = "tesla.networks")
public class HttpCardNetworkClient implements CardNetworkClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<Network, NetworkSettings> settings = new EnumMap<>(Network.class);

    public HttpCardNetworkClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public void setVisa(NetworkSettings value) {
        settings.put(Network.VISA, value);
    }

    public void setMastercard(NetworkSettings value) {
        settings.put(Network.MASTERCARD, value);
    }

    public void setAmex(NetworkSettings value) {
        settings.put(Network.AMEX, value);
    }

    @Override
    public CardLifecycleResponse execute(CardOperation operation, CardLifecycleRequest request) {
        NetworkSettings networkSettings = settings.get(request.network());
        if (networkSettings == null || networkSettings.baseUrl() == null || networkSettings.baseUrl().isBlank()) {
            throw new NetworkApiException("Base URL is not configured for " + request.network() + ".");
        }
        if (networkSettings.apiToken() == null || networkSettings.apiToken().isBlank()) {
            throw new NetworkApiException("API token is not configured for " + request.network() + ".");
        }

        URI endpoint;
        try {
            URI baseUri = URI.create(networkSettings.baseUrl());
            if (!"https".equalsIgnoreCase(baseUri.getScheme())
                    || baseUri.getHost() == null
                    || baseUri.getUserInfo() != null
                    || baseUri.getQuery() != null
                    || baseUri.getFragment() != null) {
                throw new NetworkApiException(
                        "A valid HTTPS base URL is required for " + request.network() + ".");
            }
            endpoint = URI.create(stripTrailingSlash(networkSettings.baseUrl())
                    + "/cards/" + operation.name().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new NetworkApiException("The base URL is invalid for " + request.network() + ".");
        }
        try {
            String body = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + networkSettings.apiToken())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Idempotency-Key", request.idempotencyKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new NetworkApiException(
                        request.network() + " API returned HTTP " + response.statusCode() + ".");
            }
            CardLifecycleResponse result = objectMapper.readValue(response.body(), CardLifecycleResponse.class);
            if (result == null
                    || result.network() != request.network()
                    || result.operation() != operation
                    || result.externalCardReference() == null
                    || result.externalCardReference().isBlank()
                    || result.status() == null
                    || result.status().isBlank()) {
                throw new NetworkApiException(
                        request.network() + " API returned an invalid lifecycle response.");
            }
            return result;
        } catch (IOException exception) {
            throw new NetworkApiException("Could not complete the " + request.network() + " API request.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new NetworkApiException("The " + request.network() + " API request was interrupted.");
        }
    }

    private String stripTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    public static class NetworkSettings {
        private String baseUrl;
        private String apiToken;

        public String baseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String apiToken() {
            return apiToken;
        }

        public void setApiToken(String apiToken) {
            this.apiToken = apiToken;
        }
    }
}
