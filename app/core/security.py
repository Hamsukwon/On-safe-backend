from datetime import datetime, timedelta, timezone
import bcrypt
from jose import JWTError, jwt
from .config import settings


def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()


def verify_password(plain: str, hashed: str) -> bool:
    return bcrypt.checkpw(plain.encode(), hashed.encode())


def _build_token(user_id: str, expiry_seconds: int) -> str:
    now = datetime.now(timezone.utc)
    return jwt.encode(
        {"sub": user_id, "iat": now, "exp": now + timedelta(seconds=expiry_seconds)},
        settings.jwt_secret,
        algorithm="HS256",
    )


def generate_access_token(user_id: str) -> str:
    return _build_token(user_id, settings.jwt_access_expiry)


def generate_refresh_token(user_id: str) -> str:
    return _build_token(user_id, settings.jwt_refresh_expiry)


def decode_token(token: str) -> dict:
    return jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])


def validate_token(token: str) -> bool:
    try:
        decode_token(token)
        return True
    except JWTError:
        return False
