#!/usr/bin/env bash
set -Eeuo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d /tmp/azas-env-test.XXXXXX)"
trap 'rm -rf -- "$test_dir"' EXIT
mkdir "$test_dir/bin"
cat > "$test_dir/bin/aws" <<'SH'
#!/bin/sh
test "$1 $2" = 'secretsmanager get-secret-value' || exit 1
test "${FAIL_SECRET_READ:-false}" != true || exit 1
cat "$SECRET_FIXTURE"
SH
chmod +x "$test_dir/bin/aws"
export PATH="$test_dir/bin:$PATH"
export DATABASE_SECRET_ARN=arn:aws:secretsmanager:ap-northeast-2:389930962915:secret:/azas/production/database-fixture
export AWS_REGION=ap-northeast-2
export SECRET_FIXTURE="$test_dir/secret.json"
password='fixture$with#quotes"and spaces'
jq -n --arg password "$password" '{
  VersionId:"00000000-0000-4000-8000-000000000001",
  SecretString:({engine:"postgres",host:"example.ap-northeast-2.rds.amazonaws.com",port:5432,
    dbname:"azas",username:"azas_app",password:$password}|tojson)
}' > "$SECRET_FIXTURE"
printf 'AZAS_ENVIRONMENT=production\nAZAS_CORS_ORIGINS=["https://frontend.example"]\nSPRING_DATASOURCE_PASSWORD=old\n' > "$test_dir/base.env"
cp "$test_dir/base.env" "$test_dir/base.backup"
bash "$repo_root/scripts/create-database-env.sh" "$test_dir/base.env" "$test_dir/new.env" "$test_dir/version" \
  > "$test_dir/log" 2>&1
cmp "$test_dir/base.env" "$test_dir/base.backup"
grep -Fxq "SPRING_DATASOURCE_PASSWORD=$password" "$test_dir/new.env"
grep -Fxq 'AZAS_CORS_ORIGINS=["https://frontend.example"]' "$test_dir/new.env"
grep -Fq 'sslmode=verify-full&sslrootcert=/app/certs/rds-ca-bundle.crt' "$test_dir/new.env"
[[ "$(stat -c %a "$test_dir/new.env")" == 600 ]]
[[ "$(cat "$test_dir/version")" == 00000000-0000-4000-8000-000000000001 ]]
[[ ! -s "$test_dir/log" ]]
cp "$test_dir/new.env" "$test_dir/new.backup"

# Missing credentials and multiline values must not overwrite an existing output.
for mode in missing multiline; do
  jq --arg mode "$mode" '.SecretString |= (fromjson |
      if $mode == "missing" then del(.password) else .password="private\nvalue" end | tojson)' \
    "$SECRET_FIXTURE" > "$test_dir/invalid.json"
  status=0
  SECRET_FIXTURE="$test_dir/invalid.json" bash "$repo_root/scripts/create-database-env.sh" \
    "$test_dir/base.env" "$test_dir/new.env" "$test_dir/version" > "$test_dir/log" 2>&1 || status=$?
  [[ "$status" -ne 0 ]]
  cmp "$test_dir/new.env" "$test_dir/new.backup"
done
status=0
FAIL_SECRET_READ=true bash "$repo_root/scripts/create-database-env.sh" \
  "$test_dir/base.env" "$test_dir/new.env" "$test_dir/version" > "$test_dir/log" 2>&1 || status=$?
[[ "$status" -ne 0 ]]
cmp "$test_dir/new.env" "$test_dir/new.backup"
! grep -Fq "$password" "$test_dir/log"
[[ -z "$(find "$test_dir" -maxdepth 1 -name 'azas-secret.*' -print -quit)" ]]
printf 'Secret validation, literal values, permissions, failure isolation and cleanup passed\n'
