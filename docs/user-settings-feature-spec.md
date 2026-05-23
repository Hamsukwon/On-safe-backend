# 사용자 페이지 - 설정 화면 기능 명세

## 화면 구성

| 화면 | 파일명 | 설명 |
|------|--------|------|
| 설정 메인 | SettingsActivity | 설정 항목 목록 화면 |
| 개인정보 수정 | EditProfileActivity | 프로필 수정 화면 |
| 회원탈퇴 확인 | WithdrawAccountDialog | 회원탈퇴 확인 다이얼로그 |

---

## 기능 목록

### 1. 개인정보 수정

- 수정 가능 항목: 이름, 전화번호, 이메일, 주소(도로명), 상세주소
- 화면 진입 시 현재 저장된 정보 자동 로드

| 구분 | API | 비고 |
|------|-----|------|
| 정보 불러오기 | `GET /api/users/{userId}` | address, addressDetail 포함 |
| 수정 저장 | `PUT /api/users/{userId}` | 변경된 필드만 전송 가능 |

---

### 2. 비밀번호 변경 (설정 내)

- 현재 비밀번호 확인 후 새 비밀번호로 변경
- 설정 내 비밀번호 변경은 `PUT /api/users/{userId}` 사용
  (비밀번호 찾기용 `POST /api/auth/reset-password` 와 구분)

| 구분 | API | 비고 |
|------|-----|------|
| 비밀번호 변경 | `PUT /api/users/{userId}` | currentPassword + password 함께 전달 필수 |

---

### 3. 알림 설정

- 알림 전체 ON/OFF
- 소리 ON/OFF (알림 전체가 꺼지면 자동 비활성화)
- 진동 ON/OFF (알림 전체가 꺼지면 자동 비활성화)

| 구분 | API | 비고 |
|------|-----|------|
| 설정 불러오기 | `GET /api/settings/notifications/{userId}` | notificationEnabled, soundEnabled, vibrationEnabled |
| 설정 저장 | `PUT /api/settings/notifications/{userId}` | 변경된 토글만 전송 가능 |

---

### 4. 로그아웃

- 확인 다이얼로그 후 로그아웃 처리
- 서버 토큰 블랙리스트 등록 + 로컬 토큰 삭제

| 구분 | API | 비고 |
|------|-----|------|
| 로그아웃 | `POST /api/auth/logout` | Authorization 헤더 필수 |

---

### 5. 회원탈퇴

- "회원탈퇴" 텍스트 직접 입력 후 버튼 활성화
- 사용자 데이터 + 설정 데이터 서버에서 함께 삭제

| 구분 | API | 비고 |
|------|-----|------|
| 회원탈퇴 | `DELETE /api/users/{userId}` | Authorization 헤더 필수 |

---

### 6. 개인정보 처리방침

- 현재 미구현 (준비 중)
- 추후 웹뷰 또는 외부 브라우저 연동 예정
