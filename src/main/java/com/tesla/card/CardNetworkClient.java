package com.tesla.card;

public interface CardNetworkClient {

    CardLifecycleResponse execute(CardOperation operation, CardLifecycleRequest request);
}
