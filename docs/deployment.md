# ECR 기반 EC2 자동 배포

`main` 푸시 → Gradle 테스트·JAR 빌드 → Docker 빌드·헬스체크 → ECR 업로드 → SSM으로 EC2 배포.
PR에서는 검사만 실행하며 배포하지 않습니다. `workflow_dispatch`는 `main`에서 수동 배포할 때 사용합니다.

## 리소스와 권한

- AWS 계정: `389930962915`, 리전: `ap-northeast-2`
- 기존 EC2: `i-0c2ab160f08785697` (`azas-server`)
- CloudFormation: `azas-be-cd`, 템플릿: `infra/cd.yaml`
- ECR: `azas-api`, Git 커밋 SHA를 immutable 이미지 태그로 사용
- GitHub OIDC 공급자: `token.actions.githubusercontent.com`
- 배포 역할: `azas-be-github-deploy`. 이 저장소 `main`의 정확한 OIDC subject만 허용합니다.
  이 ECR 저장소에 대한 업로드와 기존 EC2 한 대의 지정 SSM 문서 실행 권한을 가집니다.
- EC2 역할·인스턴스 프로필: `azas-be-ec2-runtime`.
  이 ECR 저장소 읽기와 `AmazonSSMManagedInstanceCore` 권한을 가집니다.
- SSM 문서: `azas-api-deploy`. 이 저장소의 SHA 태그 이미지만 인수로 받으며,
  EC2의 root 소유 배포 도구(`/usr/local/sbin/azas-deploy`)를 실행합니다.

GitHub에 장기 AWS 액세스 키나 SSH 개인 키를 저장하지 않습니다.
ECR 저장소를 제외한 추가 서버·로드밸런서는 생성하지 않습니다.
ECR 이미지 저장 용량과 전송에는 사용량에 따른 AWS 요금이 발생할 수 있습니다.

## GitHub Actions 변수

Repository Settings → Secrets and variables → Actions → Variables:

| 변수 | 값 |
| --- | --- |
| `AWS_REGION` | `ap-northeast-2` |
| `AWS_INSTANCE_ID` | `i-0c2ab160f08785697` |
| `ECR_REPOSITORY_URI` | CloudFormation `RepositoryUri` 출력 |
| `AWS_DEPLOY_ROLE_ARN` | CloudFormation `DeployRoleArn` 출력 |
| `SSM_DOCUMENT_NAME` | `azas-api-deploy` |

GitHub의 immutable OIDC subject를 사용합니다. 저장소를 재생성하거나 조직을 옮길 때는
`actions/oidc/customization/sub` API에서 subject를 확인해 스택의 `GitHubSubject`를 갱신하세요.

## EC2 최초 설정

1. 스택 생성 후 `InstanceProfileName` 출력에 해당하는 프로필을 기존 EC2에 연결합니다.
2. `scripts/bootstrap-ec2.sh`와 `scripts/deploy.sh`를 EC2의 같은 폴더에 복사합니다.
3. EC2에서 아래 명령을 실행합니다.

```bash
sudo bash bootstrap-ec2.sh 389930962915.dkr.ecr.ap-northeast-2.amazonaws.com/azas-api ap-northeast-2
```

Docker 공식 저장소에서 Docker를 설치하고 AWS CLI v2와 배포 도구를 준비합니다.
SSM 에이전트가 Online 상태가 된 뒤 배포합니다.
앱 환경변수는 EC2의 `/etc/azas/app.env`에 보관합니다. 이 파일은 재설정하지 않습니다.
프런트엔드 도메인이 정해지면 `AZAS_CORS_ORIGINS`를 JSON 배열로 추가하세요.

## 동작과 복구

새 이미지는 실행 중인 서비스를 중단하기 전에 별도 컨테이너로 헬스체크합니다.
검사를 통과하면 Compose API 컨테이너를 교체하고 Nginx를 검사·reload합니다. 공개 포트 80/443은 Nginx가 담당합니다.
전환 후 검사에 실패하면 이전 이미지와 compose.env를 복구합니다.
단일 EC2 구성이라 전환 때 짧은 중단이 있습니다. 최초 배포에는 복구할 이전 컨테이너가 없습니다.

컨테이너당 메모리 제한은 384MB이며 재부팅 후 자동으로 시작합니다.
EC2에는 현재·직전 이미지를 유지하고, ECR에는 업로드한 SHA 태그 이미지를 유지합니다.
ECR 이미지는 자동 삭제하지 않으므로 필요에 따라 보존 정책을 추가하세요.
현재 Elastic IP는 `3.37.142.87`, DNS는 `api.azas.n-e.kr`로 연결되어 있습니다.
현재 HTTPS 주소는 `https://api.azas.n-e.kr`입니다.
Nginx·Certbot 전환과 최초 적용 절차는 [HTTPS 가이드](https.md)를 참고하세요.

이미지나 SSM 배포 실패는 GitHub Actions에서 확인합니다. SSM Command ID도 로그에 남습니다.
이전 이미지로 돌아가려면 AWS 권한이 있는 관리자가 `azas-api-deploy` 문서에 이전 SHA 이미지 URI를
넣어 실행할 수 있습니다. GitHub의 재실행은 같은 SHA 이미지가 이미 있으면 이를 재사용합니다.

## 첫 Spring 배포 전 서버 도구 갱신

기존 EC2 배포 도구는 Python으로 헬스 체크하므로 첫 Spring 이미지 배포 전에 교체합니다.
리뷰·CI를 통과한 전환 PR의 커밋을 EC2에 복사하고 그 체크아웃에서 실행합니다.
main 병합은 자동 배포를 시작하므로 이 작업을 먼저 완료해야 합니다.

```bash
sudo bash scripts/install-deploy-tools.sh
```

설치 도구는 Compose 설정과 셸 문법을 검사하고 배포와 동일한 lock을 사용합니다.
`/usr/local/sbin/azas-deploy`, `/opt/azas/deploy-compose.sh`, `/opt/azas/compose.yaml`을
`/opt/azas/before-spring.*`에 백업하고 교체합니다. 실행 중인 컨테이너를 재시작하지 않으며
app.env, compose.env, 인증서 볼륨과 갱신 타이머를 변경하지 않습니다.

갱신된 도구는 이미지의 Docker HEALTHCHECK를 확인하므로 기존 이미지로도 복구할 수 있습니다.
후보 컨테이너는 최대 150초, Compose 교체와 복구는 각각 최대 180초 기다립니다.
Java는 이미지에 포함되므로 EC2 호스트에 JDK를 설치하지 않습니다.

현재 설정 파일과 실행 상태를 확인한 뒤 main에 병합합니다. 첫 배포 후 다음을 확인합니다.

```bash
sudo docker compose --project-name azas --env-file /etc/azas/compose.env --file /opt/azas/compose.yaml ps
curl --fail https://api.azas.n-e.kr/health
curl --fail --location https://api.azas.n-e.kr/docs > /dev/null
curl --fail https://api.azas.n-e.kr/openapi.json > /dev/null
sudo docker stats --no-stream
```

`/health` 응답은 유지합니다. `/docs`는 Swagger UI로 리다이렉트하며 OpenAPI 내용은 SpringDoc으로
생성됩니다. CORS 차단 preflight는 이전 400 대신 403을 반환합니다. `AZAS_CORS_ORIGINS`의
JSON 배열 형식과 기본 차단 정책은 유지합니다. `.env`는 Docker의 `--env-file`로 주입하며
로컬 `bootRun`에서는 셸/IDE 환경변수를 사용합니다.
