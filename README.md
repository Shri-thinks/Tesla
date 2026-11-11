# Tesla

Tesla is a standalone Java 17 card-lifecycle API service. It routes lifecycle operations for Visa, Mastercard, and Amex through a common adapter interface. The default mock adapter makes the service runnable without network credentials or access to a payment network.

## Run

From this directory:

```shell
mvn spring-boot:run
```

By default, the service listens on `http://localhost:8080` and uses in-memory mock state.
The API requires the `X-Tesla-API-Key` header. The default key (`local-development-only`) is for local development only; set `TESLA_API_KEY` to a secret value before use in any shared environment.

## API

`POST /api/cards/{operation}` supports `issue`, `activate`, `suspend`, `unblock`, `replace`, and `close`.

```json
{
  "network": "VISA",
  "externalCardReference": "demo-card-001",
  "customerReference": "demo-customer-001",
  "idempotencyKey": "issue-demo-card-001"
}
```

The request uses provider-issued references only. Do not send PAN, CVV, PIN, or other sensitive authentication data. The mock adapter checks basic state transitions and returns `409 Conflict` for invalid transitions.

Example:

```shell
curl.exe -X POST http://localhost:8080/api/cards/issue -H "X-Tesla-API-Key: local-development-only" -H "Content-Type: application/json" --data-raw '{"network":"VISA","externalCardReference":"demo-card-001","customerReference":"demo-customer-001","idempotencyKey":"issue-demo-card-001"}'
```

## HTTP adapter configuration

To select the configurable HTTP adapter, set `TESLA_NETWORK_MODE=http` and provide per-network base URLs and bearer tokens:

```text
TESLA_API_KEY
TESLA_VISA_BASE_URL
TESLA_VISA_API_TOKEN
TESLA_MASTERCARD_BASE_URL
TESLA_MASTERCARD_API_TOKEN
TESLA_AMEX_BASE_URL
TESLA_AMEX_API_TOKEN
```

This starter adapter posts the normalized request to `{base-url}/cards/{operation}` and expects a JSON `CardLifecycleResponse`. It sends the idempotency key in the `Idempotency-Key` header and never logs tokens. Actual network APIs have distinct contracts, onboarding, and security requirements; implement or configure provider-specific mapping and validate against the relevant sandbox specifications before using this adapter against any real network. The built-in HTTP client accepts only HTTPS base URLs.

## Test

```shell
mvn test
```
