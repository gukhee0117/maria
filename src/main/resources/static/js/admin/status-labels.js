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

    return {
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
