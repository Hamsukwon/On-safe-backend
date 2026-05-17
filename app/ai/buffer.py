"""
Redis 기반 프레임 카운터 버퍼
device_id별 추론 횟수를 집계해 최소 버퍼(≥30) 이후 AI 추론 가능 여부를 반환.
score는 score:{user_id} 키에 해시로 저장.
"""
import json
from redis.asyncio import Redis
from app.core.config import settings

_redis: Redis | None = None
_redis_bytes: Redis | None = None


def get_redis() -> Redis:
    global _redis
    if _redis is None:
        _redis = Redis.from_url(settings.redis_url, decode_responses=True)
    return _redis


def get_redis_bytes() -> Redis:
    global _redis_bytes
    if _redis_bytes is None:
        _redis_bytes = Redis.from_url(settings.redis_url, decode_responses=False)
    return _redis_bytes


async def push_frame_count(device_id: str) -> int:
    """프레임 카운터를 1 증가시키고 현재 값을 반환. 10초 TTL."""
    r = get_redis()
    key = f"frame_count:{device_id}"
    count = await r.incr(key)
    await r.expire(key, 10)
    return int(count)


async def should_infer(device_id: str, threshold: int = 30) -> bool:
    r = get_redis()
    key = f"frame_count:{device_id}"
    val = await r.get(key)
    return val is not None and int(val) >= threshold


async def save_score(user_id: str, score: float, level: str) -> None:
    r = get_redis()
    await r.set(
        f"score:{user_id}",
        json.dumps({"score": score, "level": level}),
        ex=30,
    )


async def get_score(user_id: str) -> dict | None:
    r = get_redis()
    val = await r.get(f"score:{user_id}")
    return json.loads(val) if val else None


async def save_latest_frame(device_id: str, jpeg_bytes: bytes) -> None:
    r = get_redis_bytes()
    await r.set(f"frame:{device_id}", jpeg_bytes, ex=5)


async def get_latest_frame(device_id: str) -> bytes | None:
    r = get_redis_bytes()
    return await r.get(f"frame:{device_id}")


async def check_caution_cooldown(user_id: str, ttl_seconds: int = 300) -> bool:
    """주의(51~75) 이벤트 쿨다운 확인. True = 쿨다운 없음(이벤트 저장 가능), False = 쿨다운 중.
    Redis NX 플래그로 원자적으로 설정 — 중복 알림/로그 방지 (기본 5분)."""
    r = get_redis()
    key = f"caution_cd:{user_id}"
    result = await r.set(key, "1", ex=ttl_seconds, nx=True)
    return result is not None
