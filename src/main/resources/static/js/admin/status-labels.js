/* 관리자 화면 공통 상태·구분 표기 매핑 (A-1 상태값 매핑).
   다른 화면에서도 같은 표기를 쓰려면 이 파일을 pageScripts 에서 불러와 MARIA.status.* 를 쓴다.
   label = 화면에 보이는 한글, tone = 배지 색 (components.css 의 .adm-chip-ok / info / warn / fail / off 와 같은 이름). */
var MARIA = window.MARIA || {};
window.MARIA = MARIA;

MARIA.status = (function () {
    // 같은 사전에서 label / tone 을 꺼내는 공통 함수. 모르는 값은 원문을 그대로 돌려준다.
    function pick(map, key, fallback) {
        if (key === null || key === undefined || key === "") {
            return fallback;
        }
        var found = map[String(key).toUpperCase()];
        return found === undefined ? String(key) : found;
    }

    // 세제혜택 (ACCOUNT.benefit): NULL 은 아직 판정 전이라 "대기" (API 는 NULL 을 UNCLASSIFIED 로 내려줌)
    var BENEFIT_LABEL = {
        POSSIBLE: "적용",
        REDUCED: "일부 감액",
        IMPOSSIBLE: "제외",
        UNCLASSIFIED: "대기"
    };
    var ACCOUNT_STATUS_LABEL = {
        APPLIED: "심사 대기",
        OPENED: "개설 완료",
        REJECTED: "반려",
        CLOSURE_REQUESTED: "해지 접수",
        CLOSED: "해지"
    };
    var ACCOUNT_STATUS_TONE = {
        APPLIED: "info",
        OPENED: "ok",
        REJECTED: "warn",
        CLOSURE_REQUESTED: "warn",
        CLOSED: "off"
    };
    var ACCOUNT_CLOSURE_STATUS_LABEL = {
        REQUESTED: "해지 접수",
        COMPLETED: "해지 완료",
        REJECTED: "해지 반려"
    };
    var ACCOUNT_CLOSURE_STATUS_TONE = {
        REQUESTED: "info",
        COMPLETED: "ok",
        REJECTED: "fail"
    };

    // 종목 구분 (StockType): 원본 화면 표기(해외주식 / 해외ETF / 해외펀드)에 맞춘다
    var STOCK_TYPE_LABEL = {
        FOREIGN_STOCK: "해외주식",
        ETF: "해외ETF",
        ETN: "해외ETN",
        FUND: "해외펀드"
    };

    // 거래 유형 (TradeType): 상속·증여는 세법상 매수와 같게 보므로 배지 색도 매수(info)와 같다
    var TRADE_TYPE_LABEL = {
        BUY: "매수",
        SELL: "매도",
        INHERITANCE: "상속",
        GIFT: "증여"
    };
    var TRADE_TYPE_TONE = {
        BUY: "info",
        SELL: "warn",
        INHERITANCE: "info",
        GIFT: "info"
    };

    // 국내 보유종목 상태 (DomesticStockStatus)
    var DOMESTIC_STATUS_LABEL = {
        HOLDING: "보유중",
        PARTIALLY_SOLD: "일부매도",
        SOLD_OUT: "전량매도",
        TRADE_RESTRICTED: "거래제한",
        TRADE_SUSPENDED: "거래정지",
        TERMINATED: "보유종료"
    };
    var DOMESTIC_STATUS_TONE = {
        HOLDING: "ok",
        PARTIALLY_SOLD: "info",
        SOLD_OUT: "off",
        TRADE_RESTRICTED: "warn",
        TRADE_SUSPENDED: "fail",
        TERMINATED: "off"
    };

    // 국내 상품 구분 (DomesticProduct.Type)
    var DOMESTIC_TYPE_LABEL = {
        STOCK: "국내주식",
        FUND: "펀드"
    };

    // 매도 주문 상태 (SellOrderStatus) 와 정산 상태 (SettlementStatus).
    // 화면에는 둘을 합친 "처리 단계"(주문 / 체결 / 원화 확정 / 실패) 하나로 보여 준다 — 원본 매도 · 환전 화면 표기.
    var SELL_ORDER_STATUS_LABEL = {
        RECEIVED: "접수",
        EXECUTED: "체결",
        REJECTED: "거부"
    };
    var SETTLEMENT_STATUS_LABEL = {
        PROVISIONAL: "가환전",
        FINALIZED: "확정산"
    };
    var SELL_STAGE_LABEL = {
        ORDER: "주문",
        EXECUTED: "체결",
        CONFIRMED: "원화 확정",
        FAILED: "실패"
    };
    var SELL_STAGE_TONE = {
        ORDER: "info",
        EXECUTED: "info",
        CONFIRMED: "ok",
        FAILED: "fail"
    };

    // 처리 단계 정하기: 거부=실패, 접수=주문, 체결 + (확정산이거나 확정액이 있음)=원화 확정, 그 밖의 체결=체결(가환전)
    function sellStage(status, settlementStatus, finalAmount) {
        var s = String(status || "").toUpperCase();
        if (s === "REJECTED") {
            return "FAILED";
        }
        if (s === "RECEIVED") {
            return "ORDER";
        }
        if (s === "EXECUTED") {
            var settled = String(settlementStatus || "").toUpperCase() === "FINALIZED" ||
                (finalAmount !== null && finalAmount !== undefined);
            return settled ? "CONFIRMED" : "EXECUTED";
        }
        return null;
    }

    // 국내투자 계좌의 매수가능 여부 (계좌 단위). 보유종목 상태(DomesticStockStatus)에서 정한다:
    // 거래정지 종목이 있으면 거래정지, 거래제한 종목이 있으면 거래제한, 둘 다 없으면 매수가능.
    // 목록 응답에는 거래제한·정지 보유 여부(boolean)만 있어 둘을 가르지 못하므로 RESTRICTED_ANY("거래제한·정지")를 쓴다.
    var BUYABLE_LABEL = {
        AVAILABLE: "매수가능",
        RESTRICTED: "거래제한",
        SUSPENDED: "거래정지",
        RESTRICTED_ANY: "거래제한·정지"
    };
    var BUYABLE_TONE = {
        AVAILABLE: "ok",
        RESTRICTED: "warn",
        SUSPENDED: "fail",
        RESTRICTED_ANY: "warn"
    };
    function domesticBuyable(holdings) {
        var list = holdings || [];
        var has = function (status) {
            return list.some(function (h) { return String(h.status).toUpperCase() === status; });
        };
        return has("TRADE_SUSPENDED") ? "SUSPENDED" : has("TRADE_RESTRICTED") ? "RESTRICTED" : "AVAILABLE";
    }

    // 관리자 역할 (AdminRole). 설명은 원본 "역할 변경" 화면 글.
    var ADMIN_ROLE_LABEL = {
        VIEWER: "조회전용",
        REVIEWER: "심사담당",
        SETTLEMENT: "정산담당",
        ADMIN: "최고관리자"
    };
    var ADMIN_ROLE_TONE = {
        VIEWER: "off",
        REVIEWER: "info",
        SETTLEMENT: "info",
        ADMIN: "ok"
    };
    var ADMIN_ROLE_DESC = {
        VIEWER: "전 화면 조회만 가능",
        REVIEWER: "계좌·입고·해지 승인/반려",
        SETTLEMENT: "정산·세액 배치 실행/확정",
        ADMIN: "전체 + 업무시각·역할 변경"
    };

    // 정산 배치 상태 (BatchStatus) + 건수. 화면에는 실행 중 / 완료 / 부분 실패 / 배치 실패 네 가지로 보여 준다 — 원본 표기.
    // 서버는 실패 항목이 하나라도 남아 있으면 배치를 FAILED 로 둔다(재처리까지 끝나 실패 0 이어야 COMPLETED — refreshBatchStatusAfterRetry).
    // 그래서 부분 실패 = 성공 항목이 있는 FAILED (또는 COMPLETED 인데 실패 건수가 남은 경우), 배치 실패 = 성공 항목이 하나도 없는 FAILED.
    var SETTLEMENT_BATCH_LABEL = {
        RUNNING: "실행 중",
        COMPLETED: "완료",
        PARTIAL: "부분 실패",
        FAILED: "배치 실패"
    };
    var SETTLEMENT_BATCH_TONE = {
        RUNNING: "info",
        COMPLETED: "ok",
        PARTIAL: "warn",
        FAILED: "fail"
    };
    function settlementBatchKind(batch) {
        var status = String(batch.status || "").toUpperCase();
        var failed = Number(batch.failedCount || 0);
        if (status === "COMPLETED") {
            return failed > 0 ? "PARTIAL" : "COMPLETED";
        }
        if (status === "FAILED") {
            return Number(batch.successCount || 0) > 0 ? "PARTIAL" : "FAILED";
        }
        return status === "RUNNING" ? status : null;
    }

    // 정산 항목 결과 (SettlementItemResult)
    var SETTLEMENT_ITEM_RESULT_LABEL = {
        SUCCESS: "성공",
        FAILED: "실패"
    };
    var SETTLEMENT_ITEM_RESULT_TONE = {
        SUCCESS: "ok",
        FAILED: "fail"
    };

    // 정산 실패 코드 (SettlementFailureCode) 9종 → 한글 사유
    var SETTLEMENT_FAILURE_LABEL = {
        EXCHANGE_RATE_NOT_FOUND: "환율 정보를 찾을 수 없음",
        EXTERNAL_API_ERROR: "외부 API 오류",
        EXCHANGE_NOT_FOUND: "환전 내역을 찾을 수 없음",
        INVALID_ORDER_STATUS: "주문 상태가 올바르지 않음",
        ACCOUNT_NOT_FOUND: "계좌를 찾을 수 없음",
        ACCOUNT_MISMATCH: "환전·계좌 정보 불일치",
        CALCULATION_ERROR: "정산 금액 계산 오류",
        DB_STATE_CONFLICT: "데이터 상태 충돌",
        UNKNOWN_ERROR: "알 수 없는 오류"
    };

    // 입고 lot 의 출처 계좌 종류 (GeneralAccountType)
    var GENERAL_ACCOUNT_TYPE_LABEL = {
        BROKERAGE: "종합위탁계좌",
        CMA: "CMA",
        IRP: "IRP",
        PENSION_SAVINGS: "연금저축",
        ISA: "ISA"
    };

    // 입고 0주 승인 사유 (InboundZeroApprovalReason). 목록·상세 응답에는 사유가 없어(요청 #56) 화면이 수량으로 추정할 때 쓴다.
    var INBOUND_ZERO_REASON_LABEL = {
        CURRENT_HOLDING_INSUFFICIENT: "요청 시점 기준 현재 보유수량이 0주라 입고할 자산이 없습니다.",
        REQUESTED_ZERO: "신청수량 자체가 0주로 접수됐습니다.",
        SNAPSHOT_QUANTITY_EXHAUSTED: "이 계좌·종목으로 이미 승인된 누적수량이 12.23 기준수량을 다 채웠습니다."
    };

    // 인출 처리 상태 (WithdrawalStatus). CANCELLED 는 목록에서 빼지만 매핑은 둔다.
    var WITHDRAWAL_STATUS_LABEL = {
        REQUESTED: "처리 중",
        COMPLETED: "완료",
        CANCELLED: "취소",
        FAILED: "실패"
    };
    var WITHDRAWAL_STATUS_TONE = {
        REQUESTED: "info",
        COMPLETED: "ok",
        CANCELLED: "off",
        FAILED: "fail"
    };

    // 인출 구분 (WithdrawalType = 납입건 배분 하나의 구분).
    // 표·상세의 "인출 구분" 배지는 원본대로 정상 인출 / 조기 인출 두 가지이고, 아래 긴 이름은 배지 툴팁과 배분 바 이름에 쓴다.
    var WITHDRAWAL_TYPE_LABEL = {
        EARNINGS_ONLY: "수익금",
        MATURED_PRINCIPAL_INCLUDED: "만기 원금 포함",
        IMMATURE_PRINCIPAL_INCLUDED: "조기 인출(미경과 원금 포함)"
    };
    // 배분 바 오른쪽 배지 (원본: 인출 가능 / 조기)
    var WITHDRAWAL_ALLOC_LABEL = {
        EARNINGS_ONLY: "수익금",
        MATURED_PRINCIPAL_INCLUDED: "인출 가능",
        IMMATURE_PRINCIPAL_INCLUDED: "조기"
    };
    var WITHDRAWAL_TYPE_TONE = {
        EARNINGS_ONLY: "ok",
        MATURED_PRINCIPAL_INCLUDED: "ok",
        IMMATURE_PRINCIPAL_INCLUDED: "warn"
    };
    var WITHDRAWAL_KIND_LABEL = {
        NORMAL: "정상 인출",
        EARLY: "조기 인출"
    };
    var WITHDRAWAL_KIND_TONE = {
        NORMAL: "ok",
        EARLY: "warn"
    };

    // 인출 한 건의 구분 정하기 (목록 응답에는 배분 구분별 금액·건수만 있다):
    // 미경과 원금이 있으면 조기(IMMATURE), 아니면 배분이 있을 때 정상(만기 원금 있으면 MATURED, 없으면 수익금만),
    // 배분이 하나도 없으면(처리 중·실패) 알 수 없어 null.
    function withdrawalType(item) {
        var immature = Number(item.immaturePrincipalAmount || 0);
        var matured = Number(item.maturedPrincipalAmount || 0);
        if (immature > 0 || Number(item.earlyAllocationCount || 0) > 0) {
            return "IMMATURE_PRINCIPAL_INCLUDED";
        }
        if (Number(item.allocationCount || 0) <= 0 && Number(item.earningsAmount || 0) <= 0 && matured <= 0) {
            return null;
        }
        return matured > 0 ? "MATURED_PRINCIPAL_INCLUDED" : "EARNINGS_ONLY";
    }
    function withdrawalKind(item) {
        var type = withdrawalType(item);
        return type === null ? null : type === "IMMATURE_PRINCIPAL_INCLUDED" ? "EARLY" : "NORMAL";
    }

    // 자금 환류 혜택 누수 5분류 (LeakageCategory 코드). 화면 요약의 "정상 / 부분 축소 / 취소" 로 묶는 기준:
    // 매도 없음은 혜택을 판정할 대상이 아니라 비율의 분모에서 뺀다. "일부 감액"(계좌의 REDUCED)과는 다른 말이라 쓰지 않는다.
    var LEAKAGE_LABEL = {
        NO_SELL: "매도 없음",
        BENEFIT_CANCELED: "혜택 취소",
        NO_IMPACT: "영향 없음",
        PARTIAL_REDUCED: "부분 축소",
        EXTERNAL_BUY_OFFSET: "순매수 소멸"
    };
    var LEAKAGE_GROUP = {
        NO_SELL: "excluded",
        NO_IMPACT: "normal",
        PARTIAL_REDUCED: "reduced",
        BENEFIT_CANCELED: "canceled",
        EXTERNAL_BUY_OFFSET: "canceled"
    };
    var LEAKAGE_GROUP_LABEL = {
        normal: "정상",
        reduced: "부분 축소",
        canceled: "취소",
        excluded: "판정 제외"
    };

    // 투자성향 (InvestorType). 자금 환류 연령대 × 투자성향 히트맵 열 이름. 값 순서 = 위험 선호가 낮은 쪽 → 높은 쪽, 미진단은 맨 끝
    var INVESTOR_TYPE_LABEL = {
        STABLE: "안정형",
        CONSERVATIVE: "안정추구형",
        NEUTRAL: "위험중립형",
        ACTIVE: "적극투자형",
        AGGRESSIVE: "공격투자형",
        UNASSIGNED: "미진단"
    };
    var INVESTOR_TYPE_ORDER = ["STABLE", "CONSERVATIVE", "NEUTRAL", "ACTIVE", "AGGRESSIVE", "UNASSIGNED"];

    return {
        WITHDRAWAL_STATUS_LABEL: WITHDRAWAL_STATUS_LABEL,
        WITHDRAWAL_TYPE_LABEL: WITHDRAWAL_TYPE_LABEL,
        WITHDRAWAL_KIND_LABEL: WITHDRAWAL_KIND_LABEL,
        withdrawalType: withdrawalType,
        withdrawalKind: withdrawalKind,
        withdrawalStatusLabel: function (status) {
            return pick(WITHDRAWAL_STATUS_LABEL, status, "-");
        },
        withdrawalStatusTone: function (status) {
            return WITHDRAWAL_STATUS_TONE[String(status).toUpperCase()] || "off";
        },
        withdrawalTypeLabel: function (type) {
            return pick(WITHDRAWAL_TYPE_LABEL, type, "-");
        },
        withdrawalTypeTone: function (type) {
            return WITHDRAWAL_TYPE_TONE[String(type).toUpperCase()] || "off";
        },
        withdrawalAllocLabel: function (type) {
            return pick(WITHDRAWAL_ALLOC_LABEL, type, "-");
        },
        withdrawalKindLabel: function (kind) {
            return WITHDRAWAL_KIND_LABEL[kind] || "-";
        },
        withdrawalKindTone: function (kind) {
            return WITHDRAWAL_KIND_TONE[kind] || "off";
        },
        SETTLEMENT_BATCH_LABEL: SETTLEMENT_BATCH_LABEL,
        settlementBatchKind: settlementBatchKind,
        settlementBatchLabel: function (kind) {
            return SETTLEMENT_BATCH_LABEL[kind] || "-";
        },
        settlementBatchTone: function (kind) {
            return SETTLEMENT_BATCH_TONE[kind] || "off";
        },
        settlementItemResultLabel: function (result) {
            return pick(SETTLEMENT_ITEM_RESULT_LABEL, result, "-");
        },
        settlementItemResultTone: function (result) {
            return SETTLEMENT_ITEM_RESULT_TONE[String(result).toUpperCase()] || "off";
        },
        SETTLEMENT_FAILURE_LABEL: SETTLEMENT_FAILURE_LABEL,
        // 실패 코드 → 한글 (코드가 없으면 서버 메시지, 둘 다 없으면 "-")
        settlementFailureLabel: function (code, message) {
            return pick(SETTLEMENT_FAILURE_LABEL, code, message || "-");
        },
        GENERAL_ACCOUNT_TYPE_LABEL: GENERAL_ACCOUNT_TYPE_LABEL,
        generalAccountTypeLabel: function (type) {
            return pick(GENERAL_ACCOUNT_TYPE_LABEL, type, "-");
        },
        INBOUND_ZERO_REASON_LABEL: INBOUND_ZERO_REASON_LABEL,
        // 입고 0주 승인 사유 추정: 옛 화면과 같은 순서 (현재 보유 0 → 신청 0 → 그 밖에는 기준수량 소진)
        inboundZeroReason: function (item) {
            if (item.currentHoldingAtRequest === 0) {
                return "CURRENT_HOLDING_INSUFFICIENT";
            }
            if (item.requestedQty === 0) {
                return "REQUESTED_ZERO";
            }
            return "SNAPSHOT_QUANTITY_EXHAUSTED";
        },
        inboundZeroReasonLabel: function (reason) {
            return INBOUND_ZERO_REASON_LABEL[reason] || "-";
        },
        BENEFIT_LABEL: BENEFIT_LABEL,
        ADMIN_ROLE_LABEL: ADMIN_ROLE_LABEL,
        ADMIN_ROLE_TONE: ADMIN_ROLE_TONE,
        ADMIN_ROLE_DESC: ADMIN_ROLE_DESC,
        adminRoleLabel: function (role) {
            return pick(ADMIN_ROLE_LABEL, role, "-");
        },
        adminRoleTone: function (role) {
            return ADMIN_ROLE_TONE[String(role).toUpperCase()] || "off";
        },
        BUYABLE_LABEL: BUYABLE_LABEL,
        BUYABLE_TONE: BUYABLE_TONE,
        domesticBuyable: domesticBuyable,
        buyableLabel: function (key) {
            return BUYABLE_LABEL[key] || "-";
        },
        buyableTone: function (key) {
            return BUYABLE_TONE[key] || "off";
        },
        SELL_ORDER_STATUS_LABEL: SELL_ORDER_STATUS_LABEL,
        SETTLEMENT_STATUS_LABEL: SETTLEMENT_STATUS_LABEL,
        SELL_STAGE_LABEL: SELL_STAGE_LABEL,
        SELL_STAGE_TONE: SELL_STAGE_TONE,
        sellStage: sellStage,
        sellOrderStatusLabel: function (status) {
            return pick(SELL_ORDER_STATUS_LABEL, status, "-");
        },
        settlementStatusLabel: function (status) {
            return pick(SETTLEMENT_STATUS_LABEL, status, "-");
        },
        sellStageLabel: function (stage) {
            return SELL_STAGE_LABEL[stage] || "-";
        },
        sellStageTone: function (stage) {
            return SELL_STAGE_TONE[stage] || "off";
        },
        STOCK_TYPE_LABEL: STOCK_TYPE_LABEL,
        TRADE_TYPE_LABEL: TRADE_TYPE_LABEL,
        TRADE_TYPE_TONE: TRADE_TYPE_TONE,
        DOMESTIC_STATUS_LABEL: DOMESTIC_STATUS_LABEL,
        DOMESTIC_STATUS_TONE: DOMESTIC_STATUS_TONE,
        DOMESTIC_TYPE_LABEL: DOMESTIC_TYPE_LABEL,

        benefitLabel: function (benefit) {
            return pick(BENEFIT_LABEL, benefit, BENEFIT_LABEL.UNCLASSIFIED);
        },
        // 세제혜택 배지 톤: 적용 ok / 일부 감액 warn / 제외 off / 대기 info (원본 배지 색)
        INVESTOR_TYPE_LABEL: INVESTOR_TYPE_LABEL,
        INVESTOR_TYPE_ORDER: INVESTOR_TYPE_ORDER,
        investorTypeLabel: function (type) {
            return pick(INVESTOR_TYPE_LABEL, type, "-");
        },
        LEAKAGE_LABEL: LEAKAGE_LABEL,
        leakageLabel: function (code) {
            return pick(LEAKAGE_LABEL, code, "-");
        },
        leakageGroup: function (code) {
            return LEAKAGE_GROUP[String(code).toUpperCase()] || "excluded";
        },
        leakageGroupLabel: function (group) {
            return LEAKAGE_GROUP_LABEL[group] || "-";
        },
        benefitTone: function (benefit) {
            var key = String(benefit == null ? "" : benefit).toUpperCase();
            return key === "POSSIBLE" ? "ok" : key === "REDUCED" ? "warn" : key === "IMPOSSIBLE" ? "off" : "info";
        },
        accountStatusLabel: function (status) {
            return pick(ACCOUNT_STATUS_LABEL, status, "-");
        },
        accountStatusTone: function (status) {
            return ACCOUNT_STATUS_TONE[String(status || "").toUpperCase()] || "off";
        },
        accountClosureStatusLabel: function (status) {
            return pick(ACCOUNT_CLOSURE_STATUS_LABEL, status, "-");
        },
        accountClosureStatusTone: function (status) {
            return ACCOUNT_CLOSURE_STATUS_TONE[String(status || "").toUpperCase()] || "off";
        },
        stockTypeLabel: function (type) {
            return pick(STOCK_TYPE_LABEL, type, "-");
        },
        tradeTypeLabel: function (type) {
            return pick(TRADE_TYPE_LABEL, type, "-");
        },
        tradeTypeTone: function (type) {
            return TRADE_TYPE_TONE[String(type).toUpperCase()] || "off";
        },
        domesticStatusLabel: function (status) {
            return pick(DOMESTIC_STATUS_LABEL, status, "-");
        },
        domesticStatusTone: function (status) {
            return DOMESTIC_STATUS_TONE[String(status).toUpperCase()] || "off";
        },
        domesticTypeLabel: function (type) {
            return pick(DOMESTIC_TYPE_LABEL, type, "-");
        }
    };
})();
