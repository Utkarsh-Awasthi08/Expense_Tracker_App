# contracts

Source of truth for the messages that cross service boundaries. Prose lives in [`../docs/CONTRACTS.md`](../docs/CONTRACTS.md); this directory holds the machine-readable form.

- `schemas/` - JSON Schema 2020-12 for `expense.parsed.v1` (ds to expense) and `user.created.v1` (auth to user); `additionalProperties: false`, so producers cannot drift.
- `fixtures/expense.parsed.v1.json`, `fixtures/user.created.v1.json` - one valid example event each.
- `fixtures/sms_samples.json` - realistic Indian bank/UPI/card SMS with the expected extractor output and `sms_hash` (includes an exact duplicate and a whitespace/case variant). Amounts are kept as written with commas removed; non-transaction messages (OTP, promo, balance) have null amount, merchant, account_last4 and txn_date.
- `validate_fixtures.py` - checks fixtures against schemas, recomputes every `sms_hash`, checks enums/patterns and duplicates. Run `dsService/dsenv/bin/python contracts/validate_fixtures.py` (needs `jsonschema`; exits non-zero on failure).
- Java services (expense, user, auth): tests read `../contracts/schemas` and `../contracts/fixtures` (relative to the module) to prove their DTOs and mappers accept every valid event and that outgoing events validate.
- dsService: pytest reads `contracts/` from the repo root (the Docker build context cannot see it, so ds validates against the schemas in tests only): output of the extractor is checked against `expense.parsed.v1.schema.json`, and `sms_samples.json` drives the `FakeExtractor`, the hash vectors and the endpoint tests.
- `scripts/e2e.sh` posts `sms_samples.json` through the gateway and asserts the rows that land in expenseService (publishable samples only; duplicates must not add rows).
- Changing a schema or fixture means updating every producer's and consumer's tests in the same change.
