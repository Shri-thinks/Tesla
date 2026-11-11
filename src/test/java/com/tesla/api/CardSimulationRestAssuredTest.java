package com.tesla.api;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CardSimulationRestAssuredTest {

    @LocalServerPort
    private int port;

    @Test
    void simulatesOnboardingCardIssuanceAuthorizationAndLedgerLifecycle() {
        String accountReference = createAccount("1000.00");
        String cardReference = issueCard(accountReference, "500.00");
        request().post("/api/cards/{cardReference}/activate", cardReference)
                .then().statusCode(200).body("status", equalTo("ACTIVE"));

        String transactionReference = request()
                .body("""
                        {
                          "cardReference": "%s",
                          "merchantReference": "test-merchant-001",
                          "amount": 25.00,
                          "currency": "USD",
                          "idempotencyKey": "%s"
                        }
                        """.formatted(cardReference, UUID.randomUUID()))
                .post("/api/transactions/initiate")
                .then().statusCode(201).body("status", equalTo("INITIATED"))
                .extract().path("transactionReference");

        request().post("/api/transactions/{transactionReference}/authorize", transactionReference)
                .then().statusCode(200)
                .body("status", equalTo("AUTHORIZED"))
                .body("decision", equalTo("APPROVED"));
        request()
                .body("{\"amount\":23.00}")
                .post("/api/transactions/{transactionReference}/clear", transactionReference)
                .then().statusCode(200).body("status", equalTo("CLEARED"));
        request().post("/api/transactions/{transactionReference}/settle", transactionReference)
                .then().statusCode(200).body("status", equalTo("SETTLED"));
        request().post("/api/transactions/{transactionReference}/post", transactionReference)
                .then().statusCode(200).body("status", equalTo("POSTED"));

        financialEvent(transactionReference, "refund", "3.00", "test refund", "refund");
        financialEvent(transactionReference, "chargeback", "2.00", "test chargeback", "chargeback");
        financialEvent(transactionReference, "fees", "1.00", "test fee", "fee");
        financialEvent(transactionReference, "adjustments", "0.50", "test adjustment", "adjustment");
        request().body("""
                        {"reason":"test dispute","idempotencyKey":"%s"}
                        """.formatted(UUID.randomUUID()))
                .post("/api/transactions/{transactionReference}/disputes", transactionReference)
                .then().statusCode(200).body("type", equalTo("DISPUTE"));

        request().get("/api/accounts/{accountReference}", accountReference)
                .then().statusCode(200)
                .body("postedBalance", equalTo(19.5f))
                .body("availableCredit", equalTo(980.5f));
        request().get("/api/accounts/{accountReference}/reconciliation", accountReference)
                .then().statusCode(200)
                .body("reconciled", equalTo(true))
                .body("difference", equalTo(0f));
        request().get("/api/accounts/{accountReference}/transactions", accountReference)
                .then().statusCode(200).body("size()", equalTo(1));
    }

    @Test
    void supportsCardControlsReplacementAndAuthorizationDeclines() {
        String accountReference = createAccount("500.00");
        String cardReference = issueCard(accountReference, "100.00");
        request().post("/api/cards/{cardReference}/activate", cardReference).then().statusCode(200);
        request().body("{\"dailyLimit\":50.00}")
                .put("/api/cards/{cardReference}/limits", cardReference)
                .then().statusCode(200).body("dailyLimit", equalTo(50f));
        request().body("{\"securePinChangeReference\":\"vault-change-request-001\"}")
                .post("/api/cards/{cardReference}/pin-change", cardReference)
                .then().statusCode(200).body("status", equalTo("ACCEPTED"));
        request().post("/api/cards/{cardReference}/lock", cardReference)
                .then().statusCode(200).body("temporarilyLocked", equalTo(true));

        String transactionReference = request()
                .body("""
                        {
                          "cardReference": "%s",
                          "merchantReference": "test-merchant-002",
                          "amount": 10.00,
                          "currency": "USD",
                          "idempotencyKey": "%s"
                        }
                        """.formatted(cardReference, UUID.randomUUID()))
                .post("/api/transactions/initiate")
                .then().statusCode(201).extract().path("transactionReference");
        request().post("/api/transactions/{transactionReference}/authorize", transactionReference)
                .then().statusCode(200)
                .body("decision", equalTo("DECLINED"))
                .body("message", equalTo("Card is temporarily locked."));

        request().post("/api/cards/{cardReference}/unlock", cardReference).then().statusCode(200);
        request().post("/api/cards/{cardReference}/suspend", cardReference)
                .then().statusCode(200).body("status", equalTo("SUSPENDED"));
        request().post("/api/cards/{cardReference}/unblock", cardReference)
                .then().statusCode(200).body("status", equalTo("ACTIVE"));

        String replacementReference = request()
                .body("""
                        {"network":"MASTERCARD","idempotencyKey":"%s","dailyLimit":75.00}
                        """.formatted(UUID.randomUUID()))
                .post("/api/cards/{cardReference}/replace", cardReference)
                .then().statusCode(200)
                .body("status", equalTo("ISSUED"))
                .body("tokenReference", startsWith("mock-token-"))
                .extract().path("cardReference");
        request().get("/api/cards/{cardReference}", cardReference)
                .then().statusCode(200).body("status", equalTo("REPLACED"));
        request().post("/api/cards/{cardReference}/activate", replacementReference).then().statusCode(200);
        request().post("/api/cards/{cardReference}/close", replacementReference)
                .then().statusCode(200).body("status", equalTo("CLOSED"));
    }

    @Test
    void enforcesIdempotencyAndMockCreditApproval() {
        String key = UUID.randomUUID().toString();
        String applicationBody = """
                {
                  "customerReference": "test-customer",
                  "requestedCreditLimit": 100.00,
                  "idempotencyKey": "%s"
                }
                """.formatted(key);
        String firstReference = request().body(applicationBody).post("/api/applications")
                .then().statusCode(201).extract().path("applicationReference");
        request().body(applicationBody).post("/api/applications")
                .then().statusCode(201).body("applicationReference", equalTo(firstReference));
        request().body("""
                        {
                          "customerReference": "test-customer",
                          "requestedCreditLimit": 200.00,
                          "idempotencyKey": "%s"
                        }
                        """.formatted(key))
                .post("/api/applications")
                .then().statusCode(409);

        request().body("""
                        {
                          "customerReference": "test-customer-declined",
                          "requestedCreditLimit": 10001.00,
                          "idempotencyKey": "%s"
                        }
                        """.formatted(UUID.randomUUID()))
                .post("/api/applications")
                .then().statusCode(201).body("status", equalTo("DECLINED"));
    }

    @Test
    void reservesAndReleasesAuthorizationHolds() {
        String accountReference = createAccount("100.00");
        String cardReference = issueCard(accountReference, "100.00");
        request().post("/api/cards/{cardReference}/activate", cardReference).then().statusCode(200);
        String transactionReference = request()
                .body("""
                        {
                          "cardReference": "%s",
                          "merchantReference": "hold-merchant",
                          "amount": 80.00,
                          "currency": "USD",
                          "idempotencyKey": "%s"
                        }
                        """.formatted(cardReference, UUID.randomUUID()))
                .post("/api/transactions/initiate")
                .then().statusCode(201).extract().path("transactionReference");

        request().post("/api/transactions/{transactionReference}/authorize", transactionReference)
                .then().statusCode(200).body("status", equalTo("AUTHORIZED"));
        request().get("/api/accounts/{accountReference}", accountReference)
                .then().statusCode(200).body("availableCredit", equalTo(20f));
        request().post("/api/accounts/{accountReference}/close", accountReference).then().statusCode(409);

        request().post("/api/transactions/{transactionReference}/reverse", transactionReference)
                .then().statusCode(200).body("status", equalTo("REVERSED"));
        request().get("/api/accounts/{accountReference}", accountReference)
                .then().statusCode(200).body("availableCredit", equalTo(100f));
        request().post("/api/accounts/{accountReference}/close", accountReference)
                .then().statusCode(200).body("status", equalTo("CLOSED"));
    }

    private String createAccount(String creditLimit) {
        return request().body("""
                        {
                          "customerReference": "test-customer-%s",
                          "requestedCreditLimit": %s,
                          "idempotencyKey": "%s"
                        }
                        """.formatted(UUID.randomUUID(), creditLimit, UUID.randomUUID()))
                .post("/api/applications")
                .then().statusCode(201).body("status", equalTo("APPROVED"))
                .extract().path("accountReference");
    }

    private String issueCard(String accountReference, String dailyLimit) {
        return request().body("""
                        {
                          "network": "VISA",
                          "idempotencyKey": "%s",
                          "dailyLimit": %s
                        }
                        """.formatted(UUID.randomUUID(), dailyLimit))
                .post("/api/accounts/{accountReference}/cards", accountReference)
                .then().statusCode(201)
                .body("status", equalTo("ISSUED"))
                .body("tokenReference", startsWith("mock-token-"))
                .extract().path("cardReference");
    }

    private void financialEvent(
            String transactionReference, String path, String amount, String reason, String key) {
        request().body("""
                        {
                          "amount": %s,
                          "reason": "%s",
                          "idempotencyKey": "%s"
                        }
                        """.formatted(amount, reason, UUID.randomUUID()))
                .post("/api/transactions/{transactionReference}/%s".formatted(path), transactionReference)
                .then().statusCode(200).body("type", equalTo(key.toUpperCase()));
    }

    private RequestSpecification request() {
        return RestAssured.given()
                .baseUri("http://127.0.0.1")
                .port(port)
                .header("X-Tesla-API-Key", "local-development-only")
                .contentType(ContentType.JSON);
    }
}
