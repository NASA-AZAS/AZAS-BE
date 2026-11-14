# api.azas.kro.kr HTTPS 전환 계획

## 구성과 선택 이유

`api.azas.kro.kr`의 A 레코드는 EC2 Elastic IP `3.37.142.87`을 가리킵니다.
Nginx가 80/443번 포트를 담당하고, FastAPI 컨테이너는 호스트의
`127.0.0.1:8000`으로만 공개합니다. 인증서는 Certbot webroot 방식으로 발급·갱신합니다.

기존 단일 EC2와 ECR·SSM 배포를 유지하면서 Nginx와 Certbot을 추가합니다.
ALB·ACM을 사용하는 구성은 별도 로드밸런서 비용과 운영 범위가 생기므로 이번에는 도입하지 않습니다.
기존 기여 가이드에 따라 이 설계를 Draft PR에서 먼저 공유하고 구현·검증 결과를 갱신합니다.

PR 검토·병합 전에는 운영 EC2에 HTTPS 설정을 적용하지 않습니다.
