from fastapi.testclient import TestClient

from app.core.config import Settings
from app.main import create_app


def test_health_is_available() -> None:
    with TestClient(create_app(Settings(_env_file=None, environment="test"))) as client:
        response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_cors_only_allows_configured_origin() -> None:
    settings = Settings(_env_file=None, cors_origins=["http://localhost:5173"])
    with TestClient(create_app(settings)) as client:
        allowed = client.options(
            "/health",
            headers={
                "Origin": "http://localhost:5173",
                "Access-Control-Request-Method": "GET",
            },
        )
        blocked = client.options(
            "/health",
            headers={
                "Origin": "https://unconfigured.example",
                "Access-Control-Request-Method": "GET",
            },
        )
    assert allowed.status_code == 200
    assert allowed.headers["access-control-allow-origin"] == "http://localhost:5173"
    assert blocked.status_code == 400
    assert "access-control-allow-origin" not in blocked.headers


def test_settings_load_environment_variables(monkeypatch) -> None:
    monkeypatch.setenv("AZAS_ENVIRONMENT", "production")
    monkeypatch.setenv("AZAS_CORS_ORIGINS", '["https://frontend.example"]')
    settings = Settings(_env_file=None)
    assert settings.environment == "production"
    assert settings.cors_origins == ["https://frontend.example"]
