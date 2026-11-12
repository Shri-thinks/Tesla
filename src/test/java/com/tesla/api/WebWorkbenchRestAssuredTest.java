package com.tesla.api;

import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebWorkbenchRestAssuredTest {

    @LocalServerPort
    private int port;

    @Test
    void servesWorkbenchAndInteractiveApiDocumentationWithoutAnApiKey() {
        RestAssured.given()
                .baseUri("http://127.0.0.1")
                .port(port)
                .get("/")
                .then()
                .statusCode(200)
                .body(containsString("Card lifecycle,"))
                .body(containsString("id=\"api-connection\""));

        RestAssured.given()
                .baseUri("http://127.0.0.1")
                .port(port)
                .get("/app.js")
                .then()
                .statusCode(200)
                .body(containsString("/api/transactions/initiate"))
                .body(containsString("byId(\"api-connection\")"));

        RestAssured.given()
                .baseUri("http://127.0.0.1")
                .port(port)
                .get("/swagger-ui/index.html")
                .then()
                .statusCode(200);

        RestAssured.given()
                .baseUri("http://127.0.0.1")
                .port(port)
                .get("/v3/api-docs")
                .then()
                .statusCode(200)
                .body("components.securitySchemes.TeslaApiKey.name", equalTo("X-Tesla-API-Key"))
                .body("paths.'/api/transactions/initiate'.post", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    void continuesToRequireAnApiKeyForApiRequests() {
        RestAssured.given()
                .baseUri("http://127.0.0.1")
                .port(port)
                .get("/api/accounts/unknown-account")
                .then()
                .statusCode(401);

        RestAssured.given()
                .baseUri("http://127.0.0.1")
                .port(port)
                .header("X-Tesla-API-Key", "local-development-only")
                .get("/api/accounts/unknown-account")
                .then()
                .statusCode(404);
    }
}
