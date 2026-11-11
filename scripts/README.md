# Seed email exception index

기존 시드 고객의 **고객 번호·LOCAL 로그인 아이디·원래 이메일**이 모두 일치하는 경우에만 알림 이메일 소유 확인을 생략한다. 신규 고객이나 이메일을 바꾼 고객은 일반 인증이 필요하다.

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

각 행은 `customerId:sha256(customerId + LF + loginId + LF + email)` 형식이며 UTF-8, 숫자 ID 오름차순, LF 줄바꿈을 사용한다. 이메일 대소문자나 공백은 변경하지 않는다. 누락된 필수 열·중복 고객·중복 LOCAL 계정·알 수 없는 고객은 생성 실패로 처리한다. 빈 이메일과 LOCAL 외 인증수단은 제외한다.

런타임에 목록 파일이 없거나 형식이 잘못되면 시드 예외만 비활성화하며, 정상 이메일 인증 경로는 유지한다.

## 설정 저장의 의미

알림 설정 PUT은 전체 설정을 저장한다. 인증된 이메일의 기본 켜짐 상태에서 `email=false`를 저장하면 명시적인 수신거부다. 같은 값을 반복 저장해도 이력을 중복 생성하지 않는다. 미인증 때문에 암묵적으로 꺼진 상태에서 다른 설정을 저장하는 경우에는 이메일 수신거부 이력을 생성하지 않는다.
