from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    firebase_credentials: str = "serviceAccountKey.json"
    firebase_storage_bucket: str = ""  # e.g. "your-project.appspot.com"
    redis_url: str = "redis://localhost:6379"
    jwt_secret: str = "change-me-to-a-32-char-secret-key"
    jwt_access_expiry: int = 3600
    jwt_refresh_expiry: int = 604800
    kotlin_internal_base: str = "http://localhost:8080"
    # CORS: 쉼표로 구분된 허용 출처 목록. 미설정 시 Kotlin 서버만 허용
    cors_origins: str = ""

    model_config = {"env_file": ".env"}


settings = Settings()
