from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="AZAS_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_name: str = "Lunar Landing Site Navigator API"
    environment: Literal["local", "test", "staging", "production"] = "local"
    cors_origins: list[str] = Field(default_factory=list)
