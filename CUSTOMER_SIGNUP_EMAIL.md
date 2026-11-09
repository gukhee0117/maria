# 회원가입 이메일 소유 확인

고객 로그인은 이메일 유무와 관계없이 아이디·비밀번호로 완료한다. 신규 가입은 같은 브라우저에서 가입할 이메일의 소유를 먼저 확인해야 한다. 이메일 소유 확인은 PASS·휴대폰 실명 본인인증을 대신하지 않는다.

## API 흐름

1. GET `/api/customer/csrf`로 CSRF 쿠키를 받는다.
2. POST `/api/customer/auth/signup/email`에 `{ "email": "customer@example.com" }`을 보내 인증번호를 요청한다. 재발송도 같은 경로다.
3. POST `/api/customer/auth/signup/email/verify`에 `{ "code": "123456" }`를 보내 확인한다. 이때 로그인 세션을 만들거나 가입 데이터를 저장하지 않는다.
4. 같은 브라우저에서 10분 안에 POST `/api/customer/auth/signup`으로 인증한 이메일과 가입 정보를 보낸다. 성공 시 확인 상태를 소비하고 고객 로그인 세션을 만든다. 가입 실패 시 유효 시간 내 재시도할 수 있다.

모든 POST는 CSRF가 필요하다. 인증번호는 SecureRandom 6자리이고 Redis에는 무작위 토큰과 해시만 저장한다. 코드 유효 시간은 5분이며 5회 오답 시 삭제한다. 재발송은 주소·브라우저별 60초 간격이고, 두 제한은 Redis Lua에서 한 번에 확인·설정한다. 발송이 거절되면 어느 쪽 제한도 추가 소비하지 않는다. 이전 번호는 재발송 시 무효가 된다. 다른 브라우저·다른 이메일·만료된 확인 상태로 가입할 수 없다. 인증 보안 시간은 실제 Clock/Redis TTL을 사용하며 업무시계와 분리한다.

기존 알림 PR의 SMTP_ENABLED·SMTP_HOST·SMTP_PORT·SMTP_USERNAME·SMTP_PASSWORD·SMTP_FROM 설정을 재사용한다. 실제 비밀번호를 코드·문서·PR에 기록하지 않는다. 발송자는 리턴증권이고 인증 메일은 동기 발송한다. SMTP 오류 시 성공 응답을 보내지 않으며 해당 코드도 취소한다. Redis 준비와 요청 토큰 설정만 세션 락 안에서 처리하고 SMTP는 락 밖에서 수행한다. 성공 후 동일 요청인지 확인해 인증 대기 상태를 등록하며, 세션 만료·교체·후속 요청을 이전 발송 결과로 덮어쓰지 않는다. 유효시간·재발송 간격·확인 횟수는 SignupVerificationPolicy를 Java와 Redis에서 공유한다.

## 검증

`./gradlew test --tests 'com.app.maria.domain.customer.*'`로 고객 인증 회귀를 실행한다. Redis 동시성·오답·만료·재발송 검증은 SIGNUP_REDIS_TEST_HOST와 SIGNUP_REDIS_TEST_PASSWORD 환경변수를 설정하면 실행된다. CI는 기존 테스트 전용 Redis를 함께 사용한다. 테스트 SMTP는 모의하며 실제 메일은 보내지 않는다. DB·DDL 변경은 없다.
