# 고객 알림 연결

정산 완료, 커뮤니티 답글, 끼루 답글을 notification에 저장한다. 알림 종류의 동의와 앱 푸시·이메일 채널 동의를 적용한다. 공제율 마감 안내와 문자는 이번 구현에서 제외했다.

- 댓글과 알림은 동일 JPA 트랜잭션에서 저장한다. 자기 댓글과 수신 고객이 없는 대상에는 알리지 않는다.
- 커밋 후 `/api/customer/notifications/stream`에서 해당 고객의 탭들에 갱신 신호를 보낸다. 상단 종은 공통 목록·읽음 API를 사용한다.
- 중복 이벤트는 기존 `uk_nt_event`로 막는다. 브라우저 재연결·포커스 복귀·30초 보완 조회로 누락된 목록을 복구한다.
- 이메일만 선택해도 알림 이력은 보관한다. 앱 푸시를 껐다면 실시간 갱신 신호·토스트는 보내지 않는다. 알림 목록은 과거 이력을 포함한다.

## SMTP 설정

SMTP 서비스 계정은 별도로 준비해야 한다. `maria/.env`에 아래 항목을 설정하고 서버를 재시작한다. 이 문서는 예시이며 실제 인증 정보를 기록하지 않는다.

```properties
SMTP_ENABLED=true
SMTP_HOST=<SMTP 서버 호스트>
SMTP_PORT=587
SMTP_USERNAME=<SMTP 사용자명>
SMTP_PASSWORD=<서비스의 SMTP 비밀번호 또는 앱 비밀번호>
SMTP_FROM=<서비스가 발송을 허용한 이메일 주소>
SMTP_AUTH=true
SMTP_STARTTLS=true
SMTP_SSL=false
CUSTOMER_WEB_BASE_URL=http://localhost:5173
```

465번 포트의 암시적 TLS를 요구하는 서비스라면 SMTP_PORT=465, SMTP_STARTTLS=false, SMTP_SSL=true로 설정한다. 정확한 인증 방식과 발신 주소는 사용할 서비스의 계약에 맞춘다.

정산 메일은 내장 로고와 금액·완료 시각 카드, 내 계좌 버튼을 가진 HTML과 텍스트 본문으로 발송한다. 금액은 해당 계좌·배치의 환전건별 최신 성공 결과를 중복 없이 합산한다. 완료 시각은 그 결과들의 저장된 processed_at 중 가장 늦은 시각이다. 배치 번호는 고객용 본문에 노출하지 않는다. 답글 메일도 같은 카드 양식을 사용하되 정산 요약 대신 답글 확인 버튼을 표시한다.

CUSTOMER_WEB_BASE_URL은 메일 버튼에 들어가는 고객 웹 주소다. 로컬 시연이면 http://localhost:5173을 사용하고, 다른 기기에서 버튼을 열려면 수신자가 접근할 수 있는 고객 웹 주소로 바꾼다. 로고는 CID로 내장되므로 외부 이미지 서버가 필요 없다. 템플릿 렌더링 실패는 NOTIFICATION_EMAIL_RENDER_FAILED로 기록한다.

SMTP_ENABLED가 없거나 false이면 발송 서버를 만들지 않는다. 수신 이메일이 있으나 SMTP가 준비되지 않았으면 `NOTIFICATION_EMAIL_NOT_CONFIGURED`를 기록하며 발송 성공으로 처리하지 않는다. CI 테스트는 SMTP를 모의하며 실제 외부 메일은 보내지 않는다. 로컬에서는 별도 SMTP 계정 설정 후 수신을 확인할 수 있다. 발신자 표시 이름은 리턴증권이다.

이메일은 DB 커밋 후 별도 작업에서 발송한다. 일시적 전송 오류는 최대 3회 시도하고, 인증 실패는 반복하지 않는다. 최종 실패는 `NOTIFICATION_EMAIL_FAILED`, 작업 큐 포화는 `NOTIFICATION_EMAIL_QUEUE_FAILED`로 남긴다. 프로세스 중단·최종 실패 후 재시작을 넘어 보장하는 영속 메일 발송 대기열은 이번 범위에 포함하지 않는다. 앱 알림 이력은 DB에 유지된다.
