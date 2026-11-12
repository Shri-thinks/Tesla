# Tesla Card Lifecycle Simulator

Tesla is a Java 17 Spring Boot website and API for exercising a mock card and transaction lifecycle. It supports Visa, Mastercard, and Amex references without connecting to payment networks. The configured database stores simulator state; Flyway applies schema migrations at startup.

## Run

From this directory:

```shell
mvn spring-boot:run
```

### Database options

The app runs without installing a database server: by default, it starts an H2 TCP server on `127.0.0.1:9123` and stores data in the `data` folder. The app and a database client such as DBeaver connect through TCP to the same file-backed database, so you can inspect committed transactions while the website remains running. The files survive application restarts. No Docker or database installation is required.

To inspect this database with DBeaver, create an **H2 Remote** (sometimes labeled **H2 Server**) connection using:

```text
JDBC URL: jdbc:h2:tcp://127.0.0.1:9123/tesla;MODE=PostgreSQL
User: sa
Password: (empty)
```

Do not use the embedded connection type or add `AUTO_SERVER=TRUE`. The application starts the H2 server and Flyway applies schema changes at startup. H2 accepts local clients only by default; do not expose port 9123 to an untrusted network.

The TCP port defaults to 9123 and can be changed with `TESLA_H2_TCP_PORT`; use the same port in DBeaver. H2 accepts local clients only by default; the app does not enable remote access. The data folder defaults to `./data` relative to the project working directory and can be changed with `TESLA_H2_BASE_DIR`.

PostgreSQL is also supported if you already have it installed. Set the database mode and connection environment variables before launching the app:

```powershell
$env:TESLA_DATABASE_MODE = "postgres"
$env:TESLA_DB_URL = "jdbc:postgresql://127.0.0.1:5432/tesla"
$env:TESLA_DB_USERNAME = "tesla"
$env:TESLA_DB_PASSWORD = "your-local-password"
```

Docker Desktop is optional. If it is available, the included Compose file can start a local PostgreSQL instance:

```powershell
docker compose up -d
```

The Compose database is named `tesla`, with local development credentials `tesla` / `tesla-local-password`, and keeps its data in the `tesla-postgres-data` Docker volume.

### Start the application

By default, the service listens on `http://127.0.0.1:8080`. It remains bound to loopback for local testing. Stop any already-running instance before restarting it to load code changes.
The API requires the `X-Tesla-API-Key` header. The default key (`local-development-only`) is for local development only; set `TESLA_API_KEY` to a secret value before use in any shared environment.

Database connection settings can be overridden with `TESLA_DB_URL`, `TESLA_DB_USERNAME`, and `TESLA_DB_PASSWORD`. Flyway automatically creates and updates the tables in `src/main/resources/db/migration`.

## Website and API testing

After starting the app:

- Open the lifecycle website at [http://127.0.0.1:8080](http://127.0.0.1:8080).
- Open the interactive Swagger API explorer at [http://127.0.0.1:8080/swagger-ui/index.html](http://127.0.0.1:8080/swagger-ui/index.html).
- Fetch the OpenAPI definition at [http://127.0.0.1:8080/v3/api-docs](http://127.0.0.1:8080/v3/api-docs).

In the website's Connection settings, enter `local-development-only` as the API key and save. The website sends API requests to the local backend and shows request/response details in its activity log. The key is kept only in the current browser tab's session storage. In Swagger UI, click **Authorize** and enter the same value for `TeslaApiKey`. The API key is required for API calls; the website files and API documentation can be loaded without it.

The app binds to loopback (`127.0.0.1`) by default, keeping the mock API local to the development machine. It is not intended to be exposed to a public network.

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

Applications, accounts, cards, transactions, ledger events, and idempotency results are stored in PostgreSQL and remain available after an application restart. The UI and API never connect directly to PostgreSQL; use the backend for application behavior and a database client for database checks. This is a local testing implementation, not a production banking or payment system.

### Database testing examples

Connect DBeaver using the H2 settings above, or use your PostgreSQL client if running PostgreSQL. Inspect the lifecycle tables with:

```sql
SELECT * FROM applications ORDER BY application_reference;
SELECT * FROM accounts ORDER BY account_reference;
SELECT * FROM cards ORDER BY card_reference;
SELECT * FROM simulated_transactions ORDER BY created_at;
SELECT * FROM ledger_events ORDER BY created_at;
SELECT * FROM idempotency_results ORDER BY scope, idempotency_key;
```

The test suite uses isolated H2 databases in PostgreSQL compatibility mode and runs the same Flyway migration. It checks persisted account, card, transaction, and idempotency rows, plus simultaneous app and TCP database-client access. To run it:

```powershell
mvn test
```

To stop the local database while retaining its data:

```powershell
docker compose stop
```

To stop it and remove the database volume/data, use `docker compose down -v`. This removes the simulator database contents.

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
