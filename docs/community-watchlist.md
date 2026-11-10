# 커뮤니티 관심종목

고객 세션에서 고객 ID를 가져오며 요청에서 받은 고객 ID는 사용하지 않는다. 종목 마스터·고객 인증·업무시계는 기존 서비스를 재사용한다.

## DB 적용

기존 DB에는 `db/community_watchlist_migration.sql`만 실행한다. 기존 데이터는 유지하고 `CREATE TABLE IF NOT EXISTS`로 테이블만 추가한다. 전체 `01_ria_ddl.sql`은 DB를 초기화하므로 기존 환경에 재실행하지 않는다.

신규 환경용 전체 DDL에도 동일한 테이블을 반영했다.

| 컬럼 | 타입 | 의미 |
|---|---|---|
| customer_id | BIGINT | 고객 FK, 복합 PK |
| ticker | VARCHAR(20) | 국내·해외 종목코드, 복합 PK |
| created_at | DATETIME | SYSTEM_CLOCK 등록시각 |

국내·해외 공통 코드이므로 상품 테이블 FK는 두지 않고 추가 시 종목 마스터에 존재하는지 확인한다. 고객당 최대 100개를 허용하며 상한에 도달해도 기존 항목의 중복 추가는 성공한다. 중복 추가는 최초 등록시각을 유지하고, 없는 항목 삭제도 성공한다. 같은 고객의 쓰기는 CUSTOMER 행을 먼저 FOR UPDATE로 잠가 직렬화한다.

## API

모든 경로는 고객 세션 인증이 필요하고 PUT·DELETE는 고객 CSRF 정책을 적용한다.

| 메서드 | 경로 | 응답 data |
|---|---|---|
| GET | /api/customer/community/watchlist | 등록시각·종목코드 순 문자열 배열 |
| PUT | /api/customer/community/watchlist/{ticker} | null |
| DELETE | /api/customer/community/watchlist/{ticker} | null |

프론트는 저장 성공 후 목록을 갱신한다. 저장 응답이 유실되면 서버에서 해당 종목의 상태를 확인하고, 확인도 실패하면 추가 변경을 막고 재조회 경로를 제공한다. 다른 고객의 지연 응답은 폐기한다.

## 검증

- 백엔드: `./gradlew test --tests '*CommunityWatchlist*' --tests '*CommunityTickerMapperTest' spotlessCheck`
- 프론트: `npm test`, `node --test tests/community-*.test.mjs`, `npm run build`
- 현재 커뮤니티 화면은 서버 종목 마스터의 market=DOMESTIC에 있는 코드만 표시하고 시세를 요청한다. 6자리 숫자인 해외 종목도 제외한다. 해외 관심종목은 API에 보존한다.
- 실제 HTTP·MariaDB 검증은 customer-web의 `tests/community-watchlist-http.test.mjs`에 있다. 로컬 시드 서버만 허용하고 시드 고객 1·2를 사용한다.
- `WATCHLIST_HTTP_TEST=1`, `WATCHLIST_TEST_PASSWORD=<시드 비밀번호>`를 설정한 뒤 `node --test tests/community-watchlist-http.test.mjs` 실행.
- 12개 동시 추가를 3회 실행해 모든 응답 성공·중복 한 건을 확인한다. 고객 ID 변조·타 고객 삭제·CSRF·없는 종목·반복 삭제도 검사한다.
- 실제 DB 저장 후 응답만 버리는 방식으로 프론트의 추가·삭제 복구를 확인한다.
- 테스트용으로 추가한 항목만 삭제하여 원래 관심종목을 보존한다.
