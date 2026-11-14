#!/usr/bin/env bash
set -Eeuo pipefail

# This test owns disposable paths on a GitHub-hosted Linux runner, never EC2.
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]]
[[ ! -e /etc/azas && ! -e /opt/azas ]]
[[ ! -e /usr/local/sbin/azas-deploy ]]
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d /tmp/azas-rollback-test.XXXXXX)"
real_docker="$(command -v docker)"
good_image="example.invalid/azas-api:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
bad_image="example.invalid/azas-api:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
compose=(docker compose --project-name azas --env-file /etc/azas/compose.env --file /opt/azas/compose.yaml)

cleanup() {
  sudo "${compose[@]}" logs --tail 30 api nginx 2>/dev/null || true
  sudo "${compose[@]}" --profile tools down --volumes --remove-orphans 2>/dev/null || true
  docker rm --force azas-api-candidate 2>/dev/null || true
  sudo rm -rf -- /etc/azas /opt/azas
  sudo rm -f -- /usr/local/sbin/azas-deploy
  [[ "$test_dir" == /tmp/azas-rollback-test.* ]] && rm -rf -- "$test_dir"
}
trap cleanup EXIT

mkdir -p "$test_dir/bin"
# Stub only AWS/ECR transport. Container orchestration and healthchecks are real.
cat > "$test_dir/bin/aws" <<'SH'
#!/bin/sh
test "$1 $2" = 'ecr get-login-password' || exit 1
printf 'ci-placeholder\n'
SH
cat > "$test_dir/bin/docker" <<'SH'
#!/bin/sh
case "$1" in
  login) cat > /dev/null ;;
  pull) exec "$REAL_DOCKER" image inspect "$2" > /dev/null ;;
  *) exec "$REAL_DOCKER" "$@" ;;
esac
SH
chmod +x "$test_dir/bin/aws" "$test_dir/bin/docker"
docker tag azas-api:ci "$good_image"

# This image passes standalone preflight but fails in the Compose environment.
cat > "$test_dir/Dockerfile" <<'DOCKERFILE'
FROM azas-api:ci
ENTRYPOINT ["/bin/sh", "-c", "if [ \"$SERVER_FORWARD_HEADERS_STRATEGY\" = framework ]; then echo 'intentional CI failure' >&2; exit 1; fi; exec java -jar /app/azas-api.jar"]
DOCKERFILE

sudo install -d -m 0755 /etc/azas /opt/azas/nginx
sudo install -m 0644 "$repo_root/deploy/compose.yaml" /opt/azas/compose.yaml
sudo install -m 0644 "$repo_root"/deploy/nginx/*.conf /opt/azas/nginx/
sudo install -m 0750 "$repo_root/scripts/deploy.sh" /usr/local/sbin/azas-deploy
sudo install -m 0750 "$repo_root/scripts/deploy-compose.sh" /opt/azas/deploy-compose.sh
printf 'ECR_REPOSITORY_URI=example.invalid/azas-api\nAWS_REGION=ap-northeast-2\n' | \
  sudo tee /etc/azas/registry.env > /dev/null
printf 'AZAS_ENVIRONMENT=test\n' | sudo tee /etc/azas/app.env > /dev/null
printf 'AZAS_API_IMAGE=%s\nAZAS_APP_ENV_FILE=/etc/azas/app.env\nAZAS_NGINX_CONFIG=./nginx/azas-http.conf\n' \
  "$good_image" | sudo tee /etc/azas/compose.env > /dev/null
sudo chmod 0600 /etc/azas/*.env

run_deploy() {
  sudo env "PATH=$test_dir/bin:$PATH" "REAL_DOCKER=$real_docker" \
    /usr/local/sbin/azas-deploy "$1"
}
run_deploy "$good_image"

# Installing the first-Spring deployment tools must leave the current API running.
api_before="$(sudo "${compose[@]}" ps --quiet api)"
sudo bash "$repo_root/scripts/install-deploy-tools.sh"
[[ "$(sudo "${compose[@]}" ps --quiet api)" == "$api_before" ]]
[[ "$(sudo find /opt/azas -maxdepth 1 -type d -name 'before-spring.*' | wc -l)" -eq 1 ]]
docker build --tag "$bad_image" "$test_dir"

# Capture the real helper's failure without disabling errexit inside the helper.
status=0
run_deploy "$bad_image" > "$test_dir/deploy-failure.log" 2>&1 || status=$?
cat "$test_dir/deploy-failure.log"
[[ "$status" -ne 0 ]]
grep -Fq 'intentional CI failure' "$test_dir/deploy-failure.log"
grep -Fq "Restoring $good_image" "$test_dir/deploy-failure.log"
api_id="$(sudo "${compose[@]}" ps --quiet api)"
[[ "$(docker inspect --format '{{.Config.Image}}' "$api_id")" == "$good_image" ]]
sudo grep -Fxq "AZAS_API_IMAGE=$good_image" /etc/azas/compose.env
health="$(curl --noproxy '*' --fail --silent --show-error --max-time 5 \
  --resolve api.azas.n-e.kr:80:127.0.0.1 http://api.azas.n-e.kr/health)"
[[ "$health" == '{"status":"ok"}' ]]
printf 'Failed Compose deployment restored the healthy image and persisted tag\n'
