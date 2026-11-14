# ECR 기반 EC2 자동 배포

`main` 푸시 → Ruff·pytest → Docker 빌드·헬스체크 → ECR 업로드 → SSM으로 EC2 배포.
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
검사를 통과하면 기존 컨테이너를 정지하고 새 컨테이너를 80번 포트에서 실행합니다.
전환 후 검사에 실패하면 이전 컨테이너를 다시 실행합니다.
단일 EC2 구성이라 전환 때 짧은 중단이 있습니다. 최초 배포에는 복구할 이전 컨테이너가 없습니다.

컨테이너당 메모리 제한은 384MB이며 재부팅 후 자동으로 시작합니다.
EC2에는 현재·직전 이미지를 유지하고, ECR에는 업로드한 SHA 태그 이미지를 유지합니다.
ECR 이미지는 자동 삭제하지 않으므로 필요에 따라 보존 정책을 추가하세요.
현재 Elastic IP는 `3.37.142.87`, DNS는 `api.azas.kro.kr`로 연결되어 있습니다.
HTTPS 최초 적용 전에는 `http://api.azas.kro.kr`로 접근합니다.
Nginx·Certbot 전환과 최초 적용 절차는 [HTTPS 가이드](https.md)를 참고하세요.

이미지나 SSM 배포 실패는 GitHub Actions에서 확인합니다. SSM Command ID도 로그에 남습니다.
이전 이미지로 돌아가려면 AWS 권한이 있는 관리자가 `azas-api-deploy` 문서에 이전 SHA 이미지 URI를
넣어 실행할 수 있습니다. GitHub의 재실행은 같은 SHA 이미지가 이미 있으면 이를 재사용합니다.
