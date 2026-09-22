#!/usr/bin/env bash
# Run the packaged jar locally, loading configuration from .env.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
  echo "==> loaded .env"
else
  echo "==> no .env found; using application.yml defaults (DB_PASSWORD will be empty!)"
fi

JAVA_HOME="${JAVA_HOME:-C:/Users/Administrator/.jdks/ms-17.0.20.1}"
JAR="${JAR:-ai-agent-app/target/ai-agent-java-1.2.0.jar}"

if [ ! -f "$JAR" ]; then
  echo "ERROR: $JAR not found — run scripts/build.sh first" >&2
  exit 1
fi

exec "$JAVA_HOME/bin/java" -jar "$JAR" "$@"
