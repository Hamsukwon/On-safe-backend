import firebase_admin
from firebase_admin import credentials, firestore_async, messaging
from .config import settings

_firebase_app: firebase_admin.App | None = None


def init_firebase() -> None:
    global _firebase_app
    if _firebase_app is not None:
        return
    cred = credentials.Certificate(settings.firebase_credentials)
    _firebase_app = firebase_admin.initialize_app(cred)


def get_firestore():
    return firestore_async.client()


def send_fcm(token: str, title: str, body: str, data: dict | None = None) -> str:
    msg = messaging.Message(
        token=token,
        notification=messaging.Notification(title=title, body=body),
        data={k: str(v) for k, v in (data or {}).items()},
    )
    return messaging.send(msg)
