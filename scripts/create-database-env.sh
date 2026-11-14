#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

base_env="${1:?Base environment file required}"
output_env="${2:?Candidate environment file required}"
version_file="${3:?Secret version output required}"
: "${DATABASE_SECRET_ARN:?Database secret ARN required}"
: "${AWS_REGION:?AWS region required}"
[[ "$DATABASE_SECRET_ARN" =~ ^arn:aws:secretsmanager:ap-northeast-2:[0-9]{12}:secret:/azas/production/database-[A-Za-z0-9]+$ ]]
[[ "$output_env" != "$base_env" && ! -L "$output_env" && ! -L "$version_file" ]]
ssl_mode="${DATABASE_SSL_MODE:-verify-full}"
case "$ssl_mode" in verify-full|disable) ;; *) echo 'Invalid database SSL mode' >&2; exit 1 ;; esac

secret_json="$(mktemp "$(dirname "$output_env")/azas-secret.XXXXXX")"
trap 'rm -f -- "$secret_json"' EXIT
aws secretsmanager get-secret-value --region "$AWS_REGION" --secret-id "$DATABASE_SECRET_ARN" \
  --output json > "$secret_json"

# Validate before writing anything; never print a secret or parser diagnostics.
if ! jq --exit-status '
  (.VersionId | type == "string" and test("^[A-Za-z0-9_-]{32,64}$")) and
  (.SecretString | fromjson |
    .engine == "postgres" and
    (.host | type == "string" and test("^[A-Za-z0-9.-]+$")) and
    (.port | tostring | test("^[0-9]{1,5}$")) and
    (.port | tonumber | . >= 1 and . <= 65535) and
    (.dbname | type == "string" and test("^[A-Za-z_][A-Za-z0-9_]*$")) and
    (.username | type == "string" and test("^[A-Za-z_][A-Za-z0-9_]*$")) and
    (.password | type == "string" and length > 0 and
      (explode | all(. != 0 and . != 10 and . != 13))))
' "$secret_json" >/dev/null 2>&1; then
  echo 'Invalid database secret payload; keeping the active environment unchanged' >&2
  exit 1
fi

grep -vE '^SPRING_DATASOURCE_(URL|USERNAME|PASSWORD)=' "$base_env" > "$output_env" || [[ "$?" == 1 ]]
# Compose raw env_file and docker --env-file interpret these values identically.
jq --raw-output --arg ssl "$ssl_mode" '
  .SecretString | fromjson |
  "SPRING_DATASOURCE_URL=jdbc:postgresql://\(.host):\(.port)/\(.dbname)?sslmode=\($ssl)" +
    (if $ssl == "verify-full" then "&sslrootcert=/app/certs/rds-ca-bundle.crt" else "" end),
  "SPRING_DATASOURCE_USERNAME=\(.username)",
  "SPRING_DATASOURCE_PASSWORD=\(.password)"
' "$secret_json" >> "$output_env"
jq --raw-output '.VersionId' "$secret_json" > "$version_file"
chmod 0600 "$output_env" "$version_file"
