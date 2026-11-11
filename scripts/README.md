# Seed email exception index

기존 시드 고객의 **고객 번호·LOCAL 로그인 아이디·원래 이메일**이 모두 일치하는 경우에만 알림 이메일 소유 확인을 생략한다. 신규 고객이나 이메일을 바꾼 고객은 일반 인증이 필요하다.

현재 승인된 목록은 `scale-0.01`의 1,003명 기준이다. 이메일 기준 파일이 없는 10만 명 시드는 이번 적용 범위에서 제외한다. 고객 번호만 추가하거나 임의 이메일을 추정해 예외를 확장하지 않는다.

시연용 소유 확인 우회이므로 `prod` 프로필에서는 설정값과 관계없이 비활성화한다. 다른 환경에서도 `app.notification.seed-email-exemptions-enabled=false`로 끌 수 있다. `app.notification.seed-email-exemptions`에 Spring Resource 위치를 지정하면 다른 승인 목록을 주입할 수 있다. 기본 위치는 `classpath:notification/seed-email-exemptions.sha256`이다.

이 목록은 공개 시드의 일치 여부를 비교하는 SHA-256 인덱스다. 비밀값, 암호화, 인증 토큰이 아니며 HMAC 서명으로 사용하지 않는다. 목록을 변경하면 인증 예외 대상도 바뀌므로 시드 입력과 diff를 검토한 뒤 배포한다. 원문 연락처는 출력하지 않는다.

## 생성 및 확인

Python 3.10 이상과 승인된 시드 CSV 두 파일(`customer.csv`, `customer_auth.csv`)이 필요하다. DB 접근이나 외부 패키지 설치는 없다. CSV는 data-generator의 `scripts/seed_to_csv.py`로 SQL에서 내보낼 수 있다. 원래 PR의 입력은 data-generator 커밋 `1af096c`의 `seed-data/scale-0.01`이다. 최신 시드를 사용하면 연락처가 달라질 수 있으므로 자동 갱신하지 않는다.

MARIA 저장소 루트에서 실행한다. `--seed-dir`에는 두 CSV가 있는 디렉터리를 지정한다.

```powershell
python scripts/generate_seed_email_exemptions.py --seed-dir ../data-generator/seed-data/scale-0.01/csv/maria --check
python scripts/generate_seed_email_exemptions.py --seed-dir ../data-generator/seed-data/scale-0.01/csv/maria
python -m unittest discover -s scripts -p "test_*.py"
```

기본 출력은 `src/main/resources/notification/seed-email-exemptions.sha256`이다. `--check`는 파일을 바꾸지 않고 일치하지 않으면 종료 코드 1을 반환한다. `--output`으로 임시 파일을 지정해 검토할 수도 있다.

각 행은 `customerId:sha256(customerId + LF + loginId + LF + email)` 형식이며 UTF-8, 숫자 ID 오름차순, LF 줄바꿈을 사용한다. 이메일 대소문자나 공백은 변경하지 않는다. 누락된 필수 열·중복 고객·중복 LOCAL 계정·알 수 없는 고객은 생성 실패로 처리한다. 빈 이메일은 제외 건수를 경고하고 인증 예외에서 빼며, LOCAL 외 인증수단도 제외한다. 빈 이메일 행도 중복 LOCAL 계정 검사를 생략하지 않는다. --check에서 출력 파일이 없으면 재생성 안내와 종료 코드 1을 반환한다.

런타임에 목록 파일이 없거나 형식이 잘못되면 시드 예외만 비활성화하며, 정상 이메일 인증 경로는 유지한다. 로딩 실패 원인과 처리 중인 줄 번호를 로그에 남긴다. CI는 생성기 테스트를 실행하고, Java 테스트는 실제 classpath 목록에 승인된 시드 고객이 존재하는지 확인한다. 승인된 CSV를 사용할 수 있는 환경에서는 배포 전에 --check를 실행한다.

## 설정 저장의 의미

알림 설정 PUT은 이메일 필드를 필수로 받는 전체 설정 저장 요청이다. `email=false`가 전달되면 인증 여부와 관계없이 명시적인 수신거부 이력을 저장한다. 이후 인증을 마쳐도 자동으로 켜지지 않는다. `email=true`는 등록 주소와 소유 확인이 필요하며 기본값과 같아도 최초 명시적 저장 이력을 남긴다. 같은 값을 반복 저장해도 이력을 중복 생성하지 않는다.

Redis 장애를 미인증 false와 구분한다. 조회·켜기 저장에서 인증 상태를 확인할 수 없으면 503으로 실패하고 수신거부 행을 자동 생성하지 않는다. 메일 전달은 기존 예외 처리에 따라 중단한다. 끄기 요청은 소유 인증이 없어도 처리할 수 있다.
