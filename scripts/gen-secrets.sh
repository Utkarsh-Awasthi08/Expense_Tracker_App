#!/usr/bin/env bash
# Generates local secrets:
#   .env                       random MySQL passwords (from .env.example)
#   secrets/jwt_private.pem    RSA-2048 private key (PKCS#8), used by authService to sign JWTs
#   secrets/jwt_public.pem     matching public key
#
# Existing files are never overwritten unless you pass --rotate.
#   ./scripts/gen-secrets.sh            create whatever is missing
#   ./scripts/gen-secrets.sh --rotate   replace passwords and the JWT keypair (keeps MISTRAL_API_KEY)
#
# After rotating DB passwords on a machine that already has a MySQL volume, the old passwords stay
# in the volume: run `docker compose down -v` (wipes local data) or ALTER USER by hand.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env"
SECRETS_DIR="$ROOT/secrets"
ROTATE=0
[[ "${1:-}" == "--rotate" ]] && ROTATE=1

rand() { openssl rand -base64 36 | tr -d '/+=\n' | cut -c1-32; }

mkdir -p "$SECRETS_DIR"
chmod 700 "$SECRETS_DIR"

if [[ ! -f "$SECRETS_DIR/jwt_private.pem" || $ROTATE -eq 1 ]]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$SECRETS_DIR/jwt_private.pem"
  openssl pkey -in "$SECRETS_DIR/jwt_private.pem" -pubout -out "$SECRETS_DIR/jwt_public.pem"
  # The auth container runs as a non-root user; the private key must be readable by it.
  chmod 644 "$SECRETS_DIR/jwt_private.pem" "$SECRETS_DIR/jwt_public.pem"
  echo "Generated JWT keypair in $SECRETS_DIR"
fi

if [[ ! -f "$ENV_FILE" || $ROTATE -eq 1 ]]; then
  KEEP_MISTRAL=""
  if [[ -f "$ENV_FILE" ]]; then
    KEEP_MISTRAL="$(grep -E '^MISTRAL_API_KEY=' "$ENV_FILE" | cut -d= -f2- || true)"
  fi
  cp "$ROOT/.env.example" "$ENV_FILE"
  for key in MYSQL_ROOT_PASSWORD AUTH_DB_PASSWORD USER_DB_PASSWORD EXPENSE_DB_PASSWORD; do
    sed -i.bak -E "s|^${key}=.*|${key}=$(rand)|" "$ENV_FILE"
  done
  if [[ -n "$KEEP_MISTRAL" ]]; then
    sed -i.bak -E "s|^MISTRAL_API_KEY=.*|MISTRAL_API_KEY=${KEEP_MISTRAL}|" "$ENV_FILE"
  fi
  rm -f "$ENV_FILE.bak"
  chmod 600 "$ENV_FILE"
  echo "Wrote $ENV_FILE with fresh random passwords."
  [[ -z "$KEEP_MISTRAL" ]] && echo "Set MISTRAL_API_KEY in .env, or use LLM_PROVIDER=fake."
fi
