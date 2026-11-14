#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$EUID" -ne 0 ]]; then
  echo "Run with sudo" >&2
  exit 1
fi
repository_uri="${1:?ECR repository URI required}"
region="${2:?AWS region required}"
if [[ ! "$repository_uri" =~ ^[0-9]{12}[.]dkr[.]ecr[.]ap-northeast-2[.]amazonaws[.]com/azas-api$ || "$region" != ap-northeast-2 ]]; then
  echo "Unexpected ECR repository or region" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl unzip

if ! command -v docker >/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  source /etc/os-release
  cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-$VERSION_CODENAME}
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin
fi
systemctl enable --now docker

if ! command -v aws >/dev/null; then
  aws_tmp="$(mktemp -d /var/tmp/azas-aws-cli.XXXXXX)"
  curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o "$aws_tmp/aws.zip"
  unzip -q "$aws_tmp/aws.zip" -d "$aws_tmp"
  "$aws_tmp/aws/install"
  # This directory was created above exclusively for the AWS CLI installer.
  [[ "$aws_tmp" == /var/tmp/azas-aws-cli.* ]]
  rm -rf -- "$aws_tmp"
fi

install -d -m 0750 /etc/azas
printf 'ECR_REPOSITORY_URI=%s\nAWS_REGION=%s\n' "$repository_uri" "$region" > /etc/azas/registry.env
chmod 0600 /etc/azas/registry.env
if [[ ! -f /etc/azas/app.env ]]; then
  printf 'AZAS_ENVIRONMENT=production\n' > /etc/azas/app.env
  chmod 0600 /etc/azas/app.env
fi
install -m 0750 "$(dirname "$0")/deploy.sh" /usr/local/sbin/azas-deploy
systemctl restart snap.amazon-ssm-agent.amazon-ssm-agent.service
docker version --format '{{.Server.Version}}'
aws --version
