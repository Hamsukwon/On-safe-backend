"""
Firestore TTL 정책 설정 스크립트 — notifications/fall_logs 컬렉션의 expire_at 필드를
TTL 대상으로 지정해, 문서가 expire_at 시각을 지나면 Firestore가 자동으로 삭제하게 한다.
(서버 보관 정책: 알림/사고이력 30일 고정 — 별도 삭제 배치 없이 이 정책으로 충족)

사전 조건:
  - NotificationRepository/FallLogRepository(Kotlin)의 toMap()이 expire_at(Timestamp,
    생성/발생 시각 + 30일)을 채워서 저장한다 — 반영 완료. expire_at이 없는 문서(이 필드 추가
    이전에 저장된 기존 문서)는 TTL 대상에서 자동 제외된다(삭제되지 않음, 소급 적용 안 됨).
  - TTL 삭제는 만료 이후 최대 24시간 이내 best-effort로 실행된다 (Firestore 공식 정책).
  - firebase.json/firestore.indexes.json과 달리 Firebase CLI는 TTL을 지원하지 않아
    Admin API(firestore_admin_v1)로 직접 호출한다.

실행:
    python scripts/setup_firestore_ttl.py

확인:
    gcloud firestore fields describe expire_at --collection-group=notifications
    gcloud firestore fields describe expire_at --collection-group=fall_logs
    (또는 Firebase Console → Firestore Database → TTL 정책 탭)
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from dotenv import load_dotenv
from google.api_core.exceptions import GoogleAPICallError
from google.cloud.firestore_admin_v1 import FirestoreAdminClient
from google.cloud.firestore_admin_v1.types import Field
from google.oauth2 import service_account
from google.protobuf import field_mask_pb2

load_dotenv()

CREDENTIALS_PATH = os.getenv("FIREBASE_CREDENTIALS", "serviceAccountKey.json")
DATABASE_ID = os.getenv("FIRESTORE_DATABASE_ID", "(default)")
TTL_FIELD = "expire_at"
TARGET_COLLECTIONS = ["notifications", "fall_logs"]


def load_project_id() -> str:
    with open(CREDENTIALS_PATH, "r", encoding="utf-8") as f:
        return json.load(f)["project_id"]


def enable_ttl(client: FirestoreAdminClient, project_id: str, collection_id: str) -> None:
    field_name = (
        f"projects/{project_id}/databases/{DATABASE_ID}"
        f"/collectionGroups/{collection_id}/fields/{TTL_FIELD}"
    )
    field = Field(name=field_name, ttl_config=Field.TtlConfig())
    update_mask = field_mask_pb2.FieldMask(paths=["ttl_config"])

    print(f"⏳ {collection_id}.{TTL_FIELD} TTL 정책 적용 요청 중...")
    operation = client.update_field(field=field, update_mask=update_mask)
    operation.result(timeout=120)  # 백엔드 처리 완료까지 대기 (수 분 소요될 수 있음)
    print(f"✅ {collection_id}.{TTL_FIELD} TTL 정책 활성화 완료")


def main():
    if not os.path.exists(CREDENTIALS_PATH):
        print(f"❌ 서비스 계정 키 파일을 찾을 수 없습니다: {CREDENTIALS_PATH}")
        sys.exit(1)

    project_id = load_project_id()
    credentials = service_account.Credentials.from_service_account_file(CREDENTIALS_PATH)
    client = FirestoreAdminClient(credentials=credentials)

    print(f"🔧 Firestore TTL 정책 설정 시작 — 프로젝트: {project_id}")
    for collection_id in TARGET_COLLECTIONS:
        try:
            enable_ttl(client, project_id, collection_id)
        except GoogleAPICallError as e:
            print(f"❌ {collection_id} TTL 설정 실패: {e}")
            sys.exit(1)

    print("\n🎉 Firestore TTL 정책 설정 완료")
    print(f"   ⚠️  {TTL_FIELD} 필드를 실제로 채우는 애플리케이션 코드 반영이 별도로 필요합니다 —")
    print("      이 필드가 없는 기존/신규 문서는 TTL 대상에서 자동 제외됩니다(삭제되지 않음).")


if __name__ == "__main__":
    main()
