#!/usr/bin/env bash
set -Eeuo pipefail

# Installed as a root-owned helper; invoked by the restricted SSM document.
source /etc/azas/registry.env
image_uri="${1:?Expected a SHA-tagged ECR image URI}"
tag="${image_uri##*:}"
if [[ "${image_uri%:*}" != "$ECR_REPOSITORY_URI" || ! "$tag" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Refusing an image outside the configured repository or without a commit SHA tag" >&2
  exit 1
fi

if [[ -f /etc/azas/compose.env ]]; then
  exec /opt/azas/deploy-compose.sh "$image_uri"
fi

exec 9>/var/lock/azas-deploy.lock
flock -w 300 9

auth_dir="$(mktemp -d /var/tmp/azas-ecr.XXXXXX)"
export DOCKER_CONFIG="$auth_dir"
candidate="azas-api-candidate"
service="azas-api"
backup="azas-api-previous"
old_renamed=false
replacement_started=false

cleanup() {
  rm -f -- "$auth_dir/config.json"
  rmdir -- "$auth_dir" || true
}
trap cleanup EXIT

on_failure() {
  local status=$?
  trap - ERR
  echo "Deployment failed; restoring the previous container when available" >&2
  docker logs --tail 40 "$candidate" 2>/dev/null || true
  docker rm --force "$candidate" 2>/dev/null || true
  if [[ "$replacement_started" == true ]]; then
    docker logs --tail 40 "$service" 2>/dev/null || true
    docker rm --force "$service" 2>/dev/null || true
  fi
  if [[ "$old_renamed" == true ]]; then
    docker rename "$backup" "$service"
    docker start "$service"
  fi
  exit "$status"
}
trap on_failure ERR

wait_for_health() {
  local name="$1"
  local health
  for ((attempt = 0; attempt < 150; attempt++)); do
    if [[ "$(docker inspect --format '{{.State.Running}}' "$name")" != true ]]; then
      return 1
    fi
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$name")"
    if [[ "$health" == healthy ]]; then
      return 0
    fi
    [[ "$health" != unhealthy && "$health" != missing ]] || return 1
    sleep 1
  done
  return 1
}

run_container() {
  local name="$1"
  shift
  docker run --detach --name "$name" \
    --label azas.managed=true \
    --env-file /etc/azas/app.env \
    --memory 384m --cpus 1 \
    --security-opt no-new-privileges --cap-drop ALL \
    --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \
    "$@" "$image_uri"
}

aws ecr get-login-password --region "$AWS_REGION" | \
  docker login --username AWS --password-stdin "${ECR_REPOSITORY_URI%%/*}"
docker pull "$image_uri"

# Test the new image before stopping the current service.
docker rm --force "$candidate" 2>/dev/null || true
run_container "$candidate" --restart no
wait_for_health "$candidate"
docker rm --force "$candidate"

previous_image=""
if docker container inspect "$service" >/dev/null 2>&1; then
  previous_image="$(docker inspect --format '{{.Config.Image}}' "$service")"
  docker rm --force "$backup" 2>/dev/null || true
  docker stop --time 20 "$service"
  docker rename "$service" "$backup"
  old_renamed=true
fi

replacement_started=true
run_container "$service" --restart unless-stopped --publish 80:8000
wait_for_health "$service"
curl --fail --silent --show-error --max-time 5 http://127.0.0.1/health
printf '\nDeployed %s\n' "$image_uri"

# Keep the current and previous image on this small EC2 volume.
while IFS= read -r old_image; do
  if [[ "$old_image" != "$image_uri" && "$old_image" != "$previous_image" ]]; then
    docker image rm "$old_image" || true
  fi
done < <(docker image ls "$ECR_REPOSITORY_URI" --format '{{.Repository}}:{{.Tag}}')
