/**
 * 관리자 로그인 화면 동작 (A-2) — 화면 표시만 담당한다. 인증 로직은 login.js / auth.js 그대로.
 *  1) 글자 크기 4단계 (localStorage 'ria-admin-fontscale': 0~3, 기본 1)
 *  2) 1열 전환: 원본처럼 "화면 폭 / 글자 배율 < 860px" 이면 .is-narrow
 *  3) 로그인 요청 중 스피너·버튼 잠금, 실패 시 입력창 오류 테두리 (jQuery ajax 전역 이벤트로 감지)
 * 로그인 요청 URL: /api/auth/admin/login
 */
(function ($) {
    var STORAGE_KEY = "ria-admin-fontscale";
    var LOGIN_URL = "/api/auth/admin/login";
    var ZOOM = [0.9, 1, 1.15, 1.3];
    var NARROW_BELOW = 860;
    var TIPS = ["작게", "보통", "크게", "아주 크게"];

    var $root = $("#admLogin");
    var fs = 1;

    function readStoredScale() {
        try {
            var v = parseInt(localStorage.getItem(STORAGE_KEY), 10);
            if (v >= 0 && v <= 3) { return v; }
        } catch (e) { /* 저장소 사용 불가 시 기본값 */ }
        return 1;
    }

    function updateNarrow() {
        $root.toggleClass("is-narrow", window.innerWidth / ZOOM[fs] < NARROW_BELOW);
    }

    function renderFontButtons() {
        $root[0].style.setProperty("--font-scale", String(ZOOM[fs]));
        var tip = "글자 크기: " + TIPS[fs];
        $(".adm-login-fontsize button").each(function () {
            var step = $(this).data("fsStep");
            var enabled = step === "reset" || (step === -1 ? fs > 0 : fs < 3);
            var active = step === "reset" ? fs === 1 : (step === -1 ? fs < 1 : fs > 1);
            $(this).attr("title", tip)
                .toggleClass("is-active", active)
                .toggleClass("is-dim", !(enabled || active));
        });
        updateNarrow();
    }

    function setScale(next) {
        next = Math.max(0, Math.min(3, next));
        if (next === fs) { return; }
        try { localStorage.setItem(STORAGE_KEY, String(next)); } catch (e) { /* 무시 */ }
        $root.addClass("is-fading");
        setTimeout(function () {
            fs = next;
            renderFontButtons();
            $root.removeClass("is-fading");
        }, 75);
    }

    function setBusy(busy) {
        $("#loginSubmit").prop("disabled", busy);
        $("#loginSpinner").prop("hidden", !busy);
        $("#loginSubmitLabel").text(busy ? "확인 중" : "로그인");
    }

    $(function () {
        fs = readStoredScale();
        renderFontButtons();
        $(window).on("resize", updateNarrow);

        $(".adm-login-fontsize").on("click", "button", function () {
            var step = $(this).data("fsStep");
            setScale(step === "reset" ? 1 : fs + step);
        });

        // 로그인 요청 상태 표시 (요청·응답 처리는 login.js 가 그대로 한다)
        $(document).on("ajaxSend", function (event, xhr, options) {
            if (options.url === LOGIN_URL) {
                $("#loginForm").removeClass("is-error");
                setBusy(true);
            }
        });
        $(document).on("ajaxError", function (event, xhr, options) {
            if (options.url === LOGIN_URL) {
                setBusy(false);
                $("#loginForm").addClass("is-error");
            }
        });

        // 입력을 고치면 오류 표시를 걷는다
        $("#loginId, #password").on("input", function () {
            $("#loginForm").removeClass("is-error");
            $("#loginError").hide();
        });
    });
})(jQuery);
