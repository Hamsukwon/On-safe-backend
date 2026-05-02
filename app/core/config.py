from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    firebase_credentials: str = "serviceAccountKey.json"
    redis_url: str = "redis://localhost:6379"
    jwt_secret: str = "change-me-to-a-32-char-secret-key"
    jwt_access_expiry: int = 3600
    jwt_refresh_expiry: int = 604800

    model_config = {"env_file": ".env"}


settings = Settings()
