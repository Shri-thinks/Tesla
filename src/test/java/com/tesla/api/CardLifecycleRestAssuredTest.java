package com.tesla.api;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CardLifecycleRestAssuredTest {

    @LocalServerPort
    private int port;

    @Test
    void issuesCardOverHttp() {
        RestAssured.given()
                .baseUri("http://127.0.0.1")
                .port(port)
                .header("X-Tesla-API-Key", "local-development-only")
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "network": "VISA",
                          "externalCardReference": "restassured-demo-card",
                          "customerReference": "restassured-demo-customer",
                          "idempotencyKey": "restassured-issue-demo-card"
                        }
                        """)
                .when()
                .post("/api/cards/issue")
                .then()
                .statusCode(200)
                .body("status", equalTo("ISSUED"));
    }
}
