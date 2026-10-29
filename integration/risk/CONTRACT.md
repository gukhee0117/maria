# Python ↔ MARIA 위험등급 연동 계약

팀 공유용 문서다. 실제 키·계정·고객 데이터·서버 주소·개발자 절대 경로를 포함하지 않는다. 현재 동작을 기록하며 새 기능·새 테이블·새 컬럼을 제안하거나 적용하지 않는다.

## 데이터 경계

- Python은 종목별 3년 수정 종가로 위험등급·변동성 등을 계산하고 자체 SQLite에 게시한다. 종목별 가격·등급 원자성, 계산식과 API 경로는 유지한다.
- MARIA는 `GET /grades`를 Pull해 자체 DB에 저장한다. 패키지는 기존 `POST /package/volatility`를 사용한다.
- Python은 MARIA DB와 고객 정보에 접근하지 않는다. MARIA의 업무용 SYSTEM_CLOCK은 변경하지 않는다. 위험등급은 실제 Asia/Seoul 시장 시각을 사용한다.

## GET /grades → MARIA 대응

| 응답 | 저장·해석 |
|---|---|
| ticker | 앞자리 0을 보존한 문자열. domestic_product와 정확히 하나의 매핑 |
| var975 / riskGrade / volatility | var_975 / risk_grade / volatility |
| priceFrom / priceTo | price_from / price_to |
| analysisVersion | analysis_version. Python 최초 게시 버전은 1 |
| asOf | 같은 순간을 KST로 바꾼 뒤 DATETIME 초 단위 calculated_at에 저장 |
| MARIA 수신 시각 | synced_at. 가격 기준일이나 만료 나이를 바꾸지 않음 |

응답은 배열이다. 비어 있거나 일부 종목만 있어도 정상일 수 있다. 응답 누락을 즉시 삭제·만료·동기화 실패로 해석하지 않는다. priceTo는 검증·계산의 기준 거래일이며 단순히 마지막 발견 가격 날짜를 뜻하지 않는다. 기준일의 유효 가격이 없으면 기존 성공 결과를 이전 priceTo로 보존한다.

## 게시·저장 순서

- Python은 종목별 analysis_version을 SQLite 트랜잭션 안에서 증가시킨다. 같은 기간·가격·거래량·계산 결과이면 재분석에도 버전과 asOf를 유지한다.
- MARIA는 종목 부모 행과 기존 등급을 실제 트랜잭션에서 잠그고 SQL 조건에서도 새 결과인지 확인한다. 등급 행이 처음 생성되는 경우도 부모 행 잠금과 PK로 보호한다.
- 기준일을 먼저 비교한다. 더 최신 priceTo는 갱신한다. 같은 priceTo에서는 높은 버전만 반영한다. 과거 priceTo는 높은 버전이어도 거절한다.
- 같은 기준일·버전·정규화된 내용은 멱등 처리한다. 내용이 다르면 충돌로 기록하고 기존 행을 보존한다.
- 기존 stock_risk_grade의 이관 버전 0은 미확인 값이다. Python의 검증된 결과를 받기 전 공통 사용 서비스에서 제외한다.

DB 구조 변경은 앞서 승인된 두 항목, `stock_risk_grade.analysis_version`과 `risk_grade_sync_state`뿐이다. 이 문서 작성 과정에서 추가 변경하지 않았다.

## 같은 달력·같은 만료 경계

양쪽은 같은 공식 KRX 개장일·종료 시각 파일과 범위를 사용한다. MARIA는 Python `calendar-status`의 calendarVersion과 같은 해시를 요구한다. 해시는 SHA-256(개장일 파일 바이트 + 0x00 + 별도 시간 파일 바이트)이다. 별도 시간 파일이 없으면 마지막 바이트열은 비어 있다.

- 현재 시각까지 정규장이 끝난 최신 개장일을 시장 기준일로 사용한다.
- 정상일에는 15:30부터 당일 기준일이다. 15:40은 수집·Pull 시작 시각이다. 변경일은 실제 종료 시각과 종료+10분을 사용한다.
- priceTo 이후 완료된 개장일이 0개면 CURRENT, 1~3개면 STALE, 4개 이상이면 사용하지 않는다. CURRENT만 허용하는 제한은 없다.
- 주말·휴장일은 세지 않는다. 달력·필수 종료 시각이 없으면 판단을 중단하며 평일로 추정하지 않는다.
- 날짜·표본 검증 완료와 KIS 최종 종가 확정 보장은 구분한다.

## 조회·AI·장애

서비스가 사용하는 위험등급은 `RiskGradeService.findUsable()`에서 받아야 한다. 만료된 사본과 버전 0은 삭제하지 않지만 반환·사용하지 않는다. 화면·미래 AI·추천·캐시 모두 같은 검사를 거쳐야 한다. 현재 챗봇과 절세 브리핑은 위험등급을 사용하지 않아 변경하지 않았다.

동기화 실패와 데이터 만료는 독립적이다. Python이 오류를 반환해도 MARIA는 기존 결과의 만료를 계속 검사한다. 이후 빈 정상 응답을 받아도 오래된 사본을 최신으로 되살리지 않는다.

## 시간대 조건

DATETIME과 Java LocalDateTime에는 시간대가 들어 있지 않다. 현재 구현은 위험등급 저장·조회 트랜잭션의 DB 세션이 +09:00인지 확인한다. 세션이 UTC이면 임의로 변환해 제공하지 않고 중단한다.

실제 HTTP 검증에서 DB 전역 UTC + 위험등급 세션 KST 조합은 정상 저장·재조회됐다. 이는 테스트 전용 DataSource 설정으로 확인한 것이며, 운영 풀을 자동 변경하는 기능은 아니다. 기존 SYSTEM_CLOCK의 reference_real_datetime 해석에 영향을 줄 수 있으므로 전역 DB/공용 풀 시간대를 검토 없이 바꾸면 안 된다.
