from fastapi import HTTPException


def not_found(msg: str) -> HTTPException:
    return HTTPException(status_code=404, detail=msg)


def conflict(msg: str) -> HTTPException:
    return HTTPException(status_code=409, detail=msg)


def unauthorized(msg: str = "아이디 또는 비밀번호가 올바르지 않습니다") -> HTTPException:
    return HTTPException(status_code=401, detail=msg)


def invalid_token() -> HTTPException:
    return HTTPException(status_code=401, detail="유효하지 않은 토큰입니다.")
