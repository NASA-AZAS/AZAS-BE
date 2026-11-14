#!/usr/bin/env bash
set -Eeuo pipefail

# Install changed server deployment files from a reviewed checkout.
[[ "$EUID" -eq 0 ]] || { echo 'Run with sudo' >&2; exit 1; }
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
[[ -f /etc/azas/compose.env && -f /etc/azas/registry.env ]]
command -v jq >/dev/null || { echo 'Install jq before updating deployment tools' >&2; exit 1; }
for script in deploy.sh deploy-compose.sh create-database-env.sh; do
  bash -n "$repo_root/scripts/$script"
done
docker compose --project-name azas --env-file /etc/azas/compose.env \
  --file "$repo_root/deploy/compose.yaml" config --quiet

exec 9>/var/lock/azas-deploy.lock
flock -w 300 9
backup_dir="$(mktemp -d /opt/azas/deploy-tools-backup.XXXXXX)"
cp -p /usr/local/sbin/azas-deploy "$backup_dir/azas-deploy"
cp -p /opt/azas/deploy-compose.sh "$backup_dir/deploy-compose.sh"
cp -p /opt/azas/compose.yaml "$backup_dir/compose.yaml"
if [[ -f /opt/azas/create-database-env.sh ]]; then
  cp -p /opt/azas/create-database-env.sh "$backup_dir/create-database-env.sh"
fi

# No container restart and no changes to app.env, compose.env or certificate volumes.
install -m 0750 "$repo_root/scripts/deploy.sh" /usr/local/sbin/azas-deploy
install -m 0750 "$repo_root/scripts/deploy-compose.sh" /opt/azas/deploy-compose.sh
install -m 0750 "$repo_root/scripts/create-database-env.sh" /opt/azas/create-database-env.sh
install -m 0644 "$repo_root/deploy/compose.yaml" /opt/azas/compose.yaml
printf 'Installed runtime-neutral deployment tools. Backup: %s\n' "$backup_dir"
