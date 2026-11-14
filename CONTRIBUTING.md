# 백엔드 개발 가이드

브랜치·커밋·PR 제목 규칙과 리뷰 절차는
[NASA-AZAS 공통 기여 가이드](https://github.com/NASA-AZAS/.github/blob/main/CONTRIBUTING.md)를 따릅니다.
이 문서는 AZAS API의 개발 기준을 설명합니다.
개발 환경과 실행 방법은 [README](README.md)를 참고하세요.

## 코드 작성 기준

- Python 3.11을 기준으로 작성합니다. 린트와 포맷의 기준은 `pyproject.toml`의 Ruff 설정입니다.
  줄 길이는 100자이며, import 정렬도 Ruff로 검사합니다.
- 모듈·함수·변수는 `snake_case`, 클래스는 `PascalCase`, 상수는 `UPPER_SNAKE_CASE`를 사용합니다.
- 새로 작성하거나 변경하는 함수의 인자와 반환값에 타입 힌트를 작성합니다.
  주석과 docstring은 코드만으로 알기 어려운 의도와 제약을 설명합니다.
- 기능 라우터는 `app/api/`에 두고 `app/api/router.py`에 연결합니다.
  기능 API의 기본 접두사는 `/api/v1`이며 `/health`는 별도 경로입니다.
- API 요청·응답 구조는 Pydantic 모델로 정의합니다. 입력 오류, 상태 코드,
  기존 API 사용에 미치는 영향을 함께 확인합니다.
- 환경 설정은 `app/core/config.py`의 `Settings`로 관리하고 `AZAS_` 접두사를 사용합니다.
  설정 추가·변경 시 `.env.example`과 필요한 문서를 함께 갱신합니다.
- 의존성은 `uv add` 또는 `uv add --dev`로 관리하고 `pyproject.toml`과 `uv.lock`을 함께 커밋합니다.
  `uv.lock`을 직접 편집하지 않습니다.
- `.env`, 토큰, 개인 키 등 비밀정보를 커밋하거나 로그에 남기지 않습니다.
  `.env.example`에는 실제 비밀정보 대신 예시 값을 사용합니다.

## 테스트와 로컬 검사

테스트는 `tests/test_*.py`에 작성하고 함수 이름은 `test_`로 시작합니다.
기능 추가·버그 수정에는 해당 동작을 검증하는 테스트를 추가하거나 갱신합니다.
외부 API 연동 테스트는 실제 서비스나 비밀정보에 의존하지 않도록 mock 또는 fixture를 사용합니다.
앱 테스트는 기존 테스트처럼 `create_app(Settings(_env_file=None, ...))`으로 설정을 주입해
로컬 `.env`의 영향을 차단합니다.

저장소 루트에서 CI와 같은 명령으로 검사합니다.

```powershell
uv sync --locked --dev
uv run --no-sync ruff check .
uv run --no-sync ruff format --check .
uv run --no-sync pytest -W error
```

자동 수정이 필요하면 아래 명령을 사용한 뒤 diff를 확인하세요.

```powershell
uv run --no-sync ruff check --fix .
uv run --no-sync ruff format .
```

Dockerfile이나 의존성을 바꿨다면 README의 Docker 명령으로 빌드와 `/health` 응답도 확인하세요.
CI는 린트, 포맷, 테스트 외에 Docker 빌드와 컨테이너 헬스체크를 실행합니다.
문서만 변경한 경우에는 링크·예시·서식을 확인하고 PR에 검증 범위를 적어도 됩니다.
