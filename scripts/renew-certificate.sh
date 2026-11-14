#!/usr/bin/env bash
set -Eeuo pipefail

exec 9>/var/lock/azas-deploy.lock
flock -w 300 9
compose=(docker compose --project-name azas --env-file /etc/azas/compose.env --file /opt/azas/compose.yaml)
"${compose[@]}" run --rm --no-deps certbot renew --quiet
"${compose[@]}" exec -T nginx nginx -t
"${compose[@]}" exec -T nginx nginx -s reload
