# 자금 환류 API 명세 (v1, 구현 반영)

작성 2026-10-30, 구현 반영 2026-11-02 · 담당 이은정 · 대상: 고객 "자금 환류 현황", 관리자 "자금 환류 대시보드"
지표 정의의 원본은 `data-generator/docs/analysis/metrics.md`이고, 이 문서는 그 정의를 API로 옮긴 것이다.

---

## 1. 공통 규칙

| 항목 | 규칙 |
|---|---|
| 응답 형식 | `ApiResponseDTO` (`{ "message": "...", "data": ... }`) |
| 금액 | 원 단위 정수 (`BigDecimal`). JSON에서는 숫자 |
| 비율 | **0~1 사이 소수** (퍼센트가 아님). 소수 4자리. 화면에서 `×100`으로 표시 |
| 기준일 | 모든 응답에 `asOfDate`를 담는다. **`asOfDate` = 업무시계 날짜 − 1일** (전일까지 집계). 시드의 업무시계가 2026-11-18 00:00이므로 `asOfDate`는 2026-11-17이고 분석 기준일과 같다. 화면은 "OO 기준"으로 노출 |
| 업무시계 | `BusinessClockService.now()` 사용. `LocalDate.now()` 금지 |
| 집계 범위 | 일자 조건은 `< 업무시계 날짜`(즉 `asOfDate`까지)로 건다. 스냅샷·종가처럼 날짜가 박힌 값은 그 날짜를 그대로 따른다 |
| 기간 | 쿼리 `period` = `ALL`(기본, 출시 이후 누적) / `MONTH`(이번 달) / `QUARTER`(분기). 기준일(`asOfDate`)이 속한 달·분기의 첫날부터 기준일까지. `overview`, `funnel`, `monthly`, `segments`, `allocation`, `retention`이 받는다. `leakage`, `relief`, `concentration`은 세액 스냅샷(기준일 값)이라 받지 않는다 |
| 관리자 경로 | `/api/admin/fund-flow/**`, `@PreAuthorize("hasAnyRole('ADMIN','SETTLEMENT','REVIEWER','VIEWER')")`. 조회 전용 |
| 고객 경로 | `/api/customer/fund-flow/**`, `@AuthenticationPrincipal CustomerPrincipal`로 본인 계좌만 조회 |
| 소규모 칸 | 5명 미만 칸의 합계·비율은 `null`로 내려보낸다 (개인 식별 방지). 칸 크기(`accounts`)는 내려보낸다 |
| 분모 | 모든 비율 응답에 분모 정의를 `basis` 문자열로 담는다. 화면은 이를 그대로 각주에 쓴다 |

### 1-1. 공통 지표 정의

- **체결금액(국내 매수)** = `ABS(domestic_order.amount) − fee_amount` (BUY, `status='EXECUTED'`). 매수 `amount`는 음수이고 수수료를 포함한다.
- **체결금액(국내 매도)** = `amount + fee_amount + tax_amount` (SELL, `status='EXECUTED'`).
- **확정 유입** = `krw_exchange.final_amount` (`settlement_status='FINALIZED'`). 가정산은 제외한다.
- **재투자율(합계 비율)** = Σ국내 매수 체결금액 ÷ Σ확정 유입. 계좌별 비율의 평균이 아니다.
- **순투자율** = (Σ매수 체결금액 − Σ매도 체결금액) ÷ Σ확정 유입.
- **모집단** = 계좌 개설자 (`account.opened_at IS NOT NULL`).

### 1-2. DB 선행 조건 (중요)

분석에 쓰는 컬럼은 모두 `maria/db/01_ria_ddl.sql`에 정의되어 있다. 다른 DB에서 이 API를 쓰려면 이 DDL로 만든 스키마인지 확인한다.

| 컬럼 | 용도 | DDL 위치(`01_ria_ddl.sql`) |
|---|---|---|
| `domestic_order.fee_amount`, `tax_amount` | 매수·매도 체결금액 계산 | 557, 558행 |
| `domestic_product.sector_name` | 운용처 업종 집중도 | 210행 |
| `fund_flow_monthly.cash_balance_begin`, `cash_balance_end` | 월초·월말 예수금 | 885, 886행 |
| `customer_consent.consent_type = 'MYDATA_PROVISION'` | 퍼널 2단계 | 815~822행(CHECK 목록) |
| `tax_rule.rule_type IN ('BASIC_DEDUCTION','TAX_RATE')` | 감면액 계산 | 428, 435행. 기본값 행은 `db/customer_seed.sql`(189~190행) |

---

## 2. 관리자: 자금 환류 대시보드

### 2-1. `GET /api/admin/fund-flow/overview` — 상단 핵심 지표

쿼리: `period`. 개설 계좌 수는 기간과 무관한 누적(해지 포함)이고, 확정 유입·국내 매수·매도 합계만 기간으로 거른다.
`firstOpenedDate`(첫 계좌 개설일)는 `MIN(account.opened_at)`의 날짜로, `opened_at`이 있고 업무시계 날짜(`clockDate`) 미만인 계좌만 대상이며 기간과 무관하다(`openedAccounts`와 같은 조건). 개설된 계좌가 없으면 `null`.

```json
{
  "message": "자금 환류 개요 조회 성공",
  "data": {
    "asOfDate": "2026-11-01",
    "openedAccounts": 798,
    "firstOpenedDate": "2026-03-23",
    "inflowTotal": 5941559992,
    "domesticBuyTotal": 3981126657,
    "reinvestRatio": 0.6700,
    "netInvestRatio": 0.5975,
    "basis": "분모: 계좌 개설자 Σ확정 유입. 재투자율 = Σ국내 매수 체결금액 ÷ Σ확정 유입. 첫 계좌 개설일 = 개설된 계좌(opened_at이 있고 업무시계 날짜 미만) 중 가장 이른 개설일, 기간과 무관"
  }
}
```
검증 기준값(seed-mariadb, 업무시계 2026-11-02에서 MariaDB 쿼리로 확인): 개설 798계좌, 첫 계좌 개설일 2026-03-23, 확정 유입 5,941,559,992원, 재투자율 0.6700(매수 체결금액 3,981,126,657원 ÷ 유입), 순투자율 0.5975(매도 체결금액 431,333,284원 반영). 단위·서비스·API 테스트의 스텁 값은 이 값과 무관하다.

### 2-2. `GET /api/admin/fund-flow/funnel` — 가입→첫 매수 퍼널

각 단계는 앞 단계를 모두 지난 고객만 센다(중첩). 고객당 "처음 도달" 1회.
쿼리: `period`. 기간을 주면 **그 기간에 가입한 고객 코호트**만 따라간다(`customer.created_at >= 기간 첫날`). 그 고객들이 지금까지 각 단계에 도달한 비율이다.

```json
"data": {
  "asOfDate": "2026-11-17",
  "stages": [
    { "stageNo": 1, "stage": "웹 가입",         "customers": 95050, "convPrev": null,   "convTotal": 1.0000 },
    { "stageNo": 2, "stage": "마이데이터 동의", "customers": 85996, "convPrev": 0.9047, "convTotal": 0.9047 }
  ],
  "basis": "고객당 처음 도달 기준, 앞 단계를 모두 지난 고객만 집계"
}
```
기준값: 95,050 → 85,996 → 81,549 → 75,046 → 52,811 → 52,184. 6단계 = 웹 가입, 마이데이터 동의, 계좌 신청, 개설, 첫 매도, 첫 국내 매수.

### 2-3. `GET /api/admin/fund-flow/monthly` — 월별 자금 흐름

쿼리: `fromMonth`, `toMonth` (선택, `yyyy-MM`), `period`. `fromMonth`를 주지 않으면 `period`의 첫 달이 하한이 된다.
원천: `fund_flow_monthly`를 월별로 합산한다.

```json
"data": {
  "asOfDate": "2026-11-17",
  "months": [
    {
      "flowMonth": "2026-04-01",
      "foreignSellAmount": 0, "krwInflowAmount": 0,
      "domesticBuyAmount": 0, "domesticSellAmount": 0, "withdrawalAmount": 0,
      "cashBalanceBegin": 0, "cashBalanceEnd": 0,
      "partial": false
    }
  ],
  "basis": "domesticBuyAmount는 체결금액+수수료, domesticSellAmount는 체결금액−수수료−거래세 (예수금 순액 기준)"
}
```
- 이 테이블의 국내 매수·매도는 **예수금 순액 기준**이라 §1-1의 체결금액과 다르다. 재투자율에는 쓰지 않는다.
- **2026-11 행은 `fund_flow_monthly`에 없다**(시드는 03~10월만 있음). 11월은 원천 테이블(`sell_order`, `krw_exchange`, `domestic_order`, `withdrawal`)에서 직접 집계하고 `partial = true`로 내려준다. 국내 매수·매도는 03~10월과 이어지도록 **예수금 순액 기준**(매수 = 체결금액+수수료, 매도 = 체결금액−수수료−거래세)으로 집계한다. 11월의 `cashBalanceBegin`/`End`는 `null`.

### 2-4. `GET /api/admin/fund-flow/leakage` — 혜택 누수 5분류

분모는 `tax_snapshot` 행 수(= 계좌 개설자)다. 분류는 위에서부터 먼저 걸리는 하나로 배정한다.

| code | label | 조건 |
|---|---|---|
| `NO_SELL` | 매도 없음 | `weighted_sell = 0` |
| `BENEFIT_CANCELED` | 혜택 취소(조기 인출) | `account.benefit = 'IMPOSSIBLE'` |
| `NO_IMPACT` | 영향 없음 | `adjust_ratio = 1` |
| `PARTIAL_REDUCED` | 부분 축소 | `0 < adjust_ratio < 1` |
| `EXTERNAL_BUY_OFFSET` | 타 계좌 순매수로 소멸 | `adjust_ratio = 0` |

```json
"data": {
  "asOfDate": "2026-11-17",
  "snapshotCalculatedAt": "2026-11-17T02:00:00",
  "total": 75046,
  "categories": [
    { "code": "NO_SELL", "label": "매도 없음", "accounts": 22518, "share": 0.3000, "withExternalBuy": 0 }
  ],
  "basis": "tax_snapshot은 표시용 캐시로, 예상값이며 확정 세액이 아님"
}
```
기준값: 매도 없음 22,518 / 혜택 취소 1,789 / 영향 없음 39,486 / 부분 축소 9,441 / 순매수 소멸 1,812.

응답에 `ratioBins`(조정비율 히스토그램)가 함께 온다. 매도한 계좌(`weighted_sell > 0`)만 대상이고 구간은 `ZERO`(0) / `UNDER_25` / `UNDER_50` / `UNDER_75` / `UNDER_100`(0.75 이상 1 미만) / `FULL`(1)이다. 비어 있는 구간도 순서대로 0으로 채워 낸다. `share`는 매도한 계좌 대비다.

### 2-5. `GET /api/admin/fund-flow/segments` — 연령대 × 투자성향 재투자율

```json
"data": {
  "asOfDate": "2026-11-17",
  "cells": [
    {
      "ageBand": "30대", "investorType": "ACTIVE",
      "accounts": 0, "accountsWithInflow": 0,
      "inflowTotal": 0, "buyTotal": 0,
      "reinvestRatio": 0.0000, "netInvestRatio": 0.0000
    }
  ],
  "basis": "연령은 기준일 만 나이. 5개 미만 칸은 합계·비율이 null"
}
```
- `ageBand`: 20대 이하 / 30대 / 40대 / 50대 / 60대 이상. `investorType`이 `null`이면 연령대 소계, 둘 다 `null`이면 전체.
- 쿼리: `period`. 칸의 `accounts`는 누적 개설 계좌이고, 유입·매수·매도는 기간 합계다.
- 응답에 `ageMedians`가 함께 온다: 연령대별(`ageBand`가 `null`이면 전체) **계좌별** 확정 유입 중앙값(`inflowMedian`)과 재투자율(매수 ÷ 유입) 중앙값(`reinvestMedian`). 확정 유입이 있는 계좌만 대상이고 그런 계좌가 5개 미만이면 두 값은 `null`이다. 중앙값은 SQL이 아니라 서비스에서 계산한다(`FundFlowStatistics.median`).

### 2-6. `GET /api/admin/fund-flow/relief` — 감면액

쿼리: `scope` = `ALL`(기본, 전체 개설자) 또는 `POSITIVE`(감면 > 0인 계좌만). **분모 범위는 팀 확인 대기**이므로 둘 다 지원한다.

```json
"data": {
  "asOfDate": "2026-11-17",
  "definitionVersion": "draft-1",
  "scope": "ALL",
  "accounts": 75046,
  "generalTaxTotal": 0, "finalTaxTotal": 0, "reliefTotal": 0,
  "accountsWithRelief": 0,
  "investPerRelief": 21.0,
  "basis": "일반 과세 세액 = 세율 × max(0, 비가중 총양도소득 − 기본공제). 감면액 = 일반 과세 세액 − 최종세액. 예상값(표시용 스냅샷)"
}
```
- 세율·기본공제는 `tax_rule`의 `TAX_RATE`, `BASIC_DEDUCTION`을 **기준일 시점 유효 행**으로 읽는다.
- `investPerRelief` = 감면 1원당 투자액 = Σ(국내 순투자) ÷ Σ감면액. 기준값은 `ALL` 21.0, `POSITIVE` 15.1이다.
- `original_gain_amount`가 RIA 매도분만인지 총 양도소득인지 **미확인**이라 이 지표는 잠정치다.

### 2-7. `GET /api/admin/fund-flow/allocation` — 국내 운용처 (구현 순서 맨 뒤)

종가는 증권사 DB(`domestic_base_price`)에 있고 타 시스템은 API로만 접근하는 원칙이 있어, **평가액 대신 매입금액 기준**으로 낸다.

```json
"data": {
  "asOfDate": "2026-11-17",
  "valueBasis": "PURCHASE_COST",
  "totalValue": 0,
  "buckets": [ { "label": "ETF·펀드", "value": 0, "share": 0.0000 } ],
  "hhiBucket": 0.0000,
  "hhiProduct": 0.0000,
  "topNetBuy": [ { "name": "", "type": "STOCK", "sectorName": "", "netBuyExec": 0 } ],
  "basis": "매입금액 기준(보유수량 × 평균 매입가). 현재가 평가액이 아님. HHI = Σ점유율², 0~1 (1에 가까울수록 쏠림)"
}
```
- 매입금액 = `domestic_stock_balance.qty × avg_purchase_price` (`qty > 0`). 기존 통계 API와 같은 방식이다.
- 업종(`sector_name`)은 주식에만 있어 펀드·ETF는 `ETF·펀드` 한 항목으로 묶는다.
- `topNetBuy` = 순매수(매수 체결금액 − 매도 체결금액) 상위 10종목. `domestic_order`만으로 계산한다.
- **분석 결과(평가액 기준 HHI)와 숫자가 다르므로 기준값 대조 대상이 아니다.** 평가액·HHI는 종가 조회 API가 생기면 `valueBasis = "MARKET_VALUE"`로 추가한다.

### 2-8. `GET /api/admin/fund-flow/concentration` — 혜택 집중도 (잠정)

세액 스냅샷 기준 예상값이라 `period`를 받지 않는다. 감면액(= 일반 과세 세액 − 최종세액, §2-6과 같은 식)이 0보다 큰 계좌만 대상이다.

```json
"data": {
  "asOfDate": "2026-11-17",
  "accounts": 20333,
  "reliefTotal": 0,
  "top10Share": 0.3580,
  "gini": 0.5300,
  "lorenz": [ { "populationShare": 0.0000, "valueShare": 0.0000 }, { "populationShare": 0.0500, "valueShare": 0.0050 } ],
  "basis": "..."
}
```
- `lorenz`는 하위 0%, 5%, … 100%의 21점이다. `gini = 2·Σ(i·xᵢ) / (n·Σx) − (n+1)/n` (xᵢ 오름차순).
- `top10Share`는 상위 10%(올림한 인원)의 감면액 합 ÷ 전체. 분석 기준값은 지니 0.53, 상위 10% 점유 35.8%.

### 2-9. `GET /api/admin/fund-flow/retention` — 원금 유지 (Kaplan–Meier)

쿼리: `period`(기간 = 그 기간에 환전이 **확정된** 건의 코호트). 단위는 확정된 환전 건(`krw_exchange` FINALIZED), 시작 = `final_at`, 사건 = 그 건의 `IMMATURE_PRINCIPAL_INCLUDED` 배분이 있는 **완료(`withdrawal.status = 'COMPLETED'`) 인출**(취소·실패·요청 중 인출의 배분은 사건으로 세지 않는다. `/early-withdrawal` 분자와 같은 기준), 사건이 없으면 업무시계 날짜에서 중도절단.

```json
"data": {
  "asOfDate": "2026-11-17",
  "lots": 159470, "accounts": 0, "events": 5356,
  "maxObservedDays": 236, "bootstrapReplicates": 200,
  "points": [ { "day": 0, "survival": 1.0000, "lower": 1.0000, "upper": 1.0000, "weightedSurvival": 1.0000, "atRisk": 159470 } ],
  "basis": "..."
}
```
- `points`는 10일 간격 + 마지막 관찰일. `survival`은 건수 기준, `weightedSurvival`은 금액 가중(신뢰구간 없음).
- 신뢰구간은 **계좌 단위 부트스트랩**(200회, 시드 고정 → 같은 입력이면 같은 값)의 2.5·97.5 백분위다. 같은 계좌의 건은 사건 시점을 공유하므로 건 단위 Greenwood는 쓰지 않는다.
- 최대 관찰이 1년에 못 미치므로 **1년 유지율은 정의하지 않는다**. 분석 기준값은 210일 95.5% [95.2, 95.7], 금액 가중 97.6%.
- 약 16만 건·7.5만 계좌 규모에서 계산 약 0.9초(요청마다 계산, 캐시 없음).

### 2-10. `GET /api/admin/fund-flow/maturity` — 만기 도래 스케줄

1년 묶임이 언제 풀리는지를 만기월별로 본다. 쿼리 파라미터는 없다(기간 선택 없음, 업무시계 기준 전체).

```json
"data": {
  "asOfDate": "2026-11-17",
  "firstMaturityMonth": "2027-03-01",
  "lockedAmount": 0, "lockedAccounts": 0,
  "maturedRemainingAmount": 0,
  "canceledRemainingAmount": 0, "canceledAccounts": 0,
  "lotsWithoutLeftAmount": 0,
  "excludedClosedPaidAmount": 0,
  "months": [
    { "maturityMonth": "2027-03-01", "lots": 0, "accounts": 0, "paidAmount": 0, "remainingAmount": 0, "canceledRemainingAmount": 0 }
  ],
  "basis": "..."
}
```
- **대상**: `krw_exchange.settlement_status = 'FINALIZED'`, `final_at`이 있고 **업무시계 날짜(`clockDate`) 미만**인 건. 다른 관리자 API와 같은 날짜 단위 상한이다. `left_amount`는 `LEFT JOIN`이라, 정상 흐름에서는 확정산 때 함께 생기지만 없는 건이 있어도 확정 원금이 빠지지 않는다(남은 원금은 0으로 센다. 개수는 `lotsWithoutLeftAmount`).
- **해지 계좌 제외**: `account.status <> 'CLOSED'`. `CLOSURE_REQUESTED`(해지 신청 중)는 잔액이 아직 묶여 있어 포함한다. 제외한 계좌의 확정 원금 합은 `excludedClosedPaidAmount`로 따로 낸다.
- **만기월** = `final_at` + 1년의 월(`YEAR(final_at) + 1`, `MONTH(final_at)`)의 1일. `months`는 만기월 오름차순이고 `firstMaturityMonth`는 첫 원소다(대상이 없으면 `null`, `months`는 `[]`).
- **묶임·만기 분류**는 업무시각 기준이다. 서비스가 `lockBoundary = 업무시각 - 1년`을 넘기고, 묶임 = `final_at > lockBoundary`, 만기 = `final_at <= lockBoundary`이다. 기존 인출 판정(`final_at.plusYears(1).isAfter(now)`)과 같은 뜻이다. **2/29에 확정된 건만** 기준일 계산(다음 해 2/28 vs `minusYears(1)`)이 달라 하루 어긋날 수 있다.
- **혜택이 취소된 계좌는 따로 센다**(`account.benefit = 'IMPOSSIBLE'`): 혜택이 취소된 계좌의 잔액은 1년 유지 조건과 무관해서 묶임·만기 집계에서 뺀다. `benefit`이 `NULL`이면 취소가 아닌 것으로 본다.
  - `lockedAmount` = **혜택이 살아 있는** 계좌 중 묶인 건의 `left_amount.cur_amount` 합, `lockedAccounts` = 그런 계좌 중 묶인 잔액이 **남아 있는** 계좌 수, `maturedRemainingAmount` = 혜택이 살아 있는 계좌 중 이미 만기가 지났지만 아직 남은 잔액.
  - `canceledRemainingAmount` = 혜택이 취소된 계좌의 남은 원금 합(**묶임·만기와 상관없이 전부**), `canceledAccounts` = 남은 원금이 있는 혜택 취소 계좌 수.
- `lots`, `accounts`, `paidAmount`는 **전량 인출된 건(`cur_amount = 0`)과 혜택 취소 계좌의 건도 포함**한다. `paidAmount`는 `final_amount` 합(전체)이다. `remainingAmount`는 **혜택이 취소되지 않은** 계좌의 `cur_amount` 합, `canceledRemainingAmount`는 **혜택이 취소된** 계좌의 `cur_amount` 합이다.
- `lotsWithoutLeftAmount` = `left_amount`가 없는 대상 건 수(해지 계좌의 건은 제외). 정상이면 0이다. 0이 아니면 데이터를 확인해야 한다.
- 소규모 칸 마스킹(§1)은 적용하지 않는다. 월·건 단위 집계이고 개인 속성이 없으며, 마스킹하면 합계 검산이 깨진다.
- **검산 관계** (매퍼 테스트로 확인): ① `months[].paidAmount` 합 = 대상 건의 `final_amount` 합, ② `lockedAmount + maturedRemainingAmount` = `months[].remainingAmount` 합, ③ `lockedAccounts` ≤ 대상 계좌 수, ④ `months[].paidAmount` 합 + `excludedClosedPaidAmount` = `/overview`의 `inflowTotal`(기간 `ALL`, `left_amount`가 없는 건이 있어도 성립), ⑤ `canceledRemainingAmount` = `months[].canceledRemainingAmount` 합, ⑥ `lockedAmount + maturedRemainingAmount + canceledRemainingAmount` = 대상 건의 `cur_amount` 합.
- 1년은 기존 코드처럼 `plusYears(1)` 기준이며 `tax_rule.HOLDING_PERIOD`를 읽지 않는다.

### 2-11. `GET /api/admin/fund-flow/early-withdrawal` — 1년 미경과 원금 인출 비율

계좌가 1년이 되기 전에 원금을 인출한 정도를 분모(확정 원금)와 분자(인출액)로 본다. 쿼리 파라미터는 없다(기간 선택 없음, 업무시계 기준 전체).

```json
"data": {
  "asOfDate": "2026-11-17",
  "finalizedAccounts": 0, "finalizedAmount": 0,
  "earlyAccounts": 0, "earlyWithdrawnAmount": 0,
  "accountShare": 0.0000, "amountShare": 0.0000,
  "byCause": [
    { "cause": "CLOSURE_FORCED", "label": "해지 강제 인출", "accounts": 0, "amount": 0 },
    { "cause": "OTHER", "label": "그 밖의 인출", "accounts": 0, "amount": 0 }
  ],
  "basis": "..."
}
```
- **분모**: `krw_exchange.settlement_status = 'FINALIZED'`, `final_at`이 있고 업무시계 날짜(`clockDate`) 미만인 환전의 계좌 수(`finalizedAccounts`)와 `final_amount` 합(`finalizedAmount`). **해지(CLOSED) 계좌도 포함**한다(1년 미경과 인출은 해지 강제 인출이 주원인일 수 있어서). 계좌·`left_amount` 조인이 없으므로 `/overview`의 `inflowTotal`(기간 `ALL`)과 같은 조건이다.
- **분자**: **분모 대상 환전에서 나온 인출만** 센다. `withdrawal_allocation.type = 'IMMATURE_PRINCIPAL_INCLUDED'`이고 인출이 완료(`withdrawal.status = 'COMPLETED'`)이며 `withdrawal_at < clockDate`인 배분이다. 조인은 retention과 같은 경로(`withdrawal_allocation` → `left_amount` → `krw_exchange`)이고, 그 환전이 분모 조건(FINALIZED, `final_at < clockDate`)을 만족해야 한다. 그래서 `earlyAccounts ≤ finalizedAccounts`가 항상 성립한다.
- **분자는 인출액이고 분모는 확정 원금**이다. `amountShare`는 "확정 원금 중 1년 전에 인출된 비중"으로 읽는다(수익금 배분은 제외).
- **비율**: `accountShare = earlyAccounts ÷ finalizedAccounts`, `amountShare = earlyWithdrawnAmount ÷ finalizedAmount`. 기존 비율 필드와 같은 규칙(소수 4자리 HALF_UP, 분모가 0이면 `null`)이다.
- **원인(`byCause`)**: 그 인출이 **완료된 해지 요청**(`account_closure_request.status = 'COMPLETED'`, `withdrawal_id`가 그 인출)에 연결돼 있으면 `CLOSURE_FORCED`, 아니면 `OTHER`. 두 원인을 항상 이 순서로 내고 값이 없는 원인은 0으로 채운다. 한 계좌가 두 원인에 걸칠 수 있어서 **원인별 `accounts`의 합은 `earlyAccounts`보다 클 수 있고**, `amount`의 합은 `earlyWithdrawnAmount`와 같다.
- 취소(`CANCELLED`)·실패(`FAILED`)·요청 중(`REQUESTED`) 인출은 센다고 하지 않는다. 현재 코드에서는 이 상태의 인출에 배분 행이 만들어지지 않지만(`WithdrawalProcessor`는 한 트랜잭션에서 배분 후 `COMPLETED`로 바꾸고, 실패는 인출 행만 남긴다), 쿼리에서 상태를 명시해 거른다. 기존 `/retention` 쿼리는 상태를 거르지 않는다.
- **검산 관계** (매퍼 테스트로 확인): ① `finalizedAmount` = `/overview`의 `inflowTotal`, ② `byCause[].amount` 합 = `earlyWithdrawnAmount`이고 `byCause[].accounts` 합 ≥ `earlyAccounts`, ③ `earlyAccounts` ≤ `finalizedAccounts`.

---

## 3. 고객: 자금 환류 현황

고객에게는 쉬운 말과 한 화면 한 메시지로 보여준다. 응답 필드는 서버가 계산해서 주고, 화면은 표시만 한다. 계좌가 없는 고객(미개설)은 `404`가 아니라 `hasAccount: false`로 응답한다.

### 3-1. `GET /api/customer/fund-flow/summary` — 내 자금 흐름 요약

```json
"data": {
  "asOfDate": "2026-11-17",
  "hasAccount": true,
  "soldForeignAmount": 0,
  "krwInflowAmount": 0,
  "domesticBuyAmount": 0,
  "domesticBuyFeeAmount": 0,
  "cashBalance": 0,
  "reinvestRatio": 0.0000,
  "message": "해외주식을 판 돈의 OO%를 다시 국내에 투자했어요",
  "basis": "원화로 확정된 금액 중 국내 매수에 쓴 비율"
}
```
- `soldForeignAmount`는 해외 매도, `krwInflowAmount`는 환전 확정액, `domesticBuyAmount`는 매수 체결금액(수수료 제외 원가)이다.
- `domesticBuyFeeAmount`는 국내 매수 수수료 합계다. 예수금은 `원화 확정 − 국내 매수 − 수수료 + 국내 매도 − 인출`로 맞는다. 국내 매도 금액은 수수료·세금을 뺀 값이다.
- 확정 유입이 0이면 `reinvestRatio`는 `null`이고 `message`는 "아직 원화로 확정된 금액이 없어요"다. 계좌가 없으면 `hasAccount: false`, `message`는 "아직 RIA 계좌가 없어요"다.

### 3-2. `GET /api/customer/fund-flow/monthly` — 내 월별 흐름

쿼리: 없음 (본인 계좌의 `fund_flow_monthly` 전체, 월 오름차순). 업무시계가 속한 달은 원천 테이블에서 집계해 `partial: true`로 덧붙이고, 이때 월초·월말 예수금은 `null`이다(업무시계가 1일이면 생략).

```json
"data": {
  "asOfDate": "2026-11-17",
  "months": [
    { "flowMonth": "2026-04-01", "foreignSellAmount": 0, "krwInflowAmount": 0,
      "domesticBuyAmount": 0, "domesticSellAmount": 0, "withdrawalAmount": 0,
      "cashBalanceBegin": 0, "cashBalanceEnd": 0, "partial": false }
  ],
  "basis": "..."
}
```

### 3-3. `GET /api/customer/fund-flow/relief` — 내 예상 세금 혜택

```json
"data": {
  "asOfDate": "2026-11-17",
  "hasAccount": true,
  "benefit": "POSSIBLE",
  "statusCode": "PARTIAL_REDUCED",
  "statusLabel": "다른 계좌의 해외주식 매수로 혜택이 일부 줄었어요",
  "weightedSell": 0, "adjustRatio": 0.0000,
  "finalDeduction": 0, "finalTax": 0,
  "generalTax": 0, "reliefAmount": 0,
  "calculatedAt": "2026-11-17T02:00:00",
  "notice": "표시용 예상값이며 확정 세액은 확정신고 시 계산됩니다"
}
```
- `statusCode`는 §2-4의 5분류와 같은 코드다. 내 계좌가 어디에 속하는지 보여준다.
- `reliefAmount` 계산은 §2-6과 같은 공식이다(`generalTax − finalTax`). 스냅샷이 아직 없으면 분류·감면액은 `null`이고 `statusLabel`은 "아직 계산된 예상 혜택이 없어요"다.

### 3-4. `GET /api/customer/fund-flow/allocation` — 내 운용처

평가액이 아니라 **매입금액 기준**이다(§2-7과 같은 이유).

```json
"data": {
  "asOfDate": "2026-11-17",
  "hasAccount": true,
  "valueBasis": "PURCHASE_COST",
  "holdingsCost": 0,
  "cashBalance": 0,
  "totalValue": 0,
  "buckets": [ { "label": "국내 주식", "value": 0, "share": 0.0000 },
               { "label": "ETF·펀드", "value": 0, "share": 0.0000 },
               { "label": "예수금",   "value": 0, "share": 0.0000 } ],
  "sectors": [ { "label": "제약", "value": 0, "share": 0.0000 } ],
  "basis": "매입금액 기준(보유수량 × 평균 매입가). 현재가 평가액이 아니에요"
}
```
- `buckets.share`는 `totalValue`(보유 종목 매입금액 + 예수금) 대비, `sectors.share`는 `holdingsCost` 대비다.
- `sectors`는 주식 업종 상위 3이며 업종이 없으면 `기타`로 묶는다. 본인 `OPENED` 계좌의 보유(`qty > 0`)만 센다.

### 3-5. `GET /api/customer/fund-flow/transactions` — 내 자금 이동 내역

고객 "자금 이동 내역" 타임라인용. 원화 확정·국내 매도·국내 매수·인출을 거래 후 예수금과 함께 시간순으로 준다. 계좌 ID는 받지 않고 로그인 고객의 개설 계좌만 조회한다.

```json
"data": {
  "asOfDate": "2026-11-18",
  "hasAccount": true,
  "items": [
    { "date": "2026-10-23", "type": "KRW_CONFIRMED", "amount": 47016155, "cashAfter": 47016155,
      "title": null, "details": [] },
    { "date": "2026-10-23", "type": "DOMESTIC_BUY", "amount": -7295291, "cashAfter": 39720864,
      "title": "삼성전자 외 1",
      "details": [ { "name": "삼성전자", "qty": 22, "amount": 4741000, "feeAmount": 189, "taxAmount": 0 } ] }
  ],
  "basis": "..."
}
```
- `items`는 **오래된 순**이다(화면은 뒤집어서 최신순으로 쓴다). 계좌가 없으면 `hasAccount: false`, `items: []`.
- 같은 날·같은 구분은 한 줄로 합친다(쪼개진 원화 확정은 일자별 합계, 국내 매수·매도는 종목별로 합친 `details`와 대표 `title` "OO 외 N"). 대표 종목은 금액이 큰 종목이다.
- `amount`는 **예수금 변동액**(+입금/−출금)이다. 국내 매수는 수수료 포함, 국내 매도는 수수료·거래세를 뺀 순액이라 `/summary`의 예수금 계산식과 같다. `details.amount`는 체결금액(수수료 제외)이다.
- 같은 날 처리 순서는 `KRW_CONFIRMED → DOMESTIC_SELL → DOMESTIC_BUY → WITHDRAWAL`로 고정이고, `cashAfter`는 이 순서로 누적한다.
- 집계 기준은 `/summary`와 같다(업무시계 날짜 미만, 확정된 원화·체결된 주문·완료된 인출). 국내 주문은 `stock_package_id`·`channel`로 거르지 않는다(생성기 체결 포함). 마지막 `cashAfter`는 `/summary`의 `cashBalance`와 같아야 한다.

### 3-6. `GET /api/customer/fund-flow/withdrawal-schedule` — 내 인출 가능 일정

고객 "인출 가능 일정" 타임라인(간트)용. 납입일(원화 환전 대금결제일)별 합계를 준다. 계좌 ID는 받지 않는다. 인출 패키지 코드는 읽기만 하고 고치지 않는다.

```json
"data": {
  "businessDate": "2026-11-19",
  "hasAccount": true,
  "lots": [
    { "paidDate": "2026-04-28", "maturityDate": "2027-04-28", "paidAmount": 30000000,
      "withdrawnAmount": 0, "remainingAmount": 30000000, "matured": false }
  ],
  "basis": "..."
}
```
- `lots`는 납입일 오름차순이고 같은 납입일(`krw_exchange.final_at`의 날짜)은 합친다. 인출 가능일은 납입일 + 1년이다.
- `paidAmount`는 처음 납입액(`krw_exchange.final_amount`), `remainingAmount`는 인출 후 남은 원금(`left_amount.cur_amount`), `withdrawnAmount`는 FIFO 배분 합계(`withdrawal_allocation.left_amount_id` 기준, 수익금 배분은 제외)다. 전량 인출된 건(`remainingAmount = 0`)도 포함한다.
- `matured`는 인출 처리와 같은 판정(`!final_at + 1년 > 업무시계 현재`)이다. 계좌가 없으면 `hasAccount: false`, `lots: []`.
- 남은 원금이 있는 건의 합계는 `GET /api/customer/withdrawals/availability`의 `leftAmounts` 합계와 같다(테스트로 대조). 화면의 "지금 인출 가능 금액·1년 유지 중인 납입금·다음 인출 가능일"은 그 API 값을 그대로 쓴다.

---

## 4. 구현 계획

패키지: `com.app.maria.domain.fundflow` (statistics 도메인과 같은 구조)

```
api/        AdminFundFlowApi, CustomerFundFlowApi
service/    AdminFundFlowService(+Impl), CustomerFundFlowService(+Impl)
mapper/     FundFlowMapper
dto/        *DTO (VO)            ← Mapper·Service 내부
dto/request/  FundFlowFilterRequestDTO
dto/response/ *ResponseDTO       ← VO를 받는 생성자
type/       LeakageCategory (enum)
resources/mappers/fundflow/FundFlowMapper.xml
```
- Service 매개변수는 Request DTO, 내부는 VO, 반환은 Response DTO로 통일한다.
- 5분류는 `LeakageCategory` enum으로 매핑한다.
- MyBatis 별칭이 패키지 스캔이면 DTO 클래스 이름이 프로젝트 안에서 겹치지 않아야 한다.
- 기준일은 `BusinessClockService`에서 가져온다.

### 구현 순서
1. 공통: enum, 필터 DTO, `asOfDate` 처리
2. 관리자: `funnel` → `leakage` → `monthly` → `overview` → `segments` → `relief` → `allocation`
3. 고객: `summary` → `monthly` → `relief` → `allocation`
4. 테스트: `statistics`와 같은 구조(api / mapper / service)로 최소 1개씩

### 검증 방법
- 각 API 값을 분석 결과(`SUMMARY.md`의 기준값)와 대조한다. 퍼널 6단계, 누수 5분류, 재투자율 0.707이 일치해야 한다.
- 시연 DB는 3306의 `maria-db`가 아니라 시드 컨테이너와 다를 수 있으므로, 대조는 사용자와 함께 값을 확인한다.

### 성능 주의
`leakage`, `segments`, `relief`는 개설 계좌 약 7.5만 건과 주문 원장을 조인한다. 첫 구현 후 실제 응답 시간을 재고, 느리면 결과 캐시나 인덱스를 검토한다.

---

## 5. 미확정 항목 (답이 오기 전까지의 가정)

| 항목 | 현재 가정 | 확인 대상 |
|---|---|---|
| 감면 1원당 분모 | `scope`로 둘 다 제공 (기본 `ALL`) | 팀 |
| `original_gain_amount` 범위 | 총 양도소득으로 가정, 잠정치로 표기 | 김민석 |
| 자산 규모 구간 | 이번 버전에서 제외 | 팀 |
| 정부 보고 형태 | 이번 버전에서 제외 (관리자 `overview`·`funnel`·`monthly`가 기초 자료) | 팀 |
| 11월 `fund_flow_monthly` 행 | 시드에는 2026-11 이후 행(~2027-03)도 있다. API는 `flow_month < 업무일이 속한 달의 1일`인 행만 월별 합계에 쓰고, 진행 중인 달은 원천 집계 + `partial=true`(`selectMonthlyClosed`, `selectMonthlyPartial`) | 해결 |
| 시연 DB의 확장 컬럼 | 모두 `maria/db/01_ria_ddl.sql`에 정의됨(§1-2 표)이고 seed-mariadb(3307)에도 있음(확인 완료). 앱이 보는 DB가 이 DDL로 만든 DB인지는 실행 환경에서 확인 | 사용자 |
| 종가(allocation) | 매입금액 기준으로 대체, 종가 API는 팀 합의 후 | 팀 |
