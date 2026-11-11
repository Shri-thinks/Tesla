# Tesla Card Lifecycle Simulator

Tesla is a Java 17 Spring Boot API for exercising a mock card and transaction lifecycle. It supports Visa, Mastercard, and Amex references without connecting to payment networks.

## Run

From this directory:

```shell
mvn spring-boot:run
```

By default, the service listens on `http://127.0.0.1:8080` and uses in-memory mock state. Stop any already-running instance before restarting it to load code changes.
The API requires the `X-Tesla-API-Key` header. The default key (`local-development-only`) is for local development only; set `TESLA_API_KEY` to a secret value before use in any shared environment.

## Mock account, card, and transaction API

Every request requires `X-Tesla-API-Key: local-development-only` and JSON content type when sending a body. The simulator creates opaque references and a clearly marked mock token; it never creates or accepts a PAN, CVV, or PIN. The PIN-change operation accepts only a reference to a secure external PIN-change flow.

### Onboard and issue a card

`POST /api/applications` accepts a customer reference, requested credit limit, and idempotency key. The mock policy approves limits up to 10,000 and declines larger requests. An approved application creates an open account.

```json
{
  "customerReference": "demo-customer-001",
  "requestedCreditLimit": 1000.00,
  "idempotencyKey": "application-demo-001"
}
```

Use the returned `accountReference` to issue a card:

`POST /api/accounts/{accountReference}/cards`

```json
{
  "network": "VISA",
  "idempotencyKey": "card-demo-001",
  "dailyLimit": 500.00
}
```

The card response contains a generated `cardReference`, `mock-token-*` token reference, and expiration month. It does not contain a card number. Activate it with `POST /api/cards/{cardReference}/activate`.

### Card maintenance

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/accounts/{accountReference}` | Account state and available credit |
| `POST` | `/api/accounts/{accountReference}/close` | Close account and its non-replaced cards |
| `GET` | `/api/cards/{cardReference}` | Card state, mock token reference, expiry, and limit |
| `POST` | `/api/cards/{cardReference}/activate` | Activate an issued card |
| `POST` | `/api/cards/{cardReference}/suspend` | Temporarily suspend an active card |
| `POST` | `/api/cards/{cardReference}/unblock` | Reactivate a suspended card |
| `POST` | `/api/cards/{cardReference}/block` | Block an issued, active, or suspended card |
| `POST` | `/api/cards/{cardReference}/lock` / `unlock` | Temporarily lock/unlock an active card |
| `POST` | `/api/cards/{cardReference}/replace` | Replace an active, suspended, or blocked card |
| `POST` | `/api/cards/{cardReference}/close` | Permanently close card |
| `PUT` | `/api/cards/{cardReference}/limits` | Set `{"dailyLimit": 100.00}` |
| `POST` | `/api/cards/{cardReference}/pin-change` | Accept `{"securePinChangeReference":"..."}`; no PIN is submitted |

Replacement card requests use the same JSON shape as card issuance and return the new card. Card/account actions that violate lifecycle state return `409 Conflict`.

### Transaction lifecycle

Initiate a transaction with `POST /api/transactions/initiate`:

```json
{
  "cardReference": "card-reference-from-issuance",
  "merchantReference": "demo-merchant-001",
  "amount": 25.00,
  "currency": "USD",
  "idempotencyKey": "purchase-demo-001"
}
```

Then call the stages in order, using the returned `transactionReference`:

| Method | Endpoint | Body / effect |
|---|---|---|
| `POST` | `/api/transactions/{transactionReference}/authorize` | Approve or decline; approval reserves a hold |
| `POST` | `/api/transactions/{transactionReference}/reverse` | Release an authorized hold |
| `POST` | `/api/transactions/{transactionReference}/clear` | `{"amount": 23.00}`; clears for the submitted amount |
| `POST` | `/api/transactions/{transactionReference}/settle` | Settle cleared transaction |
| `POST` | `/api/transactions/{transactionReference}/post` | Post to account balance and ledger |
| `POST` | `/api/transactions/{transactionReference}/refund` | Amount, reason, and idempotency key |
| `POST` | `/api/transactions/{transactionReference}/chargeback` | Amount, reason, and idempotency key |
| `POST` | `/api/transactions/{transactionReference}/disputes` | Reason and idempotency key |
| `POST` | `/api/transactions/{transactionReference}/fees` | Amount, reason, and idempotency key |
| `POST` | `/api/transactions/{transactionReference}/adjustments` | Amount, reason, and idempotency key |
| `GET` | `/api/transactions/{transactionReference}` | Transaction state |
| `GET` | `/api/accounts/{accountReference}/transactions` | Account transaction history |
| `GET` | `/api/accounts/{accountReference}/reconciliation` | Compare account balance with ledger |

Successful authorization returns `status: AUTHORIZED` and `decision: APPROVED`. A decline returns `status: DECLINED` and a reason in `message`. Reversals are allowed only before clearing; clearing, settlement, posting, and after-transaction events enforce their lifecycle states. Refunds and chargebacks cannot cumulatively exceed the posted transaction amount. Mutating requests with idempotency keys reject reuse of a key with a different request.

The simulator stores state only in process memory; all accounts, cards, holds, and ledger entries reset when the application restarts. This is a local testing implementation, not a production banking or payment system.

## Legacy network card lifecycle API

The original `POST /api/cards/{operation}` endpoint remains available for provider-style lifecycle tests. It supports `issue`, `activate`, `suspend`, `unblock`, `replace`, and `close`, using provider-issued references only:

```json
{
  "network": "VISA",
  "externalCardReference": "demo-card-001",
  "customerReference": "demo-customer-001",
  "idempotencyKey": "issue-demo-card-001"
}
```

## Optional HTTP network adapter

Set `TESLA_NETWORK_MODE=http` and provide per-network base URLs and bearer tokens:

```text
TESLA_API_KEY
TESLA_VISA_BASE_URL
TESLA_VISA_API_TOKEN
TESLA_MASTERCARD_BASE_URL
TESLA_MASTERCARD_API_TOKEN
TESLA_AMEX_BASE_URL
TESLA_AMEX_API_TOKEN
```

This adapter is for the legacy `/api/cards/{operation}` interface. It posts the normalized request to `{base-url}/cards/{operation}` and expects a JSON `CardLifecycleResponse`. It sends the idempotency key in the `Idempotency-Key` header and never logs tokens. Actual network APIs have distinct contracts and security requirements; validate provider-specific mappings against sandbox specifications before any real integration. The built-in HTTP client accepts only HTTPS base URLs.

## Test

```shell
mvn test
```

The RestAssured integration tests start the API on a random local port and exercise it over HTTP.
