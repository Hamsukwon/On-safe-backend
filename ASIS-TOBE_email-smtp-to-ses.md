# 이메일 발송 AS-IS → TO-BE: Google SMTP → AWS SES SDK

> 작성일: 2026-05-21 | 브랜치: feature/parent-main

---

## 1. AS-IS — Google SMTP (현재)

### 구조

```
AuthService
  └─ EmailService.sendResetCode / sendEmailVerificationCode
        └─ JavaMailSender.send(SimpleMailMessage)
              └─ Gmail SMTP (smtp.gmail.com:587, STARTTLS)
```

### 관련 파일

| 파일 | 역할 |
|------|------|
| `EmailService.kt` | `JavaMailSender`로 SMTP 발송 |
| `application.yml` | Gmail SMTP 호스트·포트·인증 설정 |
| `build.gradle.kts` | `spring-boot-starter-mail` 의존성 (중복 선언됨) |

### application.yml (현재)

```yaml
spring:
  mail:
    host: ${MAIL_HOST:smtp.gmail.com}
    port: ${MAIL_PORT:587}
    username: ${MAIL_USERNAME:}
    password: ${MAIL_PASSWORD:}
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
```

### EmailService.kt (현재)

```kotlin
@Service
class EmailService(private val mailSender: JavaMailSender) {

    suspend fun sendResetCode(to: String, code: String) = withContext(Dispatchers.IO) {
        try {
            val message = SimpleMailMessage()
            message.setTo(to)
            message.subject = "[OnSafe] 비밀번호 재설정 인증코드"
            message.text = "비밀번호 재설정 인증코드: $code ..."
            mailSender.send(message)
        } catch (e: MailException) {
            throw BusinessException(ErrorCode.MAIL_SEND_FAILED)
        }
    }

    suspend fun sendEmailVerificationCode(to: String, code: String) = withContext(Dispatchers.IO) {
        // 동일 패턴
    }
}
```

### AS-IS 문제점

| # | 문제 | 영향 |
|---|------|------|
| 1 | Gmail 앱 비밀번호 의존 — Google 정책 변경에 취약 | 운영 안정성 |
| 2 | Gmail 1일 발송 한도 500건 (개인 계정 기준) | 서비스 확장성 |
| 3 | `Dispatchers.IO` 블로킹 스레드 사용 — WebFlux 반응형 흐름 이탈 | 성능 |
| 4 | SMTP 연결 실패 시 재시도 로직 없음 | 신뢰성 |
| 5 | `spring-boot-starter-mail` 중복 선언 (`build.gradle.kts` 49, 55번 줄) | 빌드 품질 |

---

## 2. TO-BE — AWS SES SDK (목표)

### 구조

```
AuthService
  └─ EmailService.sendResetCode / sendEmailVerificationCode
        └─ SesClient.sendEmail()  ← AWS SDK v2 (비동기 가능)
              └─ AWS SES (ap-northeast-2)
```

### Lambda 트리거 vs SDK — 선택 근거

| 구분 | Lambda 트리거 | SDK 직접 호출 (선택) |
|------|--------------|----------------------|
| 사용 목적 | **수신** 이메일 처리, 바운스/컴플레인트 이벤트 핸들링 | **발신** 이메일 전송 |
| 구조 | SES → SNS → Lambda 이벤트 체인 | 애플리케이션 → SES API 직접 호출 |
| 인증코드 발송 적합성 | ❌ 발신용이 아님, 구조 복잡도만 증가 | ✅ 표준 방식 |
| 지연 | 이벤트 전파 지연 있음 | 즉시 호출 |
| 비용 | Lambda 실행 비용 추가 | SES 호출 비용만 |

**결론:** 인증코드 발송처럼 애플리케이션이 직접 트리거하는 발신 이메일은 **SDK 직접 호출**이 정석입니다.  
Lambda 트리거는 "SES가 이메일을 수신했을 때" 또는 "발송 실패(바운스) 이벤트를 처리할 때" 사용합니다.

---

## 3. 변경 내용

### 3-1. build.gradle.kts

```kotlin
// 제거
implementation("org.springframework.boot:spring-boot-starter-mail") // 2곳 모두 제거

// 추가
implementation("software.amazon.awssdk:ses:2.25.0")
implementation("software.amazon.awssdk:regions:2.25.0") // BOM 미사용 시 명시
```

> AWS SDK v2 BOM을 사용하면 버전 관리가 편합니다:
> ```kotlin
> implementation(platform("software.amazon.awssdk:bom:2.25.0"))
> implementation("software.amazon.awssdk:ses")
> ```

### 3-2. application.yml

```yaml
# 제거 — spring.mail 블록 전체 삭제

# 추가
aws:
  ses:
    region: ${AWS_REGION:ap-northeast-2}
    from: ${SES_FROM_EMAIL:no-reply@yourdomain.com}  # SES에서 인증된 발신자 주소
```

### 3-3. EmailService.kt (변경 후)

```kotlin
package com.onsafe.backend.domain.auth.service

import com.onsafe.backend.common.exception.BusinessException
import com.onsafe.backend.common.exception.ErrorCode
import kotlinx.coroutines.future.await
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.ses.SesAsyncClient
import software.amazon.awssdk.services.ses.model.*

@Service
class EmailService(
    private val sesClient: SesAsyncClient,
    @Value("\${aws.ses.from}") private val fromEmail: String
) {

    suspend fun sendResetCode(to: String, code: String) {
        sendEmail(
            to = to,
            subject = "[OnSafe] 비밀번호 재설정 인증코드",
            body = """
                안녕하세요, OnSafe입니다.

                비밀번호 재설정 인증코드: $code

                인증코드는 3분간 유효합니다.
                본인이 요청하지 않은 경우 이 메일을 무시해 주세요.
            """.trimIndent()
        )
    }

    suspend fun sendEmailVerificationCode(to: String, code: String) {
        sendEmail(
            to = to,
            subject = "[OnSafe] 이메일 인증코드",
            body = """
                안녕하세요, OnSafe입니다.

                이메일 인증코드: $code

                인증코드는 3분간 유효합니다.
                본인이 요청하지 않은 경우 이 메일을 무시해 주세요.
            """.trimIndent()
        )
    }

    private suspend fun sendEmail(to: String, subject: String, body: String) {
        try {
            sesClient.sendEmail(
                SendEmailRequest.builder()
                    .source(fromEmail)
                    .destination(Destination.builder().toAddresses(to).build())
                    .message(
                        Message.builder()
                            .subject(Content.builder().data(subject).charset("UTF-8").build())
                            .body(Body.builder()
                                .text(Content.builder().data(body).charset("UTF-8").build())
                                .build()
                            )
                            .build()
                    )
                    .build()
            ).await()  // SesAsyncClient → 코루틴 suspend
        } catch (e: SesException) {
            throw BusinessException(ErrorCode.MAIL_SEND_FAILED)
        }
    }
}
```

> `SesAsyncClient` + `.await()` (kotlinx-coroutines-jdk8)을 사용하면  
> `Dispatchers.IO` 블로킹 없이 WebFlux 반응형 흐름에 자연스럽게 통합됩니다.

### 3-4. SesConfig.kt (신규)

```kotlin
package com.onsafe.backend.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ses.SesAsyncClient

@Configuration
class SesConfig(
    @Value("\${aws.ses.region}") private val region: String
) {
    @Bean
    fun sesAsyncClient(): SesAsyncClient =
        SesAsyncClient.builder()
            .region(Region.of(region))
            .build()
    // IAM Role(EC2/ECS) 또는 환경변수(AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)로 자동 인증
}
```

---

## 4. 환경변수 변경

### 제거

| 환경변수 | 설명 |
|---------|------|
| `MAIL_HOST` | Gmail SMTP 호스트 |
| `MAIL_PORT` | Gmail SMTP 포트 |
| `MAIL_USERNAME` | Gmail 계정 |
| `MAIL_PASSWORD` | Gmail 앱 비밀번호 |

### 추가

| 환경변수 | 설명 | 예시 |
|---------|------|------|
| `AWS_REGION` | SES 리전 | `ap-northeast-2` |
| `SES_FROM_EMAIL` | SES 인증된 발신자 주소 | `no-reply@yourdomain.com` |
| `AWS_ACCESS_KEY_ID` | IAM 액세스 키 (로컬/Docker) | — |
| `AWS_SECRET_ACCESS_KEY` | IAM 시크릿 키 (로컬/Docker) | — |

> EC2 또는 ECS 배포 환경에서는 IAM Role을 인스턴스에 부여하면  
> `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` 환경변수 없이도 자동 인증됩니다.

---

## 5. SES 사전 준비 (AWS 콘솔)

| 순서 | 작업 | 비고 |
|------|------|------|
| 1 | SES에서 발신자 이메일/도메인 인증 | 샌드박스: 수신자도 인증 필요 |
| 2 | IAM 사용자 생성 + `AmazonSESFullAccess` 정책 연결 | 로컬/Docker 환경용 |
| 3 | 프로덕션 전환 시 SES 샌드박스 해제 신청 | AWS 지원 케이스 제출 |
| 4 | 발송 한도 확인 | 기본 200건/일 (샌드박스), 해제 후 무제한 |

---

## 6. 변경 파일 요약

| 파일 | 변경 유형 | 내용 |
|------|-----------|------|
| `build.gradle.kts` | 수정 | `spring-boot-starter-mail` 제거, AWS SDK SES 추가 |
| `application.yml` | 수정 | `spring.mail` 블록 제거, `aws.ses` 설정 추가 |
| `EmailService.kt` | 수정 | `JavaMailSender` → `SesAsyncClient` 교체 |
| `config/SesConfig.kt` | 신규 | `SesAsyncClient` Bean 등록 |
| `.env` / `docker-compose.yml` | 수정 | 환경변수 교체 |
