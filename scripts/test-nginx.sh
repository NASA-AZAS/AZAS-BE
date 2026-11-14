#!/usr/bin/env bash
set -Eeuo pipefail

# Isolated Compose test: no public DNS or ACME requests are made.
test_dir="$(mktemp -d /tmp/azas-nginx-test.XXXXXX)"
project="azas-nginx-test-${GITHUB_RUN_ID:-$$}"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
export AZAS_API_IMAGE="azas-api:ci"
export AZAS_APP_ENV_FILE="$test_dir/app.env"
export AZAS_NGINX_CONFIG="./nginx/azas-https.conf"
printf 'AZAS_ENVIRONMENT=test\nSPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/azas?sslmode=disable\nSPRING_DATASOURCE_USERNAME=azas_test\nSPRING_DATASOURCE_PASSWORD=test_only_password\n' > "$AZAS_APP_ENV_FILE"
compose=(docker compose --project-name "$project" --file "$repo_root/deploy/compose.yaml")

cleanup() {
  "${compose[@]}" logs --tail 20 api nginx 2>/dev/null || true
  [[ -z "${db_container:-}" ]] || docker rm --force "$db_container" >/dev/null 2>&1 || true
  "${compose[@]}" --profile tools down --volumes --remove-orphans 2>/dev/null || true
  [[ "$test_dir" == /tmp/azas-nginx-test.* ]] && rm -rf -- "$test_dir"
}
trap cleanup EXIT

"${compose[@]}" config --quiet
source "$repo_root/scripts/test-postgres.sh"
nginx_image="$("${compose[@]}" config --format json | jq --exit-status --raw-output '.services.nginx.image')"
mkdir -p "$test_dir/certs"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -keyout "$test_dir/certs/privkey.pem" -out "$test_dir/certs/fullchain.pem" \
  -subj /CN=api.azas.n-e.kr -addext subjectAltName=DNS:api.azas.n-e.kr \
  >/dev/null 2>&1

"${compose[@]}" run --rm --no-deps certbot --version
docker run --rm --entrypoint sh \
  --volume "${project}_letsencrypt:/certs" --volume "${project}_acme-webroot:/webroot" \
  --mount "type=bind,source=$test_dir/certs,target=/fixtures,readonly" \
  "$nginx_image" -c '
    mkdir -p /certs/live/api.azas.n-e.kr /webroot/.well-known/acme-challenge
    cp /fixtures/fullchain.pem /fixtures/privkey.pem /certs/live/api.azas.n-e.kr/
    printf challenge-ok > /webroot/.well-known/acme-challenge/ci-token
  '
"${compose[@]}" up --detach --wait --wait-timeout 180 api nginx
"${compose[@]}" exec -T nginx nginx -t

check_https() {
  local health
  health="$(curl --noproxy '*' --fail --silent --show-error \
    --retry 15 --retry-delay 1 --retry-all-errors --max-time 3 \
    --cacert "$test_dir/certs/fullchain.pem" \
    --resolve api.azas.n-e.kr:443:127.0.0.1 https://api.azas.n-e.kr/health)"
  [[ "$health" == '{"status":"ok"}' ]]
}
check_https
api_id="$("${compose[@]}" ps --quiet api)"
docker inspect --format '{{json .HostConfig.PortBindings}}' "$api_id" | \
  jq --exit-status '. == {} or . == null' >/dev/null
[[ "$("${compose[@]}" ps --all --quiet certbot)" == '' ]]

challenge="$(curl --noproxy '*' --fail --silent --show-error --max-time 3 \
  --resolve api.azas.n-e.kr:80:127.0.0.1 \
  http://api.azas.n-e.kr/.well-known/acme-challenge/ci-token)"
[[ "$challenge" == challenge-ok ]]
curl --noproxy '*' --silent --show-error --max-time 3 \
  --resolve api.azas.n-e.kr:80:127.0.0.1 \
  --dump-header "$test_dir/http.headers" --output /dev/null http://api.azas.n-e.kr/health
grep -Eq '^HTTP/[0-9.]+ 308' "$test_dir/http.headers"
grep -Eiq '^location: https://api[.]azas[.]n-e[.]kr/health' "$test_dir/http.headers"

curl --noproxy '*' --silent --show-error --max-time 3 \
  --cacert "$test_dir/certs/fullchain.pem" \
  --resolve api.azas.n-e.kr:443:127.0.0.1 \
  --header 'X-Forwarded-Proto: http' \
  --dump-header "$test_dir/https.headers" --output /dev/null https://api.azas.n-e.kr/health/
grep -Eq '^HTTP/[0-9.]+ 307' "$test_dir/https.headers"
grep -Eiq '^location: https://api[.]azas[.]n-e[.]kr/health' "$test_dir/https.headers"
curl --noproxy '*' --fail --silent --show-error --max-time 3 \
  --cacert "$test_dir/certs/fullchain.pem" \
  --resolve api.azas.n-e.kr:443:127.0.0.1 --location https://api.azas.n-e.kr/docs >/dev/null

curl --noproxy '*' --fail --silent --show-error --max-time 10 \
  --cacert "$test_dir/certs/fullchain.pem" \
  --resolve api.azas.n-e.kr:443:127.0.0.1 \
  --header 'X-Forwarded-Proto: http' \
  https://api.azas.n-e.kr/openapi.json | \
  jq --exit-status '.paths["/health"] != null and .servers[0].url == "https://api.azas.n-e.kr"' >/dev/null

"${compose[@]}" up --detach --wait --wait-timeout 180 --force-recreate api
check_https
docker rm --force "$db_container" >/dev/null
db_container=""
"${compose[@]}" down
source "$repo_root/scripts/test-postgres.sh"
"${compose[@]}" up --detach --wait --wait-timeout 180 api nginx
check_https
printf 'Compose TLS, redirects, ACME, headers, API recreation and certificate persistence passed\n'
