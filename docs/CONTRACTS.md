# Service contracts

This document pins the contracts between the gateway, authService, userService, expenseService and dsService: identity headers, JWT, error body, HTTP APIs, Kafka events, `sms_hash` and environment names.
These contracts are the source of truth for every service; a change to a contract means updating this document, the schemas and the fixtures in the same change.
The Kafka event contracts also exist in machine-readable form as JSON Schemas in [`contracts/schemas/`](../contracts/schemas), with example payloads and SMS samples in [`contracts/fixtures/`](../contracts/fixtures).
Every service's tests read those schemas and fixtures; `python contracts/validate_fixtures.py` checks the fixtures against the schemas and recomputes every `sms_hash`.
The Implementation notes appendix at the end holds the architecture diagram and the Hibernate and Kafka build hazards that follow from these contracts.

---

## Pinned contracts

Written once in Stage 1 to `docs/CONTRACTS.md`, `contracts/schemas/*.schema.json`, `contracts/fixtures/*.json`; every work package reads them.

- **Identity headers** (gateway→services): `X-User-Id` (UUID), `X-User-Roles` (comma list). Always stripped inbound, including public routes; set with `request.mutate().headers(...)` (Reactor headers are read-only).
- **JWT:** `alg=RS256`, `kid` = base64url SHA-256 thumbprint; claims `iss` (`JWT_ISSUER`, default `expense-tracker-auth`), `sub`=userId, `username`, `roles[]` (verbatim, e.g. `ROLE_USER`), `iat`, `exp`, `jti`. Access 15 min, refresh 30 d. JWKS at `GET /auth/v1/.well-known/jwks.json` (public in auth; not reachable through the gateway without a token).
- **Error body (all services incl. gateway 401/403):** `{"timestamp","status","error","code","message","path"}`; codes `UNAUTHORIZED, FORBIDDEN, VALIDATION_FAILED(+details[]), NOT_FOUND, CONFLICT, BAD_REQUEST, BATCH_TOO_LARGE, INTERNAL`; 401 adds `WWW-Authenticate: Bearer`.
- **Auth API:** `POST signup` (snake_case: `username,password,first_name,last_name,phone_number`(string)`,email`; 200 empty body; 409 duplicate; 400 `VALIDATION_FAILED` otherwise); `POST login {username,password}` → `{accessToken, token, tokenType:"Bearer", expiresIn}` (camelCase kept); `POST refreshToken {token}` → same shape, **rotates**; `POST logout {token}` → 204; `GET ping` → plain-text userId (kept); `GET /health` → `true`. Refresh token = opaque 256-bit base64url, stored as SHA-256 hash, many per user; presenting a rotated token just returns 401 (no family-revoke). **Username:** free text as the untouched mobile app sends it (often an email); trimmed (spaces only) and ASCII-lower-cased first, then must match `^[a-z0-9._@+-]{3,50}$` (ASCII only: spaces, control characters and Unicode look-alikes are 400 `VALIDATION_FAILED`); the normalized form is what is stored, uniqueness is checked on and the JWT `username` claim carries. **Phone:** `phone_number` is optional (blank = absent); spaces, hyphens, dots and parentheses are stripped first, then it must match `^\+?[0-9]{7,15}$`; the normalized form is what is validated and what `user.created.v1` carries. **Login** normalizes `username` the same way, then requires exact string equality with the stored username (MySQL's accent- and ignorable-insensitive collation must not let `admïn`, `ADMİN` or `admin<ZWSP>` in as `admin`), otherwise it is the same 401 as an unknown user or a wrong password; a failure of the user store is logged and answered 503 `INTERNAL`, never 401. **Public endpoints ignore any `Authorization` header** (an expired, forged or garbage bearer is never evaluated, so a client can still call `refreshToken` with a stale access token): `POST signup, login, refreshToken, logout` and `GET .well-known/jwks.json, /health, /actuator/health`; every other path, `GET ping` included, still answers a stale, forged or `alg=none` bearer with 401. `/error` is open only as Boot's container error dispatch; a direct call to it is an ordinary unauthenticated request (401).
- **User API:** `GET|PUT /user/v1/me` (partial update of `first_name,last_name,phone_number,email,profile_picture,default_currency,timezone`); `GET` 404 until the created-event lands. `createUpdate`/`getUser` removed.
- **Expense API:** `GET /expense/v1/expenses?from&to&page&size` → `{items,page,size,total_elements,total_pages}` (size ≤ 200, `txn_date desc, id desc`); `POST /expense/v1/expenses` JSON body → 201; **alias `GET /expense/v1/getExpense`** → bare array (newest 500), `amount` a JSON **number**, `created_at = coalesce(sms_received_at, created_at)` ISO, so the untouched mobile list keeps working (`Expense.tsx` calls `amount.toFixed`).
- **SMS ingest:** public `POST /sms/v1/ingest` `{sender,body,received_at}` and `/sms/v1/ingest/batch` `{messages:[…]}` → ds `/v1/ds/ingest[/batch]`; `X-User-Id` required. Per-item result `{index, sms_hash, status: published|skipped_not_expense|failed, error?: invalid_input|llm_error|llm_invalid|publish_error}`; overall 200; 413 `BATCH_TOO_LARGE`.
- **`sms_hash`** = `sha256_hex(upper(sender)|epoch_ms(received_at)|" ".join(body.split()))`; dedup key `(user_id, sms_hash)`.
- **`expense.parsed.v1`** (key=user_id, 3 partitions, `.DLT`): `schema_version=1, user_id, sms_hash, sender, sms_received_at, is_expense, txn_type(DEBIT|CREDIT|OTHER), amount(decimal string), currency, merchant, category, account_last4, txn_date`. ds publishes only when `is_expense` and `amount>0`; consumer drops `is_expense=false`; unknown `schema_version` is non-retryable. Categories: FOOD, GROCERIES, TRANSPORT, SHOPPING, BILLS_UTILITIES, ENTERTAINMENT, HEALTH, TRAVEL, EDUCATION, RENT, EMI_LOANS, INVESTMENT, TRANSFER, CASH_WITHDRAWAL, OTHER.
- **`user.created.v1`** (key=user_id, `.DLT`): `schema_version=1, event_id, user_id, username, first_name, last_name, phone_number(string), email, created_at`. **Consumer is create-only** (insert if absent, else no-op) so a stale event can't overwrite `PUT /me` edits.
- **Env names (identical across services):** `DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD` (no default), `KAFKA_BOOTSTRAP_SERVERS` (default `localhost:29092`), `USER_CREATED_TOPIC`, `EXPENSE_PARSED_TOPIC`; auth `JWT_ISSUER, JWT_PRIVATE_KEY_PATH, JWT_ACCESS_TTL(PT15M), JWT_REFRESH_TTL(P30D)` (public key derived from the private key, no second file); gateway `JWT_ISSUER, JWT_JWKS_URI, AUTH_SERVICE_URL, USER_SERVICE_URL, EXPENSE_SERVICE_URL, DS_SERVICE_URL`; ds `LLM_PROVIDER, MISTRAL_API_KEY, MISTRAL_MODEL, PORT, BATCH_MAX_MESSAGES(100), LLM_TIMEOUT_SECONDS(20), KAFKA_SEND_TIMEOUT_SECONDS(10)`. Consumer groups `user-service`, `expense-service`.

---

## Implementation notes

Appendix. Context for implementers; the contracts above are what services must honour.

### Target architecture

```
client ─> gatewayService :8000 (Spring Cloud Gateway 2025.0.3 / WebFlux)
          verifies JWT via JWKS, strips inbound X-User-Id / X-User-Roles, sets them from the token
          public : /auth/v1/{signup,login,refreshToken,logout}, /actuator/health
          authed : /auth/v1/**  -> auth-service :9898
                   /user/v1/**  -> user-service :8888
                   /expense/v1/** -> expense-service :9820
                   /sms/v1/(?<seg>.*) -> ds-service :8010  /v1/ds/${seg}   (route timeout 120s)
Kafka (apache/kafka:3.9.2, KRaft)  kafka:9092 internal / 127.0.0.1:29092 host
  user.created.v1    auth --outbox--> user      (+ .DLT)
  expense.parsed.v1  ds ------------> expense   (+ .DLT)
MySQL 8.4: dbs authservice / userservice / expenseservice, one least-privilege user each
```
Services trust `X-User-Id` only because their ports aren't published, and their filters reject non-UUID values. The real hardening (each service validating the JWT) is deferred and noted in the README. **No override file may publish backend ports.**

### Build hazards baked into every Java package (Hibernate and Kafka)

- **Hibernate `validate` + Flyway on MySQL:** IDENTITY ids (`BIGINT AUTO_INCREMENT`, not `AUTO` → `_seq` tables); UUIDs as `String`/`VARCHAR(36)`; enums as `String` columns; `VARCHAR(n)` not `CHAR/TEXT`; explicit `@Column(name=…)`; `Instant`/`LocalDate`; `hibernate.jdbc.time_zone=UTC`; `open-in-view=false`; include `flyway-mysql`; drop `MySQL8Dialect`, `ddl-auto=create`, `hbm2ddl.auto=update`, `createDatabaseIfNotExist`. `validate` doesn't check unique indexes, so tests insert real duplicates.
- **Kafka:** `ErrorHandlingDeserializer` + `DefaultErrorHandler` (≈2 min exponential backoff) + `DeadLetterPublishingRecoverer` with `DelegatingByTypeSerializer` (byte[] + JSON) and destination `TopicPartition(topic+".DLT", -1)`; `spring.json.use.type.headers=false`, explicit trusted package; `@JsonNaming(SnakeCase)` + `ignoreUnknown` per DTO, **never** a global Jackson naming strategy (would break auth's camelCase). Bad JSON / validation / unknown schema_version → straight to DLT. **No `NewTopic` beans** — `kafka-init` is the single source of truth; `missing-topics-fatal=false`. userService: delete `UserConfig`'s `new ObjectMapper()` bean and the pinned `jackson-databind:2.16.1`.

### Clarifications for `sms_hash` (non-normative)

These pin implementation details the contract leaves implicit; they do not change it. The vectors in `contracts/fixtures/sms_samples.json` are the executable reference.

- The hashed string is encoded as UTF-8 (bank SMS can contain the rupee sign).
- `epoch_ms` is an integer count of milliseconds since 1970-01-01T00:00:00Z, so the UTC offset in `received_at` does not matter and milliseconds are kept (`...09.250+05:30` is not the same as `...09+05:30`).
- `body.split()` with no arguments splits on any run of whitespace and drops leading/trailing whitespace; the sender is upper-cased before hashing. A lower-case sender or a body with extra spaces or newlines therefore hashes the same as the clean message.

```python
import hashlib
from datetime import datetime, timedelta, timezone

def sms_hash(sender: str, received_at: str, body: str) -> str:
    dt = datetime.fromisoformat(received_at)  # must carry an offset, e.g. 2026-09-12T20:41:07+05:30
    ms = (dt - datetime(1970, 1, 1, tzinfo=timezone.utc)) // timedelta(milliseconds=1)
    return hashlib.sha256(f"{sender.upper()}|{ms}|{' '.join(body.split())}".encode("utf-8")).hexdigest()
```
