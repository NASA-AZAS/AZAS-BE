#!/usr/bin/env bash
# Source from an isolated Docker test after Compose has created its backend network.
source "$repo_root/deploy/postgres.env"
db_container="${project:-azas}-postgres-test"
backend_network="$("${compose[@]}" config --format json | jq -er '.networks.backend.name')"
"${compose[@]}" create api
docker run --detach --name "$db_container" --network "$backend_network" --network-alias postgres \
  --env POSTGRES_DB=azas --env POSTGRES_USER=azas_test --env POSTGRES_PASSWORD=test_only_password \
  --health-cmd 'pg_isready -U azas_test -d azas' --health-interval 1s --health-retries 30 \
  "$POSTGRES_IMAGE" >/dev/null
db_ready=false
for ((attempt = 0; attempt < 60; attempt++)); do
  if [[ "$(docker inspect --format '{{.State.Health.Status}}' "$db_container")" == healthy ]]; then
    db_ready=true
    break
  fi
  sleep 1
done
[[ "$db_ready" == true ]]
