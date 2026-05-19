# #1 — 사용자 나이/관계 필드 추가

## ASIS (현재 상태)

### User 엔티티 (`domain/user/model/entity/User.kt`)
```kotlin
data class User(
    val userId: String,
    val password: String,
    val name: String,
    val phone: String,
    val mail: String,
    val address: String? = null,
    val addressDetail: String? = null,
    val fcmToken: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
```
- `age`, `relation` 필드 **없음**

### UserResponse DTO
```kotlin
data class UserResponse(
    val userId: String,
    val name: String,
    val mail: String,
    val phone: String,
    val createdAt: LocalDateTime
)
```
- `age`, `relation` 클라이언트에 **노출 안 됨**

### UserUpdateRequest DTO
- `name`, `password`, `mail`, `phone` 만 수정 가능
- `age`, `relation` 수정 불가

### Firestore `users` 문서
- 저장 필드: `password`, `name`, `phone`, `mail`, `address`, `address_detail`, `fcm_token`, `created_at`
- `age`, `relation` **미저장**

---

## TOBE (목표 상태)

### User 엔티티
```kotlin
data class User(
    val userId: String,
    val password: String,
    val name: String,
    val phone: String,
    val mail: String,
    val address: String? = null,
    val addressDetail: String? = null,
    val fcmToken: String? = null,
    val age: Int? = null,           // 추가
    val relation: String? = null,  // 추가 (예: "본인", "자녀", "부모", "배우자")
    val createdAt: LocalDateTime = LocalDateTime.now()
)
```

### UserResponse DTO
```kotlin
data class UserResponse(
    val userId: String,
    val name: String,
    val mail: String,
    val phone: String,
    val age: Int?,          // 추가
    val relation: String?,  // 추가
    val createdAt: LocalDateTime
)
```

### UserUpdateRequest DTO
```kotlin
data class UserUpdateRequest(
    val name: String? = null,
    val password: String? = null,
    val mail: String? = null,
    val phone: String? = null,
    val age: Int? = null,          // 추가
    val relation: String? = null   // 추가
)
```

### Firestore `users` 문서
- 추가 저장 필드: `age` (Long), `relation` (String)

---

## 구현 방향

### 수정 파일 목록 (4개)

| 파일 | 변경 내용 |
|------|-----------|
| `User.kt` | `age: Int?`, `relation: String?` 필드 추가 |
| `UserResponse.kt` | `age`, `relation` 필드 추가 + `from()` 반영 |
| `UserUpdateRequest.kt` | `age`, `relation` 필드 추가 |
| `UserRepository.kt` | `toUser()` 역직렬화 + `toMap()` 직렬화에 추가 |

### 주의사항

1. **nullable 처리**: 기존 Firestore 문서에는 `age`, `relation`이 없으므로 반드시 `?` (nullable) + 기본값 `null`
2. **UserService**: `updateUser()`에서 `UserUpdateRequest` → `User.copy()` 시 `age`, `relation` 반영 필요 (UserService 확인 필요)
3. **하위 호환**: 기존 앱 클라이언트는 응답에 새 필드가 추가돼도 null로 받으면 무시하므로 breaking change 없음
4. **검증 불필요**: `age`는 양수 체크 정도면 충분, `relation`은 자유 문자열로 처리 (열거형 강제 시 앱 배포 필요)

### 예상 작업량
- 코드 수정: ~30분 (4파일)
- UserService 동작 확인 후 즉시 적용 가능
