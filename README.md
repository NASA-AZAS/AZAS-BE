# AZAS API

Lunar Landing Site Navigator의 FastAPI 백엔드 초기 프로젝트입니다.

## 로컬 실행

Python 3.11과 [uv](https://docs.astral.sh/uv/getting-started/installation/)를 사용합니다.

```powershell
uv sync --locked
Copy-Item .env.example .env
uv run uvicorn app.main:app --reload
```

- API 문서: http://127.0.0.1:8000/docs
- OpenAPI: http://127.0.0.1:8000/openapi.json
- 프로세스 헬스체크: http://127.0.0.1:8000/health

이 작업 공간에서는 uv를 `.tools`에 설치했습니다. PATH에 uv가 없다면
`uv` 대신 `.\.tools\Scripts\uv.exe`를 사용하세요.
Linux에서는 환경 파일을 `cp .env.example .env`로 복사합니다.

## 의존성과 검사

`pyproject.toml`에 직접 의존성을 선언하고 `uv.lock`으로 전체 버전을 고정합니다.
두 파일을 함께 커밋하세요. `.venv`와 `.env`는 커밋하지 않습니다.

```powershell
uv add <package>
uv add --dev <package>
uv run ruff check .
uv run ruff format --check .
uv run pytest
```

런타임: FastAPI, Uvicorn, pydantic-settings.
개발용: pytest, HTTPX2(TestClient), Ruff.

## 구조

```text
app/
  main.py          # 앱 생성 및 미들웨어 연결
  core/config.py   # 환경변수 및 .env 설정
  api/health.py    # /health
  api/router.py    # /api/v1 기능 라우터를 추가할 위치
tests/
```

환경변수는 `AZAS_` 접두사를 사용합니다. `.env.example`을 참고하세요.
배포할 때 `AZAS_ENVIRONMENT=production`과 실제 프런트엔드 origin을 지정하세요.
CORS origin 목록은 JSON 배열이며, 환경변수가 `.env`보다 우선합니다.
`/health`는 프로세스 응답만 검사하며 외부 API나 DB 연결 상태를 보장하지 않습니다.

## Docker

```powershell
docker build -t azas-api .
docker run --rm -p 8000:8000 --env-file .env azas-api
```

컨테이너는 비루트 사용자로 실행하며 개발 의존성과 `.env`를 포함하지 않습니다.
운영 환경에서는 `--reload`를 사용하지 않습니다.

## 다음 단계

1. 착륙지·날짜 입력 및 Horizons 연동 API 구현
2. 저장이 필요한 데이터가 정해지면 DB와 마이그레이션 도입
3. GitHub Actions 검사와 EC2 배포 파이프라인 연결
