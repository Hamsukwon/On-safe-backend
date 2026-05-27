# AWS SES 이메일 인증번호 발송 — 구현 상세

> 작성일: 2026-05-27  
> 대상 브랜치: `feature/ses-email-migration`  
> 기술 스택: AWS SES SDK v2 (`SesAsyncClient`) + Spring WebFlux (Kotlin Coroutines) + Redis

---

## 목차

1. [개요](#1-개요)
2. [의존성 및 환경 설정](#2-의존성-및-환경-설정)
3. [전체 처리 흐름](#3-전체-처리-흐름)
4. [엔드포인트 목록](#4-엔드포인트-목록)
5. [회원가입 이메일 인증 흐름](#5-회원가입-이메일-인증-흐름)
6. [비밀번호 재설정 이메일 인증 흐름](#6-비밀번호-재설정-이메일-인증-흐름)
7. [핵심 컴포넌트 상세](#7-핵심-컴포넌트-상세)
8. [Redis 키 설계](#8-redis-키-설계)
9. [예외처리](#9-예외처리)
10. [단위 테스트](#10-단위-테스트)

---

## 1. 개요

OnSafe 백엔드는 두 가지 목적으로 이메일 인증번호(6자리 숫자 코드)를 발송한다.

| 목적 | 발송 시점 | TTL |
|------|----------|-----|
| 회원가입 이메일 인증 | 이메일 중복 확인 후 회원가입 전 | **3분** |
| 비밀번호 재설정 인증 | 아이디·이메일 일치 확인 후 | **3분** |

발송 엔진은 Google SMTP에서 **AWS SES SDK v2 (`SesAsyncClient`)** 로 전환됐으며, 코루틴 기반 비동기 처리(`CompletableFuture.await()`)를 사용한다.

---

## 2. 의존성 및 환경 설정

### build.gradle.kts

```kotlin
// AWS SDK v2 — SES 이메일 발송
implementation(platform("software.amazon.awssdk:bom:2.25.0"))
implementation("software.amazon.awssdk:ses")

// CompletableFuture.await() — SesAsyncClient 코루틴 연동
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8")
```

BOM(Bill of Materials)을 통해 SDK 버전을 일괄 관리하고, `ses` 모듈만 선택적으로 포함한다.  
`kotlinx-coroutines-jdk8`은 `CompletableFuture`를 `suspend fun` 내에서 `await()`로 대기할 수 있게 해준다.

### application.yml

```yaml
aws:
  ses:
    region: ${AWS_REGION:ap-northeast-2}
    from: ${SES_FROM_EMAIL:}
```

| 환경 변수 | 설명 | 예시 |
|-----------|------|------|
| `AWS_REGION` | SES 서비스 리전 | `ap-northeast-2` |
| `SES_FROM_EMAIL` | 발신자 이메일 주소 (SES 인증 완료 필요) | `no-reply@onsafe.com` |
| `AWS_ACCESS_KEY_ID` | IAM 자격증명 (EC2 IAM Role 또는 환경변수) | — |
| `AWS_SECRET_ACCESS_KEY` | IAM 자격증명 | — |

> AWS 자격증명은 `application.yml`에 직접 명시하지 않는다.  
> SDK가 **기본 자격증명 체인**(환경변수 → `~/.aws/credentials` → EC2 Instance Profile)을 자동으로 탐색한다.

### SesConfig.kt

```kotlin
@Configuration
class SesConfig(
    @Value("\${aws.ses.region}") private val region: String
) {
    @Bean
    fun sesAsyncClient(): SesAsyncClient =
        SesAsyncClient.builder()
            .region(Region.of(region))
            .build()
}
```

`SesAsyncClient`는 싱글톤 Bean으로 등록된다. 자격증명은 `.credentialsProvider()` 미지정 시 SDK 기본 체인을 사용한다.

---

## 3. 전체 처리 흐름

### 회원가입 이메일 인증

```
클라이언트
  │
  ├─ POST /api/auth/send-email-code  { "mail": "user@example.com" }
  │      │
  │      ▼ AuthService.sendEmailCode()
  │      ├─ 6자리 난수 코드 생성
  │      ├─ Redis SET  email_verify:{mail} = {code}  TTL 180s
  │      └─ EmailService.sendEmailVerificationCode(mail, code)
  │              │
  │              ▼ SesAsyncClient.sendEmail().await()
  │              └─ AWS SES ──→ 수신자 이메일
  │
  ├─ POST /api/auth/verify-email-code  { "mail": "...", "code": "123456" }
  │      │
  │      ▼ AuthService.verifyEmailCode()
  │      ├─ Redis GET  email_verify:{mail}
  │      ├─ 코드 일치 여부 검증
  │      └─ Redis DEL  email_verify:{mail}  (검증 완료 즉시 삭제)
  │
  └─ POST /api/auth/register  { ... }
         └─ 회원 저장 (이메일 인증 완료 여부는 서버가 별도 강제하지 않음)
```

### 비밀번호 재설정 이메일 인증

```
클라이언트
  │
  ├─ POST /api/auth/send-reset-code  { "user_id": "hong", "mail": "user@example.com" }
  │      │
  │      ▼ AuthService.sendResetCode()
  │      ├─ userRepository.findByUserId() — 존재 여부 확인
  │      ├─ user.mail == request.mail — 이메일 소유권 확인
  │      ├─ Redis SET  reset_code:{userId} = {code}  TTL 180s
  │      └─ EmailService.sendResetCode(mail, code)
  │              └─ AWS SES ──→ 수신자 이메일
  │
  ├─ POST /api/auth/verify-reset-code  { "user_id": "hong", "code": "654321" }
  │      │
  │      ▼ AuthService.verifyResetCode()
  │      ├─ Redis GET  reset_code:{userId}  — 코드 검증
  │      ├─ Redis DEL  reset_code:{userId}
  │      └─ Redis SET  reset_verified:{userId} = "1"  TTL 600s
  │
  └─ POST /api/auth/reset-password  { "user_id": "hong", "new_password": "..." }
         │
         ▼ AuthService.resetPassword()
         ├─ Redis GET  reset_verified:{userId}  — 단계 검증 토큰 확인
         ├─ 비밀번호 bcrypt 해시 후 저장
         └─ Redis DEL  reset_verified:{userId}
```

---

## 4. 엔드포인트 목록

| 메서드 | URL | 설명 | 인증 |
|--------|-----|------|------|
| `POST` | `/api/auth/send-email-code` | 회원가입 이메일 인증코드 발송 | 없음 |
| `POST` | `/api/auth/verify-email-code` | 회원가입 이메일 인증코드 확인 | 없음 |
| `POST` | `/api/auth/send-reset-code` | 비밀번호 재설정 인증코드 발송 | 없음 |
| `POST` | `/api/auth/verify-reset-code` | 비밀번호 재설정 인증코드 확인 | 없음 |
| `POST` | `/api/auth/reset-password` | 비밀번호 변경 (인증 완료 후) | 없음 |

---

## 5. 회원가입 이메일 인증 흐름

### 5-1. 인증코드 발송

**Request**

```
POST /api/auth/send-email-code
Content-Type: application/json

{ "mail": "user@example.com" }
```

**DTO: `SendEmailCodeRequest`**

```kotlin
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SendEmailCodeRequest(
    @field:NotBlank(message = "이메일을 입력해주세요.")
    val mail: String
)
```

**서비스 로직: `AuthService.sendEmailCode()`**

```kotlin
suspend fun sendEmailCode(request: SendEmailCodeRequest) {
    val code = generateVerificationCode()              // (100000..999999).random()
    redis.opsForValue()
        .set("email_verify:${request.mail}", code, Duration.ofSeconds(180L))
        .awaitSingle()
    emailService.sendEmailVerificationCode(request.mail, code)
}
```

**Response 200**

```json
{ "success": true, "message": "인증코드가 발송되었습니다.", "data": null }
```

---

### 5-2. 인증코드 확인

**Request**

```
POST /api/auth/verify-email-code
Content-Type: application/json

{ "mail": "user@example.com", "code": "123456" }
```

**DTO: `VerifyEmailCodeRequest`**

```kotlin
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class VerifyEmailCodeRequest(
    @field:NotBlank(message = "이메일을 입력해주세요.")
    val mail: String,

    @field:NotBlank(message = "인증코드를 입력해주세요.")
    val code: String
)
```

**서비스 로직: `AuthService.verifyEmailCode()`**

```kotlin
suspend fun verifyEmailCode(request: VerifyEmailCodeRequest) {
    val key = "email_verify:${request.mail}"
    val storedCode = redis.opsForValue().get(key).awaitFirstOrNull()
        ?: throw BusinessException(ErrorCode.INVALID_EMAIL_CODE)   // TTL 만료 또는 미발송
    if (storedCode != request.code) throw BusinessException(ErrorCode.INVALID_EMAIL_CODE)
    redis.delete(key).awaitSingle()                                 // 검증 즉시 삭제
}
```

**Response 200**

```json
{ "success": true, "message": "이메일 인증이 완료되었습니다.", "data": null }
```

**Response 400 — 코드 불일치 또는 만료**

```json
{ "success": false, "message": "유효하지 않은 인증코드입니다. 코드가 만료되었거나 올바르지 않습니다.", "data": null }
```

---

## 6. 비밀번호 재설정 이메일 인증 흐름

### 6-1. 인증코드 발송 (유저 검증 포함)

**Request**

```
POST /api/auth/send-reset-code
Content-Type: application/json

{ "user_id": "hong123", "mail": "user@example.com" }
```

**서비스 로직: `AuthService.sendResetCode()`**

```kotlin
suspend fun sendResetCode(request: SendResetCodeRequest) {
    val user = userRepository.findByUserId(request.userId)
        ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)        // 존재하지 않는 아이디
    if (user.mail != request.mail)
        throw BusinessException(ErrorCode.MAIL_NOT_MATCH)           // 이메일 소유권 확인

    val code = generateVerificationCode()
    redis.opsForValue()
        .set("reset_code:${request.userId}", code, Duration.ofSeconds(180L))
        .awaitSingle()
    emailService.sendResetCode(request.mail, code)
}
```

> 이메일 인증(send-email-code)과 달리 **아이디와 등록 이메일 일치 여부를 먼저 검증**한 뒤 발송한다.

### 6-2. 인증코드 확인 + 단계 검증 토큰 발급

**서비스 로직: `AuthService.verifyResetCode()`**

```kotlin
suspend fun verifyResetCode(request: VerifyResetCodeRequest) {
    val key = "reset_code:${request.userId}"
    val storedCode = redis.opsForValue().get(key).awaitFirstOrNull()
        ?: throw BusinessException(ErrorCode.INVALID_RESET_CODE)
    if (storedCode != request.code) throw BusinessException(ErrorCode.INVALID_RESET_CODE)
    redis.delete(key).awaitSingle()

    // 단계 검증 토큰 — 10분 내에 reset-password를 호출해야 함
    redis.opsForValue()
        .set("reset_verified:${request.userId}", "1", Duration.ofSeconds(600L))
        .awaitSingle()
}
```

### 6-3. 비밀번호 변경

**서비스 로직: `AuthService.resetPassword()`**

```kotlin
suspend fun resetPassword(request: ResetPasswordRequest) {
    val verifiedKey = "reset_verified:${request.userId}"
    redis.opsForValue().get(verifiedKey).awaitFirstOrNull()
        ?: throw BusinessException(ErrorCode.INVALID_RESET_CODE)    // 단계 검증 토큰 없음

    val user = userRepository.findByUserId(request.userId)
        ?: throw BusinessException(ErrorCode.USER_NOT_FOUND)
    userRepository.save(user.copy(password = passwordEncoder.encode(request.newPassword)))
    redis.delete(verifiedKey).awaitSingle()                         // 변경 완료 후 삭제
}
```

---

## 7. 핵심 컴포넌트 상세

### EmailService

```kotlin
@Service
class EmailService(
    private val sesClient: SesAsyncClient,
    @Value("\${aws.ses.from}") private val fromEmail: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun sendEmailVerificationCode(to: String, code: String) = sendEmail(
        to = to,
        subject = "[OnSafe] 이메일 인증코드",
        body = """
            안녕하세요, OnSafe입니다.

            이메일 인증코드: $code

            인증코드는 3분간 유효합니다.
            본인이 요청하지 않은 경우 이 메일을 무시해 주세요.
        """.trimIndent()
    )

    suspend fun sendResetCode(to: String, code: String) = sendEmail(
        to = to,
        subject = "[OnSafe] 비밀번호 재설정 인증코드",
        body = """
            안녕하세요, OnSafe입니다.

            비밀번호 재설정 인증코드: $code

            인증코드는 3분간 유효합니다.
            본인이 요청하지 않은 경우 이 메일을 무시해 주세요.
        """.trimIndent()
    )

    private suspend fun sendEmail(to: String, subject: String, body: String) {
        try {
            sesClient.sendEmail(
                SendEmailRequest.builder()
                    .source(fromEmail)
                    .destination(Destination.builder().toAddresses(to).build())
                    .message(
                        Message.builder()
                            .subject(Content.builder().data(subject).charset("UTF-8").build())
                            .body(
                                Body.builder()
                                    .text(Content.builder().data(body).charset("UTF-8").build())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            ).await()   // CompletableFuture → 코루틴 대기
        } catch (e: SdkClientException) {
            log.error("SES 연결 실패 (수신: $to): ${e.message}", e)
            throw BusinessException(ErrorCode.MAIL_SEND_FAILED)
        } catch (e: SesException) {
            log.warn("SES 발송 거부 (수신: $to, 코드: ${e.statusCode()}): ${e.awsErrorDetails()?.errorMessage()}")
            throw BusinessException(ErrorCode.MAIL_SEND_FAILED)
        }
    }
}
```

#### 핵심 설계 포인트

| 항목 | 내용 |
|------|------|
| 비동기 처리 | `SesAsyncClient`가 `CompletableFuture<SendEmailResponse>`를 반환하며, `kotlinx-coroutines-jdk8`의 `.await()`로 코루틴에서 논블로킹 대기 |
| 인코딩 | 제목·본문 모두 `charset("UTF-8")` 명시 — 한글 깨짐 방지 |
| 메일 형식 | 현재 텍스트(plain text)만 사용. HTML 메일은 `Body.builder().html(...)` 추가로 확장 가능 |
| 발신자 | `aws.ses.from` 설정값. SES에서 인증(Verified Identity)된 이메일이어야 발송 가능 |

---

### 코드 생성 로직

```kotlin
private fun generateVerificationCode() = (100000..999999).random().toString()
```

- 항상 6자리 보장 (100000 ~ 999999)
- `kotlin.random.Random`의 기본 구현 사용 — 암호학적으로 안전하지 않음
- 보안 강화가 필요하다면 `java.security.SecureRandom`으로 교체 가능

---

## 8. Redis 키 설계

| 키 패턴 | 값 | TTL | 삭제 시점 |
|---------|-----|-----|----------|
| `email_verify:{mail}` | 6자리 인증코드 | **180초 (3분)** | 인증 성공 즉시 |
| `reset_code:{userId}` | 6자리 인증코드 | **180초 (3분)** | 인증 성공 즉시 |
| `reset_verified:{userId}` | `"1"` (존재 여부만 사용) | **600초 (10분)** | 비밀번호 변경 완료 후 |
| `bl:{token}` | `"1"` | 토큰 남은 만료 시간 | 자동 만료 |

#### 상수 정의 위치 (`AuthService.kt`)

```kotlin
private const val EMAIL_CODE_TTL = 180L     // 3분
private const val RESET_CODE_TTL = 180L     // 3분
private const val RESET_VERIFIED_TTL = 600L // 10분
```

#### 설계 의도

- `email_verify`와 `reset_code`의 키 스키마가 다른 이유: 회원가입은 이메일 기준, 비밀번호 재설정은 userId 기준으로 상태를 관리하기 때문
- `reset_verified` 토큰은 코드 인증 → 비밀번호 변경 사이의 **단계 검증용**으로, 이 키가 없으면 `reset-password` 호출이 거부됨
- 검증 성공 후 즉시 삭제(DEL)하여 코드 재사용을 방지

---

## 9. 예외처리

### AWS SDK 예외 계층

```
Throwable
└── Exception
    └── SdkException (software.amazon.awssdk.core.exception)
        ├── SdkClientException  ← 클라이언트 측 오류 (네트워크, 타임아웃)
        └── SdkServiceException
            └── AwsServiceException
                └── SesException  ← SES 서비스 측 거부 응답
```

### 예외별 처리

| 예외 | 발생 상황 | 처리 |
|------|-----------|------|
| `SdkClientException` | 네트워크 단절, 연결 거부, 타임아웃 | `log.error` (스택트레이스 포함) → `BusinessException(MAIL_SEND_FAILED)` |
| `SesException` | SES 서비스 거부 (수신자 미인증, 발송 한도 초과, 스팸 감지 등) | `log.warn` (HTTP 상태코드·AWS 에러 메시지 포함) → `BusinessException(MAIL_SEND_FAILED)` |

> `SdkClientException`은 `SesException`의 상위 클래스가 아니므로 **두 catch 블록을 분리**해야 한다.  
> 두 예외를 같은 블록으로 처리하면 네트워크 장애와 서비스 거부를 구분할 수 없어 운영 중 원인 파악이 어렵다.

### ErrorCode 정의

```kotlin
// ── 이메일 인증 ────────────────────────────────────────
INVALID_EMAIL_CODE(HttpStatus.BAD_REQUEST, "유효하지 않은 인증코드입니다. 코드가 만료되었거나 올바르지 않습니다."),
MAIL_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이메일 발송에 실패했습니다."),

// ── 비밀번호 재설정 ────────────────────────────────────
MAIL_NOT_MATCH(HttpStatus.BAD_REQUEST, "이메일이 일치하지 않습니다."),
INVALID_RESET_CODE(HttpStatus.BAD_REQUEST, "유효하지 않은 인증코드입니다. 코드가 만료되었거나 올바르지 않습니다."),
```

### GlobalExceptionHandler — 클라이언트 응답 형식

```kotlin
@ExceptionHandler(BusinessException::class)
fun handleBusinessException(e: BusinessException): ResponseEntity<ApiResponse<Nothing>> {
    return ResponseEntity
        .status(e.errorCode.status)
        .body(ApiResponse.fail(e.errorCode.message))
}
```

최종 응답 예시 (`MAIL_SEND_FAILED`):

```json
{
  "success": false,
  "message": "이메일 발송에 실패했습니다.",
  "data": null
}
```

---

## 10. 단위 테스트

**파일:** `src/test/kotlin/com/onsafe/backend/domain/auth/EmailServiceTest.kt`

| 테스트 | 검증 내용 |
|--------|-----------|
| `이메일 발송 성공 - 정상 처리된다` | `CompletableFuture.completedFuture()`로 성공 응답 mock → 예외 없이 완료 |
| `SdkClientException 발생 시 MAIL_SEND_FAILED 예외로 변환된다` | `future.completeExceptionally(SdkClientException.create(...))` → `BusinessException(MAIL_SEND_FAILED)` |
| `SesException 발생 시 MAIL_SEND_FAILED 예외로 변환된다` | `SesException.builder().awsErrorDetails(...)` → `BusinessException(MAIL_SEND_FAILED)` |
| `sendResetCode - SdkClientException 발생 시 MAIL_SEND_FAILED 예외로 변환된다` | 비밀번호 재설정 발송 경로에서도 동일 변환 확인 |

### 핵심 Mock 패턴

```kotlin
// 성공 케이스
every { sesClient.sendEmail(any<SendEmailRequest>()) } returns
    CompletableFuture.completedFuture(SendEmailResponse.builder().messageId("msg-id").build())

// 실패 케이스 — CompletableFuture를 직접 예외로 완료
val future = CompletableFuture<SendEmailResponse>()
future.completeExceptionally(SdkClientException.create("Connection refused"))
every { sesClient.sendEmail(any<SendEmailRequest>()) } returns future

// SesException은 awsErrorDetails를 builder로 명시해야 NPE 방지
future.completeExceptionally(
    SesException.builder()
        .statusCode(400)
        .awsErrorDetails(
            AwsErrorDetails.builder()
                .errorCode("MessageRejected")
                .errorMessage("Email address is not verified")
                .build()
        )
        .build()
)
```

> `SesException` mock 시 `awsErrorDetails`를 반드시 builder로 세팅해야 한다.  
> 서비스 코드에서 `e.awsErrorDetails()?.errorMessage()`로 null-safe 접근하더라도,  
> 내부 `awsErrorDetails()` 자체가 `null` 반환 시 NPE가 발생하는 구현이 있어 테스트에서도 값을 채워야 안전하다.

---

## 부록 — SES 샌드박스 vs 프로덕션

| 구분 | 샌드박스 | 프로덕션 |
|------|---------|---------|
| 수신자 제한 | SES에서 인증된 이메일만 수신 가능 | 제한 없음 |
| 발송 한도 | 1일 200건, 초당 1건 | 계정별 한도 상향 신청 |
| 적용 환경 | 개발·테스트 | 실제 서비스 |
| 전환 방법 | AWS 콘솔 → SES → Account dashboard → Request production access | — |

> 현재 코드에서 `SesException`으로 잡히는 `MessageRejected` 오류는 샌드박스 환경에서  
> 인증되지 않은 수신자에게 발송 시도할 때 자주 발생한다.
