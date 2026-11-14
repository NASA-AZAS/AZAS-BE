# 앱 CI/CD와 EC2의 경계

이 레포는 Spring 앱, 실행 이미지, 로컬 PostgreSQL Compose와 GitHub Actions를 관리합니다.
운영 Compose·Nginx·인증서 갱신·배포 도구·AWS 인프라 템플릿은 EC2에서 관리합니다.

## 자동 배포 흐름

`main` 푸시 → Java/PostgreSQL 테스트 → Docker 빌드·검증 → ECR SHA 이미지 업로드 → OIDC/SSM → EC2 배포.
PR에서는 검사만 수행하고 `workflow_dispatch`의 main 실행으로 수동 재배포할 수 있습니다.
기존 workflow job 이름과 OIDC·ECR·제한된 SSM 호출 방식은 유지합니다.

SSM 문서 `azas-api-deploy`는 이미지 URI 하나를 받아 EC2의 `/usr/local/sbin/azas-deploy`를 실행합니다.
서버 도구가 Secret을 조회해 후보 환경 파일을 만들고 이미지의 Docker HEALTHCHECK로 후보 앱을 검사합니다.
검사 성공 후 운영 Compose를 전환하며 전환 실패 시 기존 이미지와 환경 파일을 복구합니다.
GitHub에 DB 비밀번호나 SSH 키를 추가하지 않습니다.

## GitHub Actions 변수

Repository Settings의 Actions Variables에서 관리합니다.

| 변수 | 역할 |
| --- | --- |
| `AWS_REGION` | 배포 리전 |
| `AWS_INSTANCE_ID` | 기존 앱 EC2 인스턴스 |
| `ECR_REPOSITORY_URI` | SHA 이미지를 올릴 기존 ECR 저장소 |
| `AWS_DEPLOY_ROLE_ARN` | main OIDC subject를 허용하는 기존 배포 역할 |
| `SSM_DOCUMENT_NAME` | 이미지 배포용 제한된 SSM 문서 |

앱 코드 변경 시 서버 설정 파일을 레포에서 복사하는 단계는 없습니다. CD는 검증한 이미지 URI를 전달합니다.

## 앱 이미지가 지켜야 하는 계약

- 포트 8000에서 HTTP를 제공하고 Nginx가 전달하는 프록시 헤더를 처리합니다.
- `/actuator/health/readiness`는 DB 연결이 정상이면 HTTP 200 UP을 반환합니다.
- Docker HEALTHCHECK, UID 10001과 메모리 384MiB 제한에서 실행 가능한 JVM 기본값을 유지합니다.
- `AZAS_`, `SPRING_DATASOURCE_` 환경변수를 받습니다. 운영 주입은 서버 배포 도구가 담당합니다.
- 공개 RDS CA를 `/app/certs/rds-ca-bundle.crt`에 포함합니다. Docker 빌드 원본은 `docker/certs/`입니다.
- Flyway SQL은 이전 앱과 호환되게 작성합니다. 이미지 롤백은 이미 적용한 SQL을 되돌리지 않습니다.

## 운영 파일 위치

| EC2 경로 | 역할 |
| --- | --- |
| `/opt/azas/compose.yaml`, `/opt/azas/nginx/` | 운영 Compose와 프록시 설정 |
| `/usr/local/sbin/azas-deploy` | SSM이 호출하는 배포 진입점 |
| `/opt/azas/deploy-compose.sh`, `/opt/azas/create-database-env.sh` | 이미지 전환·Secret 주입 |
| `/etc/azas/` | root 전용 운영 환경 파일 |
| `/usr/local/sbin/azas-renew-certificate`, `/etc/systemd/system/azas-certbot-renew.*` | 인증서 갱신 |
| `/opt/azas/management/README.md` | 서버 관리 절차와 기준 자료 위치 |

분리 이전 main `47efa722b9290b7a38b84071d9de75e14599c410`의 운영 원본·인프라 템플릿·절차는
EC2의 `/opt/azas/management/baseline-47efa72/`와 같은 폴더의 압축 파일에 보관했습니다.
이 자료는 분리 시점의 기록이며 현재 운영 파일이 기준입니다. 서버 설정은 EC2에서 백업·검증 후 변경합니다.

## 검증 범위

레포 CI는 Java/JPA/Flyway 통합 테스트, PowerShell 문법, 로컬 PostgreSQL Compose,
앱 이미지의 스키마·실행 권한·DB readiness와 DB 장애/복구를 검사합니다.
운영 설정을 제거하면서 HTTPS·Certbot·Secret 변환 스크립트·배포 롤백 스크립트의 독립 검사는
레포 CI에서 제외했습니다. 서버 설정 변경은 관리자가 별도 격리 환경에서 검증합니다.
보관한 테스트 스크립트를 운영 EC2에서 실행하면 서비스 포트·파일과 충돌할 수 있으므로 실행하지 않습니다.
CD의 실제 배포에는 기존 서버의 후보·전환 상태 검사와 실패 복구가 계속 적용됩니다.

일반 앱 변경은 로컬·CI 및 CD의 자동 검사로 확인합니다. 운영 환경변수나 인프라 변경,
배포 실패 시에는 필요한 서버 점검을 수행합니다. 로컬 DB 실행은 `compose.local.yaml`만 사용합니다.
