# PostgreSQL, Secrets Manager, JPA와 Flyway

운영 DB는 앱 EC2와 분리된 RDS PostgreSQL입니다. GitHub Actions에는 DB 비밀번호를
저장하지 않습니다. EC2 배포 도구가 인스턴스 역할로 Secrets Manager를 읽어 후보 환경 파일을
만들고, JPA·Flyway와 DB readiness 검사를 통과한 이미지와 환경 파일을 함께 적용합니다.
앱이 시작할 때마다 Secret을 읽는 방식은 아니므로 값을 변경했다면 재배포해야 합니다.

## 로컬 개발

Windows에서는 Docker Desktop과 JDK 21을 준비하고 `scripts/run-local.ps1`을 실행합니다.
`.env.example` → `.env` → 기존 프로세스 환경변수 순서로 덮어씁니다. PowerShell 스크립트는
종료할 때 자신이 추가한 프로세스 환경변수를 복구하며 `.env`를 수정하지 않습니다.

다른 환경에서는 아래 DB를 시작하고 IDE/셸에 연결 변수를 설정합니다.

```bash
docker compose --env-file deploy/postgres.env --file deploy/compose.local.yaml up --detach --wait
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:55432/azas?sslmode=disable'
export SPRING_DATASOURCE_USERNAME=azas_local
export SPRING_DATASOURCE_PASSWORD=azas_local_only
./gradlew bootRun
```

로컬 포트는 `127.0.0.1:55432`이며 데이터는 `azas-local_postgres-data` 볼륨에 남습니다.
중지는 같은 Compose 명령에 `down`을 사용합니다. 데이터도 지울 때만 `down --volumes`를 사용하세요.
로컬과 CI는 `deploy/postgres.env`의 digest로 고정한 PostgreSQL 17을 사용합니다.
운영 RDS도 17을 사용하며 제공되는 마이너 버전은 서로 다를 수 있습니다.

## 운영 리소스

`infra/database.yaml`은 다음을 만듭니다.

- RDS `azas-postgres`: PostgreSQL 17.11, `db.t4g.micro`, Single-AZ, 암호화된 gp3 20GB.
  앱과 같은 `ap-northeast-2c`를 기본값으로 사용합니다. 자동 백업 보존 기간은 1일입니다.
- DB 서브넷 그룹과 DB 보안 그룹: 앱 VPC의 두 AZ 서브넷을 사용하며 public 접근은 끕니다.
  5432는 앱 EC2 보안 그룹에서만 허용합니다.
- Secret `/azas/production/database`: 사용자 `azas_app`과 무작위 비밀번호를 생성합니다.
  SecretTargetAttachment가 `engine`, `host`, `port`, `dbname` 등 연결 정보를 추가합니다.
- 기존 EC2 역할에 해당 Secret의 `GetSecretValue`만 추가합니다.
  GitHub 배포 역할에는 DB Secret 읽기 권한을 추가하지 않습니다.

권한이 있는 AWS CLI 프로필에서 변경 세트를 먼저 검토하고 생성합니다.

```bash
aws cloudformation deploy --profile azas --region ap-northeast-2 \
  --stack-name azas-be-database --template-file infra/database.yaml --capabilities CAPABILITY_IAM \
  --parameter-overrides VpcId=vpc-0796ceff243cabcae \
    SubnetIds=subnet-06e96c60d836220bb,subnet-06fcfe8a665d30c24 \
    AppSecurityGroupId=sg-0a6e43e4b582a2d7f RuntimeRoleName=azas-be-ec2-runtime \
  --no-execute-changeset
```

소규모 해커톤용으로 DB 마스터 계정을 앱에서 사용합니다. 운영 기간이 길어지면
마이그레이션 계정과 제한된 앱 계정을 분리하세요. 자동 비밀번호 회전은 설정하지 않습니다.
Secret의 비밀번호만 수정하면 RDS 비밀번호가 바뀌지 않으므로 두 값을 함께 변경해야 합니다.

## 최초 적용 순서

1. DB 스택 생성 후 RDS가 available인지 확인합니다. 스택 출력에서 Secret ARN을 확인합니다.
2. PR의 CI와 리뷰를 완료한 뒤, main 병합 전에 해당 커밋의 배포 도구를 EC2에 설치합니다.
   `jq`, Docker Compose 2.30 이상이 필요합니다. `sudo bash scripts/install-deploy-tools.sh`는
   컨테이너를 재시작하지 않고 기존 도구를 백업합니다.
3. EC2에 root 소유, 모드 0600의 `/etc/azas/database.env`를 만듭니다.

   ```text
   DATABASE_SECRET_ARN=<스택의 DatabaseSecretArn 출력>
   DATABASE_SSL_MODE=verify-full
   ```

   AWS 리전은 기존 `/etc/azas/registry.env`의 `AWS_REGION`을 사용합니다.
   `/etc/azas/app.env`의 기존 `AZAS_` 값은 보존합니다. DB 비밀번호를 직접 복사하지 않습니다.
4. EC2 역할로 Secret을 읽고 RDS에 TLS 연결할 수 있는지 확인합니다. 비밀번호와 Secret JSON을
   터미널·SSM 출력·CI 로그에 남기지 않습니다. 검증 파일은 root만 읽도록 만들고 제거합니다.
5. main 병합 후 기존 ECR·SSM CD가 실행됩니다. 후보 컨테이너의 Flyway, JPA 검증,
   `/actuator/health/readiness`가 성공해야 전환합니다.

TLS는 `verify-full`로 서버 이름과 인증서를 검증합니다. 이미지의
`deploy/certs/rds-ca-bundle.crt`는 AWS 서울 리전 공개 CA 묶음입니다.
출처: https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem
SHA-256: `913fb5b814f17af79d4c1622584a8d0ceddf5b0d76fe353d0c7d1186cdd6b229`.
CA 교체 시 공식 묶음을 갱신하고 연결을 다시 검증하세요. TLS 비활성화는 로컬·CI에서만 사용합니다.

Secret 값은 root 전용 임시 파일에서 검증한 뒤 환경 파일로 변환합니다. Compose는 `format: raw`로
읽으므로 `$`, `#`, 따옴표와 공백이 Docker 사전 검사와 동일하게 전달됩니다.
줄바꿈·NUL이 들어간 자격증명은 거절합니다. 후보 검사가 실패하면 기존 환경 파일을 유지하며,
전환 후 실패하면 이전 이미지·환경 파일을 복구합니다. 성공한 Secret 버전 ID만 별도 기록합니다.
평문 환경변수는 호스트 root/Docker 관리자가 볼 수 있으므로 서버 접근 권한을 제한하세요.

## 스키마 변경

Flyway는 앱 시작 시 `src/main/resources/db/migration/V<번호>__<설명>.sql`을 순서대로 적용합니다.
JPA는 `ddl-auto: validate`, `open-in-view: false`이며 테이블을 자동 생성·수정하지 않습니다.
`clean`은 금지하고 기존 migration의 체크섬을 검증합니다. 적용한 SQL 파일은 수정하지 말고
새 버전을 추가하세요. 아직 도메인이 정해지지 않아 운영용 임의 테이블은 만들지 않습니다.

첫 도메인 구현 시 `V1__...sql`과 JPA Entity를 함께 추가하세요. 테스트 전용 테이블과 실패 SQL은
테스트 리소스에만 있으며 실행 JAR에는 포함하지 않습니다. CI는 실제 PostgreSQL에서 SQL 적용,
재실행 시 중복 적용 방지, JPA 저장·조회, 실패 SQL 트랜잭션 롤백을 확인합니다.

후보 앱의 시작 시점에 운영 DB에 migration이 적용됩니다. 이전 앱도 잠시 실행 중이므로
컬럼 추가 등 기존 앱과 호환되는 변경부터 적용하세요. 삭제·타입 변경은 별도 단계로 나누고
데이터 이관 뒤 진행합니다. **이미지 롤백은 이미 적용된 DB migration을 되돌리지 않습니다.**
배포 실패 시 DB는 전진 수정하거나 검토한 백업 복구 절차를 사용합니다.

Hikari 풀은 최대 3개, minimum idle 0이며 후보와 기존 앱이 동시에 연결할 수 있습니다.
`/health`는 프로세스 응답이고 readiness는 DB 연결을 포함합니다. DB 장애 시 readiness는 503,
복구 시 UP으로 돌아옵니다. Actuator는 상세 DB 정보를 공개하지 않습니다.

## 행사 종료 후 정리

리소스는 자동 종료하지 않습니다. RDS 인스턴스·스토리지·백업·Secret은 사용 기간에 따라 과금됩니다.
데이터가 필요한지 확인하고 백업한 다음 DB 스택을 삭제하세요. 템플릿의 삭제 정책 때문에
최종 DB 스냅샷과 Secret은 남습니다. 이들도 계속 과금될 수 있으므로 보존 필요성을 확인한 뒤
각각 삭제해야 합니다. 앱이 DB를 필요로 하는 동안에는 스택을 삭제하지 마세요.
