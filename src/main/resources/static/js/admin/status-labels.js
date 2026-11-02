/* 관리자 화면 공통 상태 표기 매핑 (A-1 상태값 매핑).
   다른 화면에서도 같은 표기를 쓰려면 이 파일을 pageScripts 에서 불러와 MARIA.status.* 를 쓴다. */
var MARIA = window.MARIA || {};
window.MARIA = MARIA;

MARIA.status = (function () {
    // 세제혜택 (ACCOUNT.benefit): NULL 은 아직 판정 전이라 "대기" (API 는 NULL 을 UNCLASSIFIED 로 내려줌)
    var BENEFIT_LABEL = {
        POSSIBLE: "적용",
        REDUCED: "일부 감액",
        IMPOSSIBLE: "제외",
        UNCLASSIFIED: "대기"
    };

    function benefitLabel(benefit) {
        if (benefit === null || benefit === undefined || benefit === "") {
            return BENEFIT_LABEL.UNCLASSIFIED;
        }
        return BENEFIT_LABEL[String(benefit).toUpperCase()] || String(benefit);
    }

    return {
        BENEFIT_LABEL: BENEFIT_LABEL,
        benefitLabel: benefitLabel
    };
})();
