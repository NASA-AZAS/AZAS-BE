#!/usr/bin/env bash
set -Eeuo pipefail

source /etc/azas/registry.env
image_uri="${1:?Expected a SHA-tagged ECR image}"
if [[ "${image_uri%:*}" != "$ECR_REPOSITORY_URI" || ! "${image_uri##*:}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Unexpected repository or image tag" >&2
  exit 1
fi
exec 9>/var/lock/azas-deploy.lock
flock -w 300 9

active_image="$image_uri"
compose() {
  AZAS_API_IMAGE="$active_image" docker compose --project-name azas \
    --env-file /etc/azas/compose.env --file /opt/azas/compose.yaml "$@"
}
old_image=""
old_container="$(compose ps --all --quiet api)"
if [[ -n "$old_container" ]]; then
  old_image="$(docker inspect --format '{{.Config.Image}}' "$old_container")"
fi

auth_dir="$(mktemp -d /var/tmp/azas-ecr.XXXXXX)"
export DOCKER_CONFIG="$auth_dir"
candidate="azas-api-candidate"
deploy_started=false
env_tmp=""
env_backup="$(mktemp /etc/azas/compose.env.backup.XXXXXX)"
cp /etc/azas/compose.env "$env_backup"
cleanup() {
  docker rm --force "$candidate" 2>/dev/null || true
  rm -f -- "$auth_dir/config.json"
  rmdir -- "$auth_dir" || true
  [[ -z "$env_tmp" ]] || rm -f -- "$env_tmp"
  rm -f -- "$env_backup"
}
trap cleanup EXIT
rollback() {
  local status=$?
  trap - ERR
  compose logs --tail 40 api nginx || true
  if [[ "$deploy_started" == true && -n "$old_image" ]]; then
    echo "Restoring $old_image" >&2
    active_image="$old_image"
    install -m 0600 "$env_backup" /etc/azas/compose.env
    compose up --detach --wait --wait-timeout 180 api nginx
    compose exec -T nginx nginx -s reload
  fi
  exit "$status"
}
trap rollback ERR

aws ecr get-login-password --region "$AWS_REGION" | \
  docker login --username AWS --password-stdin "${ECR_REPOSITORY_URI%%/*}"
docker pull "$image_uri"
docker rm --force "$candidate" 2>/dev/null || true
docker run --detach --name "$candidate" --memory 384m --cpus 1 \
  --env-file /etc/azas/app.env --cap-drop ALL --security-opt no-new-privileges "$image_uri"
candidate_ready=false
for ((attempt = 0; attempt < 150; attempt++)); do
  if [[ "$(docker inspect --format '{{.State.Running}}' "$candidate")" != true ]]; then
    break
  fi
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$candidate")"
  if [[ "$health" == healthy ]]; then
    candidate_ready=true
    break
  fi
  [[ "$health" != unhealthy && "$health" != missing ]] || break
  sleep 1
done
[[ "$candidate_ready" == true ]]
docker rm --force "$candidate"

deploy_started=true
compose up --detach --wait --wait-timeout 180 api nginx
compose exec -T nginx nginx -t
compose exec -T nginx nginx -s reload
compose exec -T nginx wget -q -O- http://127.0.0.1:8081/health

# Persist the successful tag without changing the operator's other settings.
env_tmp="$(mktemp /etc/azas/compose.env.XXXXXX)"
awk -v image="$image_uri" '
  BEGIN { updated = 0 }
  /^AZAS_API_IMAGE=/ { print "AZAS_API_IMAGE=" image; updated = 1; next }
  { print }
  END { if (!updated) print "AZAS_API_IMAGE=" image }
' /etc/azas/compose.env > "$env_tmp"
chmod 0600 "$env_tmp"
mv -- "$env_tmp" /etc/azas/compose.env
env_tmp=""
if [[ -n "$old_image" && "$old_image" != "$image_uri" ]]; then
  printf '%s\n' "$old_image" > /etc/azas/previous-image
fi
printf '\nDeployed %s through Compose\n' "$image_uri"

previous_keep="$old_image"
if [[ "$previous_keep" == "$image_uri" && -f /etc/azas/previous-image ]]; then
  previous_keep="$(cat /etc/azas/previous-image)"
fi
while IFS= read -r old_tag; do
  if [[ "$old_tag" != "$image_uri" && "$old_tag" != "$previous_keep" ]]; then
    docker image rm "$old_tag" || true
  fi
done < <(docker image ls "$ECR_REPOSITORY_URI" --format '{{.Repository}}:{{.Tag}}')
