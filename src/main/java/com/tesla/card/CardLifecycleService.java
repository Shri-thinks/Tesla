package com.tesla.card;

import org.springframework.stereotype.Service;

@Service
public class CardLifecycleService {

    private final CardNetworkClient networkClient;

    public CardLifecycleService(CardNetworkClient networkClient) {
        this.networkClient = networkClient;
    }

    public CardLifecycleResponse execute(String operation, CardLifecycleRequest request) {
        return networkClient.execute(CardOperation.fromPath(operation), request);
    }
}
