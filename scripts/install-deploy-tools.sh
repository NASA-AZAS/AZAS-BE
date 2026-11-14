#!/usr/bin/env bash
set -Eeuo pipefail

# Install from a reviewed checkout before the first Spring image reaches main.
[[ "$EUID" -eq 0 ]] || { echo 'Run with sudo' >&2; exit 1; }
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
[[ -f /etc/azas/compose.env && -f /etc/azas/registry.env ]]
for script in deploy.sh deploy-compose.sh; do
  bash -n "$repo_root/scripts/$script"
done
docker compose --project-name azas --env-file /etc/azas/compose.env \
  --file "$repo_root/deploy/compose.yaml" config --quiet

exec 9>/var/lock/azas-deploy.lock
flock -w 300 9
backup_dir="$(mktemp -d /opt/azas/before-spring.XXXXXX)"
cp -p /usr/local/sbin/azas-deploy "$backup_dir/azas-deploy"
cp -p /opt/azas/deploy-compose.sh "$backup_dir/deploy-compose.sh"
cp -p /opt/azas/compose.yaml "$backup_dir/compose.yaml"

# No container restart and no changes to app.env, compose.env or certificate volumes.
install -m 0750 "$repo_root/scripts/deploy.sh" /usr/local/sbin/azas-deploy
install -m 0750 "$repo_root/scripts/deploy-compose.sh" /opt/azas/deploy-compose.sh
install -m 0644 "$repo_root/deploy/compose.yaml" /opt/azas/compose.yaml
printf 'Installed runtime-neutral deployment tools. Backup: %s\n' "$backup_dir"
