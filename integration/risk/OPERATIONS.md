# Risk 운영 기준과 인프라 인수인계

현재 기준으로 갱신하는 팀 공유 문서다. 폐기된 제안·완료 작업은 남은 일에 누적하지 않는다.
계산식·업무 API 경로·종목별 원자성·3거래일 만료 정책은 유지한다. 로컬 검증과 NCP 적용을 구분한다.

## 현재 구현

| 항목 | 동작 |
| --- | --- |
| 실행 | 컨테이너 1개·uvicorn worker 1개·포트 8000. Dockerfile의 UID/GID는 10001 |
| 스케줄러 | 앱 내부 APScheduler·Asia/Seoul. 명시적 활성화 시만 실행. 기본 60초마다 달력 적격성 확인 |
| 시각 | 정상일 15:40·변경일 공식 종료+10분 이후. 최대 한 확인 주기 지연 가능. 시장 기준일·만료는 종료 시각 기준 |
| 복구 | 시작 직후 실행 가능한 최신 거래일 확인. 오전·휴장일에는 필요한 이전 거래일 복구. 성공 종목 제외 |
| 재시도 | 기본 기준일별 종목 처리 총 3회, 기존 SQLite 이력으로 재시작 후에도 제한. 요청 단위 KIS 재시도는 별도 유지 |
| 재시도 제외 | 표본·무결성 오류 반복 제외. 인증·설정·코드 오류는 해당 기준일 자동 재개 중단. 원인 해결 후 CLI로 명시적 재처리 |
| SQLite | WAL·파일별 프로세스 내 쓰기 연결 직렬화·API 별도 읽기 연결. 기존 user_version=1 유지, 추가 테이블·컬럼 없음 |
| 실패 | API 유지·이전 유효 결과 제공. STALE 3거래일까지 허용·4거래일부터 차단. 초기 결과 없음은 정상 데이터로 제공하지 않음 |
| 인증 | 두 업무 API에 X-Risk-Service-Key 필요. RISK_SERVICE_KEY 미설정은 503, 누락·오류 키는 401 |
| health | 내부 GET /health는 인증 없이 HTTP 200. status·lastSuccessfulCalculationAt·lastBatchStatus·batch_running 제공. KIS 호출 없음 |
| 시세 | KIS 사용. 공공데이터 전환 작업 없음 |
| 백업 | 배치 후 전체 SQLite 일관된 백업. 재시도로 갱신되면 당일 파일 교체, 최근 7개 보관. 백업 실패는 계산 없이 백업만 재시도 |

스케줄러는 API 이벤트 루프와 별도의 실행 스레드를 사용하고 기존 OS 배치 잠금을 유지한다.
종료 요청 시 현재 종목을 마치고 나머지는 미완료로 남긴다. 강제 종료 시 종목별 트랜잭션·OS 잠금·체크포인트로 복구한다.
API 조회가 DB를 만들거나 구 스키마를 자동 변경하지 않는다. 수동 CLI의 --as-of·--reanalyze도 유지한다.

## 환경변수

| 이름 | 설정 |
| --- | --- |
| KIS_APP_KEY / KIS_APP_SECRET | 실제 값은 배포 환경에서 주입 |
| KIS_ENV | demo 또는 real |
| RISK_SERVICE_KEY | MARIA와 같은 공유 키. 코드·이미지·로그에 저장하지 않음 |
| RISK_SCHEDULER_ENABLED | 기본 false. 자동 실행 환경에서만 true |
| RISK_SCHEDULER_POLL_SECONDS | 기본 60 |
| RISK_BATCH_MAX_ATTEMPTS | 기본 3 |
| RISK_DB_PATH | 배포 /data/prices.db |
| RISK_BACKUP_DIR | 기본 DB 디렉터리의 backup/, 배포 /data/backup |
| RISK_BACKUP_KEEP | 기본 7 |
| RISK_CALENDAR_PATH | 이미지 기본 /app/config/krx/open-days.csv |
| RISK_TRADING_HOURS_PATH | 이미지 기본 /app/config/krx/trading-hours.csv |
| RISK_UNIVERSE_PATH | 이미지 기본 /app/config/universe.csv |

RISK_SCHEDULER_ENABLED는 Python 배치, RISK_SYNC_ENABLED는 MARIA Pull용으로 서로 다르다.
로컬 RISK_ENV_FILE·KIS_ENV_FILE 참조는 지원하지만 로컬 .env·키·DB·문서는 이미지에 넣지 않는다.

## NCP 구성

- 인프라 전달 결과: /dev/vda2, **ext4**, 약 40GB·사용률 23%. NCP는 WAL 파일시스템 조건에 맞는다.
- named volume risk-data를 /data 전체에 연결. Compose 프로젝트 이름 maria-app,
  전달받은 호스트 경로 /var/lib/docker/volumes/maria-app_risk-data/_data는 실제 Mounts로 확인한다. 코드에는 사용하지 않는다.
- 재시작·재생성·재배포 후 보존. 배포에서 down -v·volume rm 금지.
- 새 볼륨은 이미지의 /data 권한으로 시작한다. 기존 볼륨 UID/GID·쓰기 권한은 별도 확인한다.
- 인프라는 컨테이너 교체·배포 flock 직렬화를 사용한다고 전달했다. 실제 NCP 스크립트를 실행·검증한 것은 아니다.
- 학원 VM의 NFS/CIFS 여부는 미확인이다. NCP 구현을 막지는 않지만 학원 VM 배포 전 별도 확인한다.

## 배포 중 배치 보호

batch_running은 스케줄러와 같은 DB 잠금을 가진 수동 CLI를 감지한다. 판단 불가능한 null을 false로 해석하지 않는다.
health의 DEGRADED는 데이터·배치 상태이며 프로세스 사망과 다르다.

**health 조회 직후 배치 시작과 경쟁할 수 있으므로 조회만으로 배포를 허용하지 않는다.**
deploy.sh에서 같은 /data/prices.batch.lock의 OS 잠금을 비차단으로 확보하고 교체가 끝날 때까지 유지하는 방식으로 협의한다.
호스트와 Python이 같은 파일/inode를 사용해야 하며, 호스트의 볼륨 접근 권한도 확인한다. 살아 있는 잠금 파일을 삭제·교체하지 않는다.
잠금 파일에도 컨테이너 UID/GID 10001의 쓰기 권한을 유지한다. 첫 배포의 빈 볼륨 초기화를 외부 파일 생성으로 방해하지 않도록 순서를 검증한다.

- HTTP 무응답만으로 배포 허용 금지. 컨테이너 실제 중지 여부를 확인하고 실행 중 무응답·불명 상태는 배포 중단.
- risk는 15:00이 아니라 **15:40 또는 변경 종료+10분**. 고정 시간 차단은 변경일도 고려한 안전망이다.
- 현재 실측은 **74종목·593회 호출·13분 12초**. 서버 성능을 8분으로 가정하지 않는다.
- Docker 종료 유예 120초 이상을 인프라와 검토한다. 유예 초과 강제 종료는 다음 시작에 복구한다.
- deploy.sh 수정은 인프라 담당자 작업이며 이번 Python 작업에서 수정하지 않았다.

## 백업·복원

/data/backup/risk-YYYY-MM-DD.sqlite3의 날짜는 기준 거래일이다. SQLite backup API로 임시 파일을 완성한 뒤 원자적으로 교체한다.
분석 버전·정정·실행·복구 이력도 포함한다. 같은 디스크의 백업은 디스크 손상에 대비하지 못한다.
서버 밖 주 1회 복사는 인프라 담당자 절차다. 오브젝트 스토리지는 미정이며 새 백업 엔드포인트는 없다.

```powershell
.\.venv\Scripts\python.exe -m app.cli backup data/manual-backup-NEW.sqlite3
```

이 유지보수 CLI는 기존 파일을 덮어쓰지 않는다. 복원은 API·스케줄러·CLI 쓰기를 중지한 상태에서 진행한다.
오래된 백업에는 이후 게시 이력이 없으므로 MARIA에 전달한 버전 고수위 확인 전 게시를 재개하지 않는다.
프로세스 재시작 복구와 재해 복원은 다르다.

## 달력·종목 원본

- 개장일은 2023-01-01~2026-12-31, 종료 시각은 2026-10-30까지. 이후 미검토 42개 개장일은 비워 자동 실행을 막는다.
- MARIA에 같은 CSV 두 파일·바이트·해시를 적용한다. 수능일 변경·미래 일정은 공지 확인 후 갱신한다.
- 15:40 KIS 반영 관측은 준비만 완료했다. 20시 이후 조회로 15:40을 검증했다고 표현하지 않는다.
- **정확한 시드 확정 후 domestic_product에 맞춘다.** 현재 77개 CSV나 대체 후보를 최종 원본으로 고정하지 않는다.
- 생성기는 gradlew run(기존 77개)과 gradlew generate(새 Catalog CSV 6개)가 공존한다. 최종 경로·원본 확인 전 임의 변경하지 않는다.
- 후보 381180·458730은 공식 명칭·실제 KIS 가격 731개·수익률 730개 검증을 마쳤으나 활성화·시드 수정은 하지 않았다. 091990 대체는 미정이다.
- 207940·086520·012450은 합의한 표본 기준 미달로 미게시를 유지한다.

## 남은 적용·검증

- 실제 NCP 볼륨 권한·재시작·강제 종료·배포 잠금 연계·용량·로그 확인.
- 인프라 배포 차단·서버 밖 주간 백업, 학원 VM 파일시스템 확인.
- MARIA 공유 키 헤더 연결과 인증 포함 HTTP 통합 4개·위험등급 테스트 총 20개 통과. 실제 배포 환경의 공유 키·같은 달력 주입은 남아 있다.
- 달력 갱신·15:40 실제 관측·확정 시드 반영.
- 운영 DB 변경·NCP 배포·자동 동기화 활성화는 별도 단계. 추가 테이블·컬럼 계획 없음.
