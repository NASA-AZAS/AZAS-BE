# Compose 기반 api.azas.kro.kr HTTPS

DNS A 레코드는 Elastic IP `3.37.142.87`로 연결되어 있습니다. FastAPI·Nginx는
`deploy/compose.yaml`의 상시 서비스이며, Certbot은 `tools` profile에서 발급·갱신 때만 실행합니다.
호스트에는 Nginx·Certbot을 설치하지 않습니다. 기존 ECR·SSM·단일 EC2를 유지합니다.
ALB·ACM은 추가 로드밸런서 비용과 관리 범위 때문에 도입하지 않습니다.
운영 서버 변경과 인증서 발급은 PR 검토·병합 후 별도 적용합니다.

## 구성과 검증

- Nginx만 80/443번 포트를 공개합니다. API에는 published port가 없습니다.
- Nginx는 `api:8000`으로 프록시하며 Docker DNS로 재생성된 API의 IP를 다시 조회합니다.
- API와 Nginx만 backend 네트워크에 참여합니다. API는 이 경계 내의 프록시를 신뢰하고,
  Nginx는 클라이언트가 보낸 forwarding headers를 덮어씁니다.
- Certbot은 별도 acme 네트워크에서 실행하며 인증서·webroot 볼륨을 Nginx와 공유합니다.
- 인증서는 `azas_letsencrypt`, ACME 파일은 `azas_acme-webroot` 볼륨에 남습니다.
  운영에서 `docker compose down --volumes`를 실행하지 마세요.
- `/etc/azas/app.env`는 앱 설정, `/etc/azas/compose.env`는 이미지·Nginx 모드를 보관합니다.
- API 384MB, Nginx 96MB, 일회성 Certbot 192MB 메모리 제한을 사용합니다.

`scripts/test-nginx.sh`는 별도 Compose project와 테스트 인증서를 사용합니다.
TLS 검증을 끄지 않고 `--cacert`·`--resolve`로 HTTPS, HTTP 308, ACME 경로, 프록시 헤더,
API 재생성, 재기동 후 인증서 유지를 확인합니다. Certbot은 `--version`만 실행하므로
CI에서 공인 인증서나 ACME 계정을 만들지 않습니다. 실제 인증서 발급·갱신은 운영 적용 후 별도 확인합니다.

`scripts/test-compose-rollback.sh`는 GitHub-hosted Linux runner에서만 실행합니다.
AWS/ECR 전송만 로컬 fixture로 대체하고 실제 배포 도구·Docker·Compose를 실행합니다.
사전 검사 통과 후 Compose 환경에서 실패하는 이미지를 주입해 이전 서비스와 저장된 태그 복구를 검사합니다.

## 최초 적용 (PR 검토·병합 후)

아래는 EC2 관리자 절차입니다. **병합된 커밋**의 `deploy/`·`scripts/`를
`/home/ubuntu/azas-https`에 복사합니다. Docker Compose v2, 80/443 인바운드 허용,
A 레코드를 확인하고 잘못된 AAAA 레코드가 있으면 수정합니다.
기존 80번 포트를 넘기는 동안 짧은 중단이 있으므로 점검 시간에 진행합니다.

### 1. 파일 설치와 HTTP Compose 시작

```bash
cd /home/ubuntu/azas-https
current_image=$(sudo docker inspect --format '{{.Config.Image}}' azas-api)
sudo install -d -m 0755 /opt/azas/nginx
sudo install -m 0644 deploy/compose.yaml /opt/azas/compose.yaml
sudo install -m 0644 deploy/nginx/*.conf /opt/azas/nginx/
sudo install -m 0750 scripts/deploy-compose.sh /opt/azas/deploy-compose.sh
sudo install -m 0750 /usr/local/sbin/azas-deploy /usr/local/sbin/azas-deploy.before-https
sudo install -m 0750 scripts/deploy.sh /usr/local/sbin/azas-deploy
printf 'AZAS_API_IMAGE=%s\nAZAS_APP_ENV_FILE=/etc/azas/app.env\nAZAS_NGINX_CONFIG=./nginx/azas-http.conf\n' \
  "$current_image" | sudo tee /etc/azas/compose.env > /dev/null
sudo chmod 0600 /etc/azas/compose.env
sudo docker stop azas-api
sudo docker rename azas-api azas-api-legacy
sudo /usr/local/sbin/azas-deploy "$current_image"
```

ECR 인증은 기존 EC2 역할을 사용합니다. SSM 호출 방식은 그대로이며 `compose.env`가 있을 때만
Compose 배포 도구로 위임합니다. 이후 명령을 위해 EC2 쉘에 함수를 정의합니다.

```bash
dc() { sudo docker compose --project-name azas --env-file /etc/azas/compose.env --file /opt/azas/compose.yaml "$@"; }
dc ps
curl --fail http://api.azas.kro.kr/health
```

### 2. 인증서 발급과 HTTPS 활성화

관리자가 승인한 이메일과 인증기관 이용약관을 확인한 후 실행합니다.
`--agree-tos`는 해당 이용약관에 동의하는 옵션입니다.

```bash
CERTBOT_EMAIL='관리자가-승인한-이메일'
dc run --rm --no-deps certbot certonly --webroot -w /var/www/letsencrypt \
  --cert-name api.azas.kro.kr -d api.azas.kro.kr \
  --email "$CERTBOT_EMAIL" --agree-tos --non-interactive
sudo sed -i 's@^AZAS_NGINX_CONFIG=.*@AZAS_NGINX_CONFIG=./nginx/azas-https.conf@' /etc/azas/compose.env
dc up --detach --wait --wait-timeout 90 api nginx
dc exec -T nginx nginx -t
```

인증서 발급 실패 시 HTTP 설정을 유지합니다. 인증서 생성 전에 HTTPS 모드로 바꾸지 않습니다.
외부에서 HTTPS `/health`, `/docs`, HTTP→HTTPS 308을 확인합니다.

### 3. 정기 갱신

```bash
sudo install -m 0750 scripts/renew-certificate.sh /usr/local/sbin/azas-renew-certificate
sudo install -m 0644 deploy/systemd/azas-certbot-renew.* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now azas-certbot-renew.timer
dc run --rm --no-deps certbot renew --dry-run
systemctl list-timers azas-certbot-renew.timer
```

하루 두 번 최대 한 시간의 무작위 지연으로 갱신을 확인합니다. 배포와 같은 lock을 사용합니다.
Certbot 성공 후 Nginx 설정을 검사하고 reload합니다. 새 인증서가 없어도 reload는 안전합니다.
실패 로그는 `journalctl -u azas-certbot-renew.service`로 확인합니다.
HTTP-01 갱신을 위해 80번 포트와 ACME location을 유지합니다.

## 이후 배포와 복구

새 이미지는 별도 컨테이너에서 먼저 검사하고 Compose로 갱신합니다.
실패하면 이전 Compose 이미지로 복구합니다. 성공 태그는 `compose.env`, 직전 태그는
`/etc/azas/previous-image`에 기록합니다. 단일 EC2라 교체 중 짧은 중단이 있습니다.
첫 전환에는 이전 Compose 서비스가 없어 기존 단독 컨테이너를 자동 복구하지 않습니다.

이전 Compose 이미지는 `sudo /usr/local/sbin/azas-deploy IMAGE_URI`로 복구할 수 있습니다.
최초 Compose 전환 자체를 되돌리려면:

```bash
dc stop nginx api
sudo systemctl disable --now azas-certbot-renew.timer
sudo install -m 0750 /usr/local/sbin/azas-deploy.before-https /usr/local/sbin/azas-deploy
sudo mv /etc/azas/compose.env /etc/azas/compose.env.disabled
sudo docker rename azas-api-legacy azas-api
sudo docker start azas-api
```

앱 설정과 인증서 볼륨은 삭제하지 않습니다. Compose·Nginx·배포 도구를 바꾸는 후속 PR은
`/opt/azas`의 root 소유 파일도 갱신해야 합니다. 앱 이미지 배포만으로 설치 파일이 바뀌지는 않습니다.
