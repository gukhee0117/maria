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

    return {
        BENEFIT_LABEL: BENEFIT_LABEL,
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
