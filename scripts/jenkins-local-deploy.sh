#!/usr/bin/env bash
set -Eeuo pipefail

DEPLOY_ENV_FILE="${DEPLOY_ENV_FILE:-/run/voyager-deploy/.env}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-voyager}"
APP_IMAGE="${APP_IMAGE:-ghcr.io/harshithrao07/voyager-app}:${RELEASE_TAG:?RELEASE_TAG is required}"
FRONTEND_IMAGE="${FRONTEND_IMAGE:-ghcr.io/harshithrao07/voyager-frontend}:${RELEASE_TAG:?RELEASE_TAG is required}"

if [[ ! -f "$DEPLOY_ENV_FILE" ]]; then
  echo "Deployment env file not found: $DEPLOY_ENV_FILE" >&2
  exit 1
fi

docker info >/dev/null

compose=(
  docker compose
  --project-name "$COMPOSE_PROJECT_NAME"
  --env-file "$DEPLOY_ENV_FILE"
  -f docker-compose.yml
  -f compose.deploy.yml
)

container_id() {
  "${compose[@]}" ps -q "$1"
}

current_image() {
  local id
  id="$(container_id "$1")"
  if [[ -n "$id" ]]; then
    docker inspect --format '{{.Config.Image}}' "$id"
  fi
}

wait_for_health() {
  local service="$1"
  local id
  local status

  for _ in $(seq 1 90); do
    id="$(container_id "$service")"
    if [[ -n "$id" ]]; then
      status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id")"
      if [[ "$status" == "healthy" ]]; then
        return 0
      fi
      if [[ "$status" == "unhealthy" || "$status" == "exited" || "$status" == "dead" ]]; then
        break
      fi
    fi
    sleep 2
  done

  echo "Service $service did not become healthy." >&2
  "${compose[@]}" logs --tail 120 "$service" >&2 || true
  return 1
}

previous_app_image="$(current_image app)"
previous_frontend_image="$(current_image frontend)"
deployment_started=false

rollback() {
  local exit_code="$?"
  trap - ERR

  if [[ "$deployment_started" == "true" \
      && -n "$previous_app_image" \
      && -n "$previous_frontend_image" ]]; then
    echo "Deployment failed; restoring previous app and frontend images." >&2
    export VOYAGER_APP_IMAGE="$previous_app_image"
    export VOYAGER_FRONTEND_IMAGE="$previous_frontend_image"
    "${compose[@]}" up -d --no-deps --no-build --pull never app
    wait_for_health app
    "${compose[@]}" up -d --no-deps --no-build --pull never frontend
    wait_for_health frontend
  else
    echo "Deployment failed before a rollback target was available." >&2
  fi

  exit "$exit_code"
}
trap rollback ERR

export VOYAGER_APP_IMAGE="$APP_IMAGE"
export VOYAGER_FRONTEND_IMAGE="$FRONTEND_IMAGE"

echo "Pulling Voyager images tagged $RELEASE_TAG."
"${compose[@]}" pull app frontend

deployment_started=true
"${compose[@]}" up -d --no-deps --no-build --pull never app
wait_for_health app

"${compose[@]}" up -d --no-deps --no-build --pull never frontend
wait_for_health frontend

trap - ERR
echo "Voyager $RELEASE_TAG is healthy at http://localhost:3000."
