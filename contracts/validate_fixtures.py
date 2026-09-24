#!/usr/bin/env python3
"""Validate the contract fixtures against the contract schemas.

Checks
  1. Both schemas are valid JSON Schema 2020-12 and pin the plan's enums.
  2. expense.parsed.v1.json and user.created.v1.json validate against their schemas,
     and the schemas reject drifted payloads (extra field, bad enum, bad pattern, ...).
  3. Every sms_samples.json entry: expected fields obey the enums/patterns, the
     expected sms_hash recomputes exactly, duplicates hash identically, and every
     publishable sample (is_expense and amount > 0) forms a valid expense.parsed.v1 event.

Run from anywhere:  python contracts/validate_fixtures.py   (Python 3.11+, needs jsonschema)
Exit code 0 = all good, 1 = at least one failure.
"""
import hashlib
import json
import re
import sys
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parent
SCHEMAS = ROOT / "schemas"
FIXTURES = ROOT / "fixtures"

# The plan's pinned enums. The schemas must match these exactly.
CATEGORIES = [
    "FOOD", "GROCERIES", "TRANSPORT", "SHOPPING", "BILLS_UTILITIES", "ENTERTAINMENT",
    "HEALTH", "TRAVEL", "EDUCATION", "RENT", "EMI_LOANS", "INVESTMENT", "TRANSFER",
    "CASH_WITHDRAWAL", "OTHER",
]
TXN_TYPES = ["DEBIT", "CREDIT", "OTHER"]

AMOUNT_RE = re.compile(r"^\d+(\.\d{1,2})?$")
CURRENCY_RE = re.compile(r"^[A-Z]{3}$")
LAST4_RE = re.compile(r"^\d{4}$")
HASH_RE = re.compile(r"^[0-9a-f]{64}$")
RECEIVED_AT_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?\+05:30$")
RFC3339_RE = re.compile(r"^\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(\.\d+)?([Zz]|[+-]\d{2}:\d{2})$")

EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
SAMPLE_USER_ID = "3f6c2b7e-9d1a-4c58-8e0b-5a1d7c4f2e91"


# ---- sms_hash: sha256_hex(upper(sender)|epoch_ms(received_at)|" ".join(body.split())) ----
def parse_instant(text):
    """Parse an RFC 3339 / ISO-8601 timestamp to an aware datetime."""
    dt = datetime.fromisoformat(re.sub(r"[Zz]$", "+00:00", text))
    if dt.tzinfo is None:
        raise ValueError(f"timestamp has no offset: {text!r}")
    return dt


def epoch_ms(received_at):
    """Integer milliseconds since the Unix epoch (exact integer arithmetic, no floats)."""
    return (parse_instant(received_at) - EPOCH) // timedelta(milliseconds=1)


def sms_hash(sender, received_at, body):
    canonical = f"{sender.upper()}|{epoch_ms(received_at)}|{' '.join(body.split())}"
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


# ---- reporting ----
class Report:
    def __init__(self):
        self.passed = 0
        self.failed = 0

    def check(self, ok, label, detail=""):
        if ok:
            self.passed += 1
        else:
            self.failed += 1
            print(f"FAIL  {label}" + (f"  -> {detail}" if detail else ""))
        return ok

    def section(self, title):
        print(f"\n== {title}")


def make_format_checker():
    """Stock format checker plus a stdlib date-time check (the stock one needs an optional package)."""
    fc = FormatChecker()

    @fc.checks("date-time", raises=ValueError)
    def _date_time(value):
        if not isinstance(value, str):
            return True
        if not RFC3339_RE.match(value):
            raise ValueError(f"not an RFC 3339 date-time: {value!r}")
        parse_instant(value)
        return True

    return fc


def load(path):
    with path.open(encoding="utf-8") as fh:
        return json.load(fh)


def validation_errors(validator, instance):
    return sorted(validator.iter_errors(instance), key=lambda e: list(e.path))


def is_valid(validator, instance):
    return not validation_errors(validator, instance)


# ---- 1 + 2: schemas and event fixtures ----
def check_schemas_and_fixtures(rep):
    fc = make_format_checker()
    validators = {}
    for name in ("expense.parsed.v1", "user.created.v1"):
        rep.section(f"{name}: schema + fixture")
        schema = load(SCHEMAS / f"{name}.schema.json")
        try:
            Draft202012Validator.check_schema(schema)
            rep.check(True, f"{name} schema is valid 2020-12")
        except Exception as exc:  # jsonschema.SchemaError
            rep.check(False, f"{name} schema is valid 2020-12", str(exc))
        rep.check(schema.get("$schema") == "https://json-schema.org/draft/2020-12/schema",
                  f"{name} declares draft 2020-12")
        rep.check(schema.get("additionalProperties") is False, f"{name} additionalProperties is false")
        rep.check(set(schema.get("required", [])) == set(schema["properties"]),
                  f"{name} requires every declared property")
        validator = Draft202012Validator(schema, format_checker=fc)
        validators[name] = (schema, validator)

        fixture = load(FIXTURES / f"{name}.json")
        errs = validation_errors(validator, fixture)
        rep.check(not errs, f"{name}.json validates", "; ".join(f"{list(e.path)}: {e.message}" for e in errs))
        if not errs:
            print(f"OK    {name}.json validates against {name}.schema.json")

    expense_schema, expense_v = validators["expense.parsed.v1"]
    props = expense_schema["properties"]
    rep.check(props["category"]["enum"] == CATEGORIES, "expense category enum equals the plan's 15 values")
    rep.check(props["txn_type"]["enum"] == TXN_TYPES, "expense txn_type enum equals DEBIT|CREDIT|OTHER")
    rep.check(props["schema_version"].get("const") == 1, "expense schema_version const is 1")

    user_schema, user_v = validators["user.created.v1"]
    rep.check(user_schema["properties"]["schema_version"].get("const") == 1, "user schema_version const is 1")

    # Drift guards: each mutation of a good payload must be rejected.
    rep.section("schemas reject drift")
    before = rep.passed
    good = load(FIXTURES / "expense.parsed.v1.json")
    reject = {
        "extra field": {"extra": 1},
        "schema_version 2": {"schema_version": 2},
        "schema_version as string": {"schema_version": "1"},
        "user_id not a uuid": {"user_id": "not-a-uuid"},
        "sms_hash upper-case": {"sms_hash": "A" * 64},
        "sms_hash too short": {"sms_hash": "ab12"},
        "sms_received_at not a date-time": {"sms_received_at": "yesterday"},
        "sms_received_at date only": {"sms_received_at": "2026-09-12"},
        "is_expense as string": {"is_expense": "true"},
        "txn_type REFUND": {"txn_type": "REFUND"},
        "amount as number": {"amount": 436},
        "amount three decimals": {"amount": "12.345"},
        "amount negative": {"amount": "-5.00"},
        "amount with comma": {"amount": "1,234.00"},
        "currency lower-case": {"currency": "inr"},
        "currency too long": {"currency": "INRR"},
        "merchant number": {"merchant": 5},
        "category FUEL (not in enum)": {"category": "FUEL"},
        "account_last4 five digits": {"account_last4": "12345"},
        "txn_date impossible": {"txn_date": "2026-13-45"},
        "txn_date with time": {"txn_date": "2026-09-12T10:00:00Z"},
    }
    for label, patch in reject.items():
        rep.check(not is_valid(expense_v, {**good, **patch}), f"expense rejects: {label}")
    for key in good:
        missing = {k: v for k, v in good.items() if k != key}
        rep.check(not is_valid(expense_v, missing), f"expense rejects: missing {key}")
    accept = {
        "merchant null": {"merchant": None},
        "account_last4 null": {"account_last4": None},
        "txn_date null": {"txn_date": None},
        "offset date-time": {"sms_received_at": "2026-09-12T20:41:07.250+05:30"},
        "amount without decimals": {"amount": "2000"},
        "amount one decimal": {"amount": "850.0"},
    }
    for label, patch in accept.items():
        errs = validation_errors(expense_v, {**good, **patch})
        rep.check(not errs, f"expense accepts: {label}", "; ".join(e.message for e in errs))
    for cat in CATEGORIES:
        rep.check(is_valid(expense_v, {**good, "category": cat}), f"expense accepts category {cat}")

    good_u = load(FIXTURES / "user.created.v1.json")
    reject_u = {
        "extra field": {"extra": 1},
        "schema_version 2": {"schema_version": 2},
        "event_id not a uuid": {"event_id": "123"},
        "user_id not a uuid": {"user_id": "abc"},
        "username null": {"username": None},
        "username empty": {"username": ""},
        "phone_number as number": {"phone_number": 9876500012},
        "email as number": {"email": 5},
        "created_at not a date-time": {"created_at": "today"},
    }
    for label, patch in reject_u.items():
        rep.check(not is_valid(user_v, {**good_u, **patch}), f"user rejects: {label}")
    for key in good_u:
        missing = {k: v for k, v in good_u.items() if k != key}
        rep.check(not is_valid(user_v, missing), f"user rejects: missing {key}")
    nulls = {**good_u, "first_name": None, "last_name": None, "phone_number": None, "email": None}
    rep.check(is_valid(user_v, nulls), "user accepts null first_name/last_name/phone_number/email")
    print(f"OK    {rep.passed - before} drift/acceptance probes behave as the schemas require")

    return expense_v, good


# ---- 3: sms_samples ----
def check_sms_samples(rep, expense_v, expense_fixture):
    rep.section("sms_samples.json")
    samples = load(FIXTURES / "sms_samples.json")
    rep.check(isinstance(samples, list) and len(samples) >= 14, "at least 14 samples", f"got {len(samples)}")
    ids = [s.get("id") for s in samples]
    rep.check(len(ids) == len(set(ids)), "sample ids are unique")
    by_id = {s["id"]: s for s in samples}

    sample_keys = {"id", "sender", "body", "received_at", "expected"}
    optional_keys = {"duplicate_of", "note"}
    expected_keys = ["is_expense", "txn_type", "amount", "currency", "merchant", "category",
                     "account_last4", "txn_date", "sms_hash"]

    hashes = {}
    publishable = 0
    for s in samples:
        sid = s.get("id", "<no id>")
        keys = set(s)
        rep.check(sample_keys <= keys and keys <= sample_keys | optional_keys, f"[{sid}] key set",
                  f"unexpected/missing: {sorted(keys ^ (sample_keys | (keys & optional_keys)))}")
        for k in ("sender", "body", "received_at"):
            rep.check(isinstance(s.get(k), str) and s[k].strip() != "", f"[{sid}] {k} is a non-empty string")
        rep.check(bool(RECEIVED_AT_RE.match(s["received_at"])), f"[{sid}] received_at is ISO-8601 with +05:30",
                  s["received_at"])
        e = s["expected"]
        rep.check(list(e) == expected_keys, f"[{sid}] expected has exactly the pinned keys", str(list(e)))

        # enums / patterns
        rep.check(isinstance(e["is_expense"], bool), f"[{sid}] is_expense is boolean")
        rep.check(e["txn_type"] in TXN_TYPES, f"[{sid}] txn_type in enum", str(e["txn_type"]))
        rep.check(e["category"] in CATEGORIES, f"[{sid}] category in enum", str(e["category"]))
        rep.check(isinstance(e["currency"], str) and bool(CURRENCY_RE.match(e["currency"])),
                  f"[{sid}] currency matches ^[A-Z]{{3}}$", str(e["currency"]))
        rep.check(e["amount"] is None or (isinstance(e["amount"], str) and bool(AMOUNT_RE.match(e["amount"]))),
                  f"[{sid}] amount is null or a decimal string", repr(e["amount"]))
        rep.check(e["merchant"] is None or (isinstance(e["merchant"], str) and e["merchant"].strip() != ""),
                  f"[{sid}] merchant is null or a non-empty string")
        rep.check(e["account_last4"] is None or (isinstance(e["account_last4"], str)
                                                 and bool(LAST4_RE.match(e["account_last4"]))),
                  f"[{sid}] account_last4 is null or 4 digits", repr(e["account_last4"]))
        txn_date_ok = e["txn_date"] is None
        if isinstance(e["txn_date"], str):
            try:
                txn_date_ok = date.fromisoformat(e["txn_date"]).isoformat() == e["txn_date"]
            except ValueError:
                txn_date_ok = False
        rep.check(txn_date_ok, f"[{sid}] txn_date is null or a valid yyyy-mm-dd", repr(e["txn_date"]))
        rep.check(bool(HASH_RE.match(str(e["sms_hash"]))), f"[{sid}] sms_hash matches ^[0-9a-f]{{64}}$")

        # consistency rules
        if e["is_expense"]:
            rep.check(e["txn_type"] == "DEBIT", f"[{sid}] expenses are DEBIT")
            rep.check(e["amount"] is not None and Decimal(e["amount"]) > 0, f"[{sid}] expenses have amount > 0")
        if e["txn_type"] == "CREDIT":
            rep.check(not e["is_expense"], f"[{sid}] CREDIT is not an expense")
        if e["txn_type"] == "OTHER":
            rep.check(not e["is_expense"], f"[{sid}] txn_type OTHER is not an expense")
        if e["amount"] is None:
            rep.check(not e["is_expense"], f"[{sid}] null amount implies not an expense")

        # hallucination guard: the expected amount digits must appear in the SMS text
        if e["amount"] is not None:
            body_plain = re.sub(r"(?<=\d),(?=\d)", "", s["body"])
            rep.check(e["amount"] in body_plain, f"[{sid}] amount digits appear in the SMS body", e["amount"])

        # txn_date is on or shortly before the day the SMS was received (IST wall clock)
        if e["txn_date"] is not None:
            received_day = parse_instant(s["received_at"]).date()
            delta = (received_day - date.fromisoformat(e["txn_date"])).days
            rep.check(0 <= delta <= 3, f"[{sid}] txn_date within 3 days before received_at", f"delta={delta}d")

        # sms_hash recomputes exactly
        recomputed = sms_hash(s["sender"], s["received_at"], s["body"])
        rep.check(recomputed == e["sms_hash"], f"[{sid}] sms_hash recomputes",
                  f"expected {e['sms_hash']} got {recomputed}")
        hashes[sid] = recomputed

        # publishable samples must form a valid expense.parsed.v1 event
        if e["is_expense"] and e["amount"] is not None and Decimal(e["amount"]) > 0:
            publishable += 1
            event = {
                "schema_version": 1,
                "user_id": SAMPLE_USER_ID,
                "sms_hash": e["sms_hash"],
                "sender": s["sender"],
                "sms_received_at": parse_instant(s["received_at"]).astimezone(timezone.utc)
                                   .strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z",
                "is_expense": e["is_expense"],
                "txn_type": e["txn_type"],
                "amount": e["amount"],
                "currency": e["currency"],
                "merchant": e["merchant"],
                "category": e["category"],
                "account_last4": e["account_last4"],
                "txn_date": e["txn_date"],
            }
            errs = validation_errors(expense_v, event)
            rep.check(not errs, f"[{sid}] forms a valid expense.parsed.v1 event",
                      "; ".join(f"{list(x.path)}: {x.message}" for x in errs))

    # duplicates
    duplicates = [s for s in samples if "duplicate_of" in s]
    rep.check(len(duplicates) >= 1, "at least one duplicate entry")
    exact = 0
    for d in duplicates:
        did = d["id"]
        orig = by_id.get(d["duplicate_of"])
        if not rep.check(orig is not None and orig["id"] != did, f"[{did}] duplicate_of references another sample",
                         str(d["duplicate_of"])):
            continue
        rep.check(orig.get("duplicate_of") is None, f"[{did}] duplicate_of points at an original, not a duplicate")
        rep.check(hashes[did] == hashes[orig["id"]], f"[{did}] hashes identically to {orig['id']}")
        rep.check(d["expected"] == orig["expected"], f"[{did}] expected output identical to {orig['id']}")
        if (d["sender"], d["body"], d["received_at"]) == (orig["sender"], orig["body"], orig["received_at"]):
            exact += 1
    rep.check(exact >= 1, "at least one exact duplicate (same sender, body, received_at)")
    rep.check(len(duplicates) > exact, "at least one whitespace/case-variant duplicate that still hashes identically")

    # distinct messages must not collide
    originals = {sid: h for sid, h in hashes.items() if "duplicate_of" not in by_id[sid]}
    rep.check(len(set(originals.values())) == len(originals), "all non-duplicate samples have distinct sms_hash")

    # coverage: the scenarios the plan asks for
    cats = {s["expected"]["category"] for s in samples if s["expected"]["is_expense"]}
    rep.check(cats >= set(CATEGORIES) - {"OTHER"} and "OTHER" in cats, "every category appears on an expense sample",
              f"missing {sorted(set(CATEGORIES) - cats)}")
    rep.check(any(s["expected"]["txn_type"] == "CREDIT" for s in samples), "has a CREDIT sample")
    rep.check(sum(1 for s in samples if not s["expected"]["is_expense"] and s["expected"]["txn_type"] == "OTHER") >= 3,
              "has OTP, promo and balance-inquiry style non-expense samples (>= 3)")
    rep.check(any(s["expected"]["amount"] == "123456.50" for s in samples), "has a Rs 1,23,456.50 comma-amount sample")
    rep.check(any(s["expected"]["amount"] and re.search(r"\d,\d", s["body"]) for s in samples),
              "has a body whose amount contains commas")

    # the event fixture must correspond to a sample
    match = [s for s in samples if s["expected"]["sms_hash"] == expense_fixture["sms_hash"]]
    if rep.check(len(match) >= 1, "expense.parsed.v1.json sms_hash exists in sms_samples.json"):
        s, e = match[0], match[0]["expected"]
        same = all(expense_fixture[k] == e[k] for k in
                   ("is_expense", "txn_type", "amount", "currency", "merchant", "category", "account_last4",
                    "txn_date")) and expense_fixture["sender"] == s["sender"]
        rep.check(same, "expense.parsed.v1.json is consistent with its sms_samples entry")
        rep.check(epoch_ms(expense_fixture["sms_received_at"]) == epoch_ms(s["received_at"]),
                  "expense.parsed.v1.json sms_received_at is the same instant as the sample's received_at")

    print(f"OK    {len(samples)} samples checked ({publishable} publishable, {len(duplicates)} duplicates)")


def main():
    rep = Report()
    expense_v, expense_fixture = check_schemas_and_fixtures(rep)
    check_sms_samples(rep, expense_v, expense_fixture)
    print(f"\n{rep.passed} checks passed, {rep.failed} failed")
    if rep.failed:
        print("RESULT: FAIL")
        return 1
    print("RESULT: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
