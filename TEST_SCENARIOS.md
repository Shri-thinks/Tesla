# Card Lifecycle Lab — Test Scenario Reference

This document describes practical positive, negative, UI, API, and database checks for the Tesla Card Lifecycle Lab. It is a test reference, not an automation framework.

## 1. Test layers

| Layer | Use it for |
|---|---|
| API (RestAssured) | Business rules, validation, HTTP status codes, lifecycle transitions, authorization decisions, and idempotency. This should contain most test cases. |
| UI (Selenium) | Page rendering, form validation, visible responses, auto-filled references, navigation, loading behavior, and representative end-to-end journeys. |
| Database | Persistence, record relationships, account balances, ledger entries, holds, and reconciliation after API or UI actions. |
| Combined | A small number of cross-layer scenarios: perform an action through one layer, verify it in another, then assert persisted state in the database. |

Prefer API tests for exhaustive rule combinations. Use Selenium to verify the website's behavior rather than repeating every API test through the browser. Use database assertions to verify the effect of important operations, not as a substitute for checking the API response.

## 2. Test setup and conventions

- The API requires `X-Tesla-API-Key`. The local default is `local-development-only`.
- The website defaults to the current app origin. Enter the API key in Connection settings before submitting requests.
- Use a unique customer reference, merchant reference, and idempotency key for each test run. The website's **Test run references** helper can generate customer and merchant values.
- Capture account, card, and transaction references from responses. Do not rely on records being returned in a particular order.
- Data persists in the configured database across server restarts. Use an isolated database per test run or CI job; do not casually clear the local database.
- Where a response represents a business decline, the HTTP request can still succeed. Assert both the HTTP status and the JSON business status/decision.
- Use explicit Selenium waits for `data-state` and `data-http-status`; do not use fixed sleeps.

The default mock application policy approves requested credit limits up to and including `10000.00`. Larger applications are declined. This is simulator behavior, not a real credit decision.

## 3. Application and account creation

Endpoint: `POST /api/applications`

| ID | Scenario | Expected assertions | Recommended layer |
|---|---|---|---|
| APP-01 | Apply for `1000.00` with valid customer reference and idempotency key | HTTP `201`; `status=APPROVED`; account reference is present; approved limit equals requested limit | API; one UI smoke |
| APP-02 | Apply for exactly `10000.00` | HTTP `201`; `status=APPROVED`; account reference is present | API |
| APP-03 | Apply for `10000.01` | HTTP `201`; `status=DECLINED`; account reference is null; approved limit is zero | API; UI response smoke |
| APP-04 | Blank or omit customer reference | HTTP `400`; no account created | API; UI required-field check |
| APP-05 | Omit, zero, or use a negative requested limit | HTTP `400`; no account created | API; UI browser validation |
| APP-06 | Use more than two fractional digits or exceed numeric precision | HTTP `400` | API |
| APP-07 | Omit or blank the idempotency key | HTTP `400` | API |
| APP-08 | Submit without an API key or with an incorrect key | HTTP `401` | API; UI shows unauthorized response for wrong key |
| APP-09 | Repeat identical application request with the same idempotency key | Same logical application response; no duplicate application/account | API + database |
| APP-10 | Reuse an application idempotency key with a different request body | HTTP `409`; original result remains unchanged | API + database |

**UI assertions for APP-01 and APP-03**

- Wait for `#application-result` to leave `data-state="loading"`.
- Assert its final state is `success` or `error` as appropriate, and its `data-http-status` matches the response.
- For approval, assert the visible JSON contains `APPROVED` and an account reference; assert the reference is copied into the issue-card and account-lookup inputs.
- For a decline, assert the response says `DECLINED` and no account reference is filled.
- Assert an activity-log entry is added for the API request.

## 4. Card issuance and maintenance

Card issuance endpoint: `POST /api/accounts/{accountReference}/cards`

| ID | Scenario | Expected assertions | Recommended layer |
|---|---|---|---|
| CARD-01 | Issue a VISA card to an open account | HTTP `201`; `status=ISSUED`; network, daily limit, card reference, token reference, and expiry are present | API; one UI smoke |
| CARD-02 | Issue MASTERCARD and AMEX cards | HTTP `201`; returned network matches the request | API |
| CARD-03 | Set daily limit equal to the account credit limit | HTTP `201`; card is issued | API |
| CARD-04 | Set daily limit higher than account credit limit | HTTP `400`; no card created | API |
| CARD-05 | Use an unknown account reference | HTTP `404` | API |
| CARD-06 | Issue against a closed account | HTTP `409` | API |
| CARD-07 | Omit idempotency key, network, or daily limit; use zero/negative limit | HTTP `400` | API; UI field validation where applicable |
| CARD-08 | Repeat identical issuance with the same idempotency key | Same card result; one card row | API + database |
| CARD-09 | Reuse issuance idempotency key with a different network or limit | HTTP `409`; original card remains unchanged | API + database |

Card actions and lifecycle tests:

| ID | Scenario | Expected assertions | Recommended layer |
|---|---|---|---|
| CARD-10 | Activate an issued card | HTTP `200`; card state becomes `ACTIVE` | API; UI smoke |
| CARD-11 | Suspend an active card | HTTP `200`; state becomes `SUSPENDED` | API |
| CARD-12 | Unblock a suspended card | HTTP `200`; state becomes `ACTIVE` | API |
| CARD-13 | Block an issued, active, or suspended card | HTTP `200`; state becomes `BLOCKED` | API |
| CARD-14 | Lock an active card | HTTP `200`; `temporarilyLocked=true` | API; UI smoke |
| CARD-15 | Unlock a temporarily locked active card | HTTP `200`; `temporarilyLocked=false` | API |
| CARD-16 | Update the limit on an active card to a valid amount | HTTP `200`; returned daily limit matches | API |
| CARD-17 | Change PIN using a nonblank secure-flow reference | HTTP `200`; `status=ACCEPTED`; no PIN is submitted or stored | API; UI smoke |
| CARD-18 | Replace an active, suspended, or blocked card | HTTP `200`; new card reference returned; old card becomes `REPLACED` | API; one UI smoke |
| CARD-19 | Close a card | HTTP `200`; state becomes `CLOSED` | API |
| CARD-20 | Fetch card details | HTTP `200`; returned fields match the latest card state | API; UI |
| CARD-21 | Operate on an unknown card reference | HTTP `404` | API |
| CARD-22 | Activate an already-active card, suspend a non-active card, or unblock a non-suspended card | HTTP `409`; card state remains unchanged | API |
| CARD-23 | Lock an inactive card, lock twice, or unlock when not locked | HTTP `409`; state remains unchanged | API |
| CARD-24 | Change limits on a non-active card | HTTP `409`; limit is unchanged | API |
| CARD-25 | Submit blank PIN-flow reference or invalid limit | HTTP `400`; no state change | API; UI required-field validation |
| CARD-26 | Replace an issued card or a closed/replaced card | HTTP `409`; old card state remains unchanged | API |

**UI assertions**

- After issuance, the returned card reference is visible and copied into the card controls and transaction form.
- After a card action, wait for the card result to complete and assert response HTTP status and resulting card state/fields.
- The activity log contains the request path, HTTP status, and response.

## 5. Transaction initiation and authorization

Initiation endpoint: `POST /api/transactions/initiate`

| ID | Scenario | Expected assertions | Recommended layer |
|---|---|---|---|
| TXN-01 | Initiate a valid transaction | HTTP `201`; `status=INITIATED`; `decision=PENDING`; transaction reference is present | API; one UI smoke |
| TXN-02 | Use valid uppercase three-letter currencies (for example `USD`, `INR`) | HTTP `201`; returned currency matches | API |
| TXN-03 | Omit required fields, use blank references, or submit zero/negative amount | HTTP `400`; no transaction created | API; UI field validation |
| TXN-04 | Use lowercase or malformed currency | HTTP `400` | API |
| TXN-05 | Use an unknown card reference | HTTP `404` | API |
| TXN-06 | Initiate with a known but inactive/locked card | Initiation may return HTTP `201`; authorization returns HTTP `200`, `status=DECLINED`, `decision=DECLINED`, and a reason | API; one UI negative case |
| TXN-07 | Authorize a transaction over the daily spend limit | HTTP `200`; `status=DECLINED`; message identifies the daily limit | API |
| TXN-08 | Authorize a transaction over available credit | HTTP `200`; `status=DECLINED`; message identifies insufficient credit | API |
| TXN-09 | Authorize a valid initiated transaction | HTTP `200`; `status=AUTHORIZED`; `decision=APPROVED`; authorization code is present | API; UI smoke |
| TXN-10 | Repeat identical initiation with the same idempotency key | Same transaction reference; one transaction row | API + database |
| TXN-11 | Reuse initiation idempotency key with changed request | HTTP `409`; no second transaction | API + database |
| TXN-12 | Authorize twice or authorize from a state other than `INITIATED` | HTTP `409`; state remains unchanged | API |

Authorization declines are business outcomes, not HTTP failures. Assert HTTP `200` and the response's `DECLINED` status/decision and reason.

## 6. Holds, reversal, clearing, settlement, and posting

| ID | Scenario | Expected assertions | Recommended layer |
|---|---|---|---|
| LIFE-01 | Successfully authorize a transaction | Available credit is reduced by the hold; posted balance remains unchanged | API + database |
| LIFE-02 | Reverse an authorized transaction | HTTP `200`; state is `REVERSED`; available credit is restored; no posting event exists | API + database |
| LIFE-03 | Reverse before authorization | HTTP `409`; state remains `INITIATED` | API |
| LIFE-04 | Reverse after clearing | HTTP `409`; state remains unchanged | API |
| LIFE-05 | Clear an authorized transaction with a valid amount | HTTP `200`; state becomes `CLEARED`; transaction amount matches clearing request | API + database |
| LIFE-06 | Clear with zero, negative, or malformed amount | HTTP `400`; transaction state unchanged | API |
| LIFE-07 | Clear for more than available credit | HTTP `409`; transaction remains authorized | API |
| LIFE-08 | Clear before authorization or clear twice | HTTP `409`; no extra state transition | API |
| LIFE-09 | Settle a cleared transaction | HTTP `200`; state becomes `SETTLED` | API + database |
| LIFE-10 | Settle before clearing or settle twice | HTTP `409` | API |
| LIFE-11 | Post a settled transaction | HTTP `200`; state becomes `POSTED`; account posted balance increases; one `POSTING` ledger entry is created | API + database |
| LIFE-12 | Post before settlement or post twice | HTTP `409`; no duplicate posting/ledger entry | API + database |
| LIFE-13 | Complete initiate → authorize → clear → settle → post | Assert every transition and final account, transaction, ledger, and reconciliation values | API; one UI end-to-end smoke |

The simulator allows clearing for an amount different from the original initiation amount, subject to its available-credit check. Do not assume it enforces a clearing amount ceiling equal to the authorization amount.

## 7. Refunds, chargebacks, disputes, fees, and adjustments

These events require the transaction to be `POSTED`.

| ID | Scenario | Expected assertions | Recommended layer |
|---|---|---|---|
| EVT-01 | Issue a partial or full refund | HTTP `200`; event type `REFUND`; ledger amount is negative; posted balance decreases | API + database |
| EVT-02 | Issue a partial or full chargeback | HTTP `200`; event type `CHARGEBACK`; ledger amount is negative; posted balance decreases | API + database |
| EVT-03 | Refund/chargeback cumulative amount exceeds posted transaction amount | HTTP `409`; no event and no balance change | API + database |
| EVT-04 | Submit refund/chargeback before posting | HTTP `409`; no event created | API |
| EVT-05 | Create a dispute on a posted transaction | HTTP `200`; event type `DISPUTE`; amount is zero; reason is persisted | API + database |
| EVT-06 | Add a fee | HTTP `200`; event type `FEE`; positive ledger amount; posted balance increases | API + database |
| EVT-07 | Add an adjustment | HTTP `200`; event type `ADJUSTMENT`; positive ledger amount; posted balance increases | API + database |
| EVT-08 | Submit a missing/blank reason or idempotency key | HTTP `400`; no event created | API |
| EVT-09 | Submit zero/negative/malformed event amount | HTTP `400`; no event created | API |
| EVT-10 | Repeat identical event request with the same idempotency key | Same event result; one ledger entry and one balance change | API + database |
| EVT-11 | Reuse event idempotency key with changed request | HTTP `409`; original event remains | API + database |
| EVT-12 | Submit an event for an unknown transaction | HTTP `404` | API |

## 8. Account closure, history, and reconciliation

| ID | Scenario | Expected assertions | Recommended layer |
|---|---|---|---|
| ACCT-01 | Fetch an open account | HTTP `200`; status, credit limit, posted balance, available credit, and card references are correct | API; UI account summary |
| ACCT-02 | Close an account with no unsettled funds | HTTP `200`; account becomes `CLOSED`; non-replaced cards become `CLOSED` | API + database |
| ACCT-03 | Close an account with authorized/cleared/settled funds | HTTP `409`; account remains open | API |
| ACCT-04 | Close an already-closed account | HTTP `409` | API |
| ACCT-05 | Issue a card on a closed account | HTTP `409` | API |
| ACCT-06 | List account transactions | HTTP `200`; list includes that account's transactions and excludes other accounts' | API; UI history |
| ACCT-07 | Reconcile a new account | HTTP `200`; `difference=0`; `reconciled=true` | API; UI |
| ACCT-08 | Reconcile after posting | Posted balance equals ledger balance; `reconciled=true` | API + database |
| ACCT-09 | Reconcile after refund, fee, or adjustment | Balance and signed ledger entries agree; `reconciled=true` | API + database |
| ACCT-10 | Fetch unknown account / history / reconciliation | HTTP `404` | API |

Account closure is available through the API but is not exposed as a control on the current website. Test it through RestAssured.

## 9. Website-specific Selenium checks

Use the stable `data-testid` selectors and state attributes added for UI automation. Keep this suite focused on browser behavior:

1. **Page and navigation:** page loads; section navigation works; API Explorer link opens Swagger.
2. **Connection settings:** default base URL is shown; missing API key produces a useful error; a valid key allows API calls; an invalid key shows HTTP `401`.
3. **Application approval:** submit a valid application; assert result `data-state`, HTTP status `201`, `APPROVED`, visible account reference, and auto-filled downstream fields.
4. **Application decline:** submit over the approval threshold; assert visible `DECLINED`, HTTP `201`, and no account reference.
5. **Card flow:** issue and activate a card; assert card reference is available in card controls and the transaction form.
6. **Transaction flow:** initiate and advance a transaction; assert each result and activity-log entry.
7. **Visible negative case:** perform an invalid lifecycle operation; assert HTTP `409` and readable error in the result panel.
8. **HTML validation:** submit required fields empty or invalid amounts; browser prevents submission and no API activity entry is added.
9. **Loading/completion:** assert `aria-busy="true"` while a request is pending, then wait for result `data-state` to become `success` or `error`; assert `data-http-status`.
10. **Run references:** apply a run ID and assert customer and merchant inputs use it.
11. **Activity log:** assert newest entry shows the request path, method, status, and response.

Prefer selectors such as `[data-testid="application-result"]` and state/status attributes over CSS classes or visual layout. Use explicit waits rather than fixed delays.

## 10. Database verification

Use the API response references to query rows after the action. The default local H2 connection is:

```text
jdbc:h2:tcp://127.0.0.1:9123/tesla;MODE=PostgreSQL
User: sa
Password: (empty)
```

Example queries:

```sql
SELECT * FROM applications
WHERE customer_reference = '<unique-customer-reference>';

SELECT * FROM accounts
WHERE account_reference = '<account-reference>';

SELECT * FROM cards
WHERE card_reference = '<card-reference>';

SELECT * FROM simulated_transactions
WHERE transaction_reference = '<transaction-reference>';

SELECT * FROM ledger_events
WHERE transaction_reference = '<transaction-reference>'
ORDER BY created_at;

SELECT * FROM idempotency_results
WHERE idempotency_key = '<idempotency-key>';
```

Database expectations:

- **Approved application:** one application row and one account row. **Declined application:** application row exists, account reference is null, and no account row is created.
- **Card action:** card row exists and reflects the latest lifecycle state, limit, and lock status.
- **Authorization:** transaction is `AUTHORIZED`; hold reduces available credit; posted balance is unchanged.
- **Reversal:** transaction is `REVERSED`; hold is released; no posting event exists.
- **Posting:** transaction is `POSTED`; one `POSTING` event exists; account balance increases by the posted amount.
- **Refund/chargeback:** negative ledger row and corresponding decrease in balance.
- **Fee/adjustment:** positive ledger row and corresponding increase in balance.
- **Idempotent retry:** no duplicate logical transaction/card/event is created.
- **Reconciliation:** `postedBalance = ledgerBalance`, `difference = 0`, and `reconciled = true`.

The schema is defined in `src/main/resources/db/migration/V1__create_simulator_tables.sql`.

## 11. Recommended execution order

1. Run API tests for validation, idempotency, lifecycle transitions, decline decisions, and negative cases using isolated data.
2. Run a small Selenium smoke suite for approval → card → transaction → reconciliation and one visible negative case.
3. Verify persisted records and ledger/balance consistency in the database using captured references.
4. In CI, provision a fresh database for each run and use unique references. Avoid running parallel jobs against the same file-backed local H2 database.

## 12. Useful status-code summary

| Result | Typical HTTP status |
|---|---:|
| Resource created (application, card, transaction initiation) | `201` |
| Successful lookup or lifecycle action | `200` |
| Invalid/missing request fields | `400` |
| Missing or incorrect API key | `401` |
| Unknown resource reference | `404` |
| Invalid lifecycle transition, unsettled account closure, or idempotency conflict | `409` |
| Business authorization decline | Usually `200` with `status=DECLINED` and `decision=DECLINED` |
