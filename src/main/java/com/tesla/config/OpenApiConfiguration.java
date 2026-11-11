package com.tesla.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI teslaOpenApi() {
        String apiKeyScheme = "TeslaApiKey";
        return new OpenAPI()
                .info(new Info()
                        .title("Tesla Card Lifecycle Simulator API")
                        .version("1.0.0")
                        .description("Local mock API for testing account, card, and transaction lifecycles."))
                .components(new Components().addSecuritySchemes(apiKeyScheme,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Tesla-API-Key")))
                .addSecurityItem(new SecurityRequirement().addList(apiKeyScheme));
    }
}
