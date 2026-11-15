# 앱 CI/CD와 EC2의 경계

이 레포는 Spring 앱, 실행 이미지, 로컬 PostgreSQL Compose와 GitHub Actions를 관리합니다.
운영 Compose·Nginx·인증서 갱신·배포 도구·AWS 인프라 템플릿은 EC2에서 관리합니다.

## 자동 배포 흐름

`main` 푸시 → 변경 분류·문서 검사 → 앱 변경이 있으면 Java/PostgreSQL 테스트 → Docker 빌드·검증 → ECR SHA 이미지 업로드 → OIDC/SSM → EC2 배포.
PR에서는 검사만 수행하고 `workflow_dispatch`의 main 실행으로 수동 재배포할 수 있습니다.
기존 workflow job 이름과 OIDC·ECR·제한된 SSM 호출 방식은 유지합니다.

개발은 최신 `dev`에서 작업 브랜치를 만들고 `dev` 대상 PR로 진행합니다.
모든 PR에 기존 CI가 적용되며, `dev` 푸시·병합은 배포 워크플로를 시작하지 않습니다.
운영 릴리스는 `dev → main` PR로 진행합니다. ECR 업로드·EC2 배포는 계속 `main`으로 제한합니다.

SSM 문서 `azas-api-deploy`는 이미지 URI 하나를 받아 EC2의 `/usr/local/sbin/azas-deploy`를 실행합니다.
서버 도구가 Secret을 조회해 후보 환경 파일을 만들고 이미지의 Docker HEALTHCHECK로 후보 앱을 검사합니다.
검사 성공 후 운영 Compose를 전환하며 전환 실패 시 기존 이미지와 환경 파일을 복구합니다.
GitHub에 DB 비밀번호나 SSH 키를 추가하지 않습니다.

### 문서 변경과 앱 변경 구분

워크플로는 모든 PR과 main 푸시에서 시작합니다. 먼저 `Documentation and CI policy` job이
문서의 내부 파일·제목 링크와 코드 블록, CI 분기 회귀 테스트를 검사하고 실행할 작업을 결정합니다.
외부 URL에 HTTP 요청을 보내는 검사는 포함하지 않습니다.

| 변경·실행 유형 | 앱 검사·Docker 빌드 | ECR 업로드·EC2 배포 |
| --- | --- | --- |
| `README.md`, `docs/` 아래 `.md` 파일만 변경한 PR | 생략 | 실행하지 않음 |
| 코드·설정·기타 파일이 포함된 PR | 실행 | 실행하지 않음 |
| 배포 성공 커밋 이후 문서만 변경된 main | 생략 | 생략 |
| 배포되지 않은 앱 변경이 있는 main | 실행 | 검사 성공 후 실행 |
| main에서 수동 실행 | 변경 파일과 관계없이 전체 실행 | 검사 성공 후 실행 |
| main 이외 브랜치에서 수동 실행 | 변경 파일과 관계없이 전체 실행 | 실행하지 않음 |

문서 생략 대상은 위 경로로 한정합니다. `build.gradle`, 워크플로, SQL, `scripts/`,
문서가 아닌 `docs/` 파일과 알 수 없는 경로는 모두 앱 검사 대상으로 취급합니다.
rename은 이전·새 경로를 모두 확인합니다. 삭제도 변경 파일에 포함합니다.

PR은 base와 head의 merge-base 이후 변경을 비교합니다. main은 직전 푸시가 아니라
**`Deploy to EC2` job이 성공한 커밋**과 비교합니다. 문서 전용 실행의 성공은 배포 기록으로
사용하지 않으므로, 앞선 코드 배포가 실패하거나 대기 실행이 교체되어도 변경을 빠뜨리지 않습니다.

배포 기준은 GitHub Actions의 최근 완료된 main 실행 20개에서 조회하며, 재실행도 배포 job의
완료 시각으로 비교합니다. 기준이 없거나 조회·Git 비교를 할 수 없으면 전체 앱 검사를 실행합니다.
main의 현재 SHA를 확인할 수 없으면 분류를 실패시켜 배포를 차단합니다.
조회에는 `actions: read` 권한만 사용하고 AWS 인증·EC2 접속은 하지 않습니다.

main 실행은 진행 중인 실행을 자동 취소하지 않습니다. PR의 이전 실행은 기존처럼 취소합니다.
GitHub의 대기 실행 순서는 보장되지 않으므로 이미 최신 main에 뒤처진 push 실행은 배포를 생략합니다.
Actions의 **Re-run jobs**도 원래 이벤트를 따릅니다. 문서만 바뀐 main을 강제로 재배포하려면
**Run workflow → main**을 사용하세요.

문서 전용 실행에서도 기존 `Java build and test` 검사 결과는 남습니다. 이 경우 Java를 설치하지 않고
분류·문서 검사 통과 여부만 확인합니다. `Docker build and smoke test`와 배포 job은 생략됩니다.
분류나 문서 검사에 실패하면 `Java build and test`도 실패하므로 조용히 통과하지 않습니다.

### CI 정책 로컬 검증

Python 3.10 이상과 Git으로 실행할 수 있으며 별도 Python 의존성은 없습니다.

```powershell
python -m unittest discover -s scripts/ci -v
python scripts/ci/check_docs.py
```

회귀 테스트는 임시 Git 저장소와 모의 API 응답을 사용합니다. 운영 배포나 AWS 호출은 하지 않습니다.
Actions 실행 요약의 **CI plan**에서 전체 앱 검사 여부와 판별 이유를 확인할 수 있습니다.

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
