#!/usr/bin/env bash
# Local dev helper — wraps the three most common debug operations.
#
# Usage:
#   ./dev.sh                  — start postgres, build JAR, run server
#   ./dev.sh --no-build       — start postgres, skip JAR build, run server
#   ./dev.sh categorize       — run the nightly categorizer once (needs venv)
#   ./dev.sh setup-categorizer — create categorizer/.venv and install deps

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Load main .env — parse manually so values with spaces (e.g. CATEGORIZER_CMD) are handled correctly.
if [[ -f .env ]]; then
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" =~ ^[[:space:]]*# ]] && continue
        [[ -z "${line//[[:space:]]/}" ]] && continue
        export "$line"
    done < .env
fi

# Map docker-compose vars → server env vars (DB_USER/DB_PASSWORD override if already set)
DB_URL="${DB_URL:-jdbc:postgresql://localhost:5433/${POSTGRES_DB:-photovault}}"
DB_USER="${DB_USER:-${POSTGRES_USER:-photovault}}"
DB_PASSWORD="${DB_PASSWORD:-${POSTGRES_PASSWORD:-change-me}}"
PHOTO_STORAGE_ROOT="${PHOTO_STORAGE_ROOT:-$SCRIPT_DIR/data/photos}"
VECTOR_STORE_DIR="${VECTOR_STORE_DIR:-$SCRIPT_DIR/data/vectors}"
JWT_SECRET="${JWT_SECRET:-dev-secret-change-me-in-production}"
SERVER_PORT="${SERVER_PORT:-8080}"

CMD="${1:-server}"

case "$CMD" in

  server | --no-build)
    BUILD=true
    [[ "$CMD" == "--no-build" ]] && BUILD=false

    echo "==> postgres"
    docker compose up -d postgres

    if [[ "$BUILD" == true ]]; then
        echo "==> gradlew shadowJar"
        ./gradlew shadowJar
    fi

    mkdir -p data/photos data/vectors

    echo "==> server :${SERVER_PORT}"
    DB_URL="$DB_URL" \
    DB_USER="$DB_USER" \
    DB_PASSWORD="$DB_PASSWORD" \
    PHOTO_STORAGE_ROOT="$PHOTO_STORAGE_ROOT" \
    JWT_SECRET="$JWT_SECRET" \
    SERVER_PORT="$SERVER_PORT" \
    CATEGORIZER_CMD=".venv/bin/python -m photovault_categorizer.cli.categorize" \
    CATEGORIZER_WORKDIR="./categorizer" \
    java -jar build/libs/photovault-server.jar
    ;;

  categorize)
    cd categorizer
    echo "==> categorize"
    DB_URL="$DB_URL" \
    DB_USER="$DB_USER" \
    DB_PASSWORD="$DB_PASSWORD" \
    PHOTO_STORAGE_ROOT="../data/photos" \
    VECTOR_STORE_DIR="../data/vectors" \
    .venv/bin/python -m photovault_categorizer.cli.categorize
    ;;

  setup-categorizer)
    echo "==> setup categorizer venv"
    cd categorizer
    python3 -m venv .venv
    .venv/bin/pip install -e .
    echo "Done. Run ./dev.sh categorize to execute a categorization pass."
    ;;

  *)
    echo "Usage: $0 [--no-build | categorize | setup-categorizer]"
    exit 1
    ;;

esac
