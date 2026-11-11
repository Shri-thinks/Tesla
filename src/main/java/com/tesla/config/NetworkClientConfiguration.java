package com.tesla.config;

import com.tesla.card.CardNetworkClient;
import com.tesla.card.MockCardNetworkClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NetworkClientConfiguration {

    @Bean
    @ConditionalOnProperty(name = "tesla.network.mode", havingValue = "mock", matchIfMissing = true)
    CardNetworkClient mockCardNetworkClient() {
        return new MockCardNetworkClient();
    }
}
