#!/usr/bin/env bash
# Startet eine lokale ArangoDB per Docker für die Entwicklung.
#
#   ./db.sh [start|stop|logs|rm]
#
# Passt zu den Defaults in application.properties (localhost:8529, root, leeres Passwort).
# Ist ARANGODB_PASSWORD gesetzt, wird es als Root-Passwort verwendet, sonst läuft die DB ohne Auth.
# Web-UI: http://localhost:8529
set -euo pipefail

CONTAINER="${ARANGODB_CONTAINER:-arrango-db}"
IMAGE="${ARANGODB_IMAGE:-arangodb:3.12}"
PORT="${ARANGODB_PORT:-8529}"
VOLUME="${CONTAINER}-data"

start() {
  if docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    echo "$CONTAINER läuft bereits."
  elif docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    docker start "$CONTAINER" >/dev/null
  else
    if [[ -n "${ARANGODB_PASSWORD:-}" ]]; then
      auth=(-e "ARANGO_ROOT_PASSWORD=$ARANGODB_PASSWORD")
    else
      auth=(-e ARANGO_NO_AUTH=1)
    fi
    docker run -d \
      --name "$CONTAINER" \
      -p "$PORT:8529" \
      -v "$VOLUME:/var/lib/arangodb3" \
      "${auth[@]}" \
      "$IMAGE" >/dev/null
  fi

  if [[ -z "$(docker port "$CONTAINER" 8529 2>/dev/null)" ]]; then
    echo "$CONTAINER läuft ohne Port-Mapping (Port $PORT belegt?)." >&2
    echo "Port freigeben, dann: docker rm -f $CONTAINER && $0 start" >&2
    exit 1
  fi

  echo -n "Warte auf ArangoDB"
  for _ in $(seq 1 60); do
    if curl -fs -o /dev/null "http://localhost:$PORT/_api/version" \
        ${ARANGODB_PASSWORD:+-u "root:$ARANGODB_PASSWORD"}; then
      echo " – bereit: http://localhost:$PORT"
      return
    fi
    echo -n "."
    sleep 1
  done
  echo
  echo "ArangoDB nicht erreichbar, siehe: ./db.sh logs" >&2
  exit 1
}

case "${1:-start}" in
  start) start ;;
  stop)  docker stop "$CONTAINER" ;;
  logs)  docker logs -f "$CONTAINER" ;;
  rm)    docker rm -f "$CONTAINER"; docker volume rm "$VOLUME" ;;
  *)     echo "Usage: $0 [start|stop|logs|rm]" >&2; exit 1 ;;
esac
