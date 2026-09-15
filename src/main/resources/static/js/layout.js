/**
 * 공용 레이아웃(사이드바+헤더) 동작.
 * layout/main.html을 쓰는 모든 페이지에서 공통으로 로드된다.
 */
MARIA.ui = MARIA.ui || {};
MARIA.deeplink = MARIA.deeplink || {};
MARIA.deeplink.accountNoFromUrl = function () {
    return new URLSearchParams(window.location.search).get("accountNo");
};

// 계좌번호 = 회사코드(3자리) + 고유번호(7자리). DB/lookup용 값은 항상 순수 10자리 숫자 그대로 두고,
// 화면 표시할 때만 하이픈을 넣는다 - 아무 731로 시작하는 10자리 숫자나 바꾸면 금액과 충돌할 수 있어서
// 계좌번호를 렌더링하는 자리에서만 명시적으로 이 함수를 부른다(전역 DOM 스캔 방식은 쓰지 않음).
MARIA.fmt = MARIA.fmt || {};
MARIA.fmt.hyphenateAccountNo = function (accountNo) {
    if (!accountNo) return "-";
    var no = String(accountNo);
    return /^\d{10}$/.test(no) ? no.slice(0, 3) + "-" + no.slice(3) : no;
};
MARIA.fmt.accountNoHtml = function (accountNo) {
    return '<span class="account-no-fmt">' + MARIA.fmt.hyphenateAccountNo(accountNo) + '</span>';
};

MARIA.ui.showError = function (message) {
    var $container = $("#toastContainer");
    var maxToastCount = 3;
    while ($container.children(".toast").length >= maxToastCount) {
        $container.children(".toast").first().remove();
    }
    var $toast = $("<div>", { "class": "toast", text: message || "요청 처리 중 오류가 발생했습니다." });
    $container.append($toast);
    requestAnimationFrame(function () { $toast.addClass("is-visible"); });
    setTimeout(function () {
        $toast.removeClass("is-visible");
        setTimeout(function () { $toast.remove(); }, 200);
    }, 3500);
};

$(function () {
    MARIA.auth.requireAuth().done(function (admin) {
        // admin = { adminId, name, role } — GET /api/admin/me 응답

        // 브랜드명은 특정 화면으로 이동하지 않는 고정 영역으로 둔다.
        $(".sidebar-brand")
            .removeAttr("href role tabindex")
            .css("cursor", "default");

        // 현재 경로명은 쿼리스트링을 제거한 현재 페이지의 첫 화면으로 이동한다.
        $(".breadcrumb").each(function () {
            // Thymeleaf fragment가 span 대신 div를 주입하는 페이지도 있으므로
            // 마지막 breadcrumb 항목을 현재 페이지 링크로 정규화한다.
            $(this).children().last().addClass("breadcrumb-current");
        });
        $(".breadcrumb").on("click keydown", ".breadcrumb-current", function (event) {
            if (event.type === "click" || event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                window.location.href = window.location.pathname;
            }
        });
        $(".breadcrumb-current").attr({ role: "link", tabindex: "0" });

        var currentAdminRole = admin.role ? admin.role.toLowerCase() : "";
        if (currentAdminRole) {
            document.body.dataset.adminRole = currentAdminRole;
        }

        var currentBusinessTime = null;
        if (admin.name && admin.role) {
            $("#adminBadge").text(admin.name + " · " + admin.role);
        }

        var isAdmin = admin.role === "ADMIN";
        $("#clockButton")
            .prop("disabled", !isAdmin)
            .toggleClass("is-editable", isAdmin)
            .attr("title", isAdmin ? "업무시각 설정" : "업무시각 조회");

        applyTheme(localStorage.getItem("maria.theme") || "light");
        loadReferenceTime();

        $("#themeToggle").on("click", function () {
            var next = document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark";
            localStorage.setItem("maria.theme", next);
            applyTheme(next);
        });

        $("#logoutLink").on("click", function () {
            MARIA.auth.logout();
        });

        loadAccountReviewCount();

        setInterval(function () {
            if (!currentBusinessTime) {
                return;
            }

            currentBusinessTime = new Date(currentBusinessTime.getTime() + 1000);
            renderBusinessTime();
        }, 1000);

        $("#clockButton").on("click", function () {
            if (!isAdmin || !currentBusinessTime) {
                return;
            }

            $("#clockDatetime").val(toDateTimeLocalValue(currentBusinessTime));
            $("#clockReasonCode").val("");
            $("#clockModal").prop("hidden", false);
            $("#clockDatetime").trigger("focus");
        });

        $("#clockModalClose, #clockModalCancel").on("click", closeClockModal);

        $("#clockModal").on("click", function (event) {
            if (event.target === this) {
                closeClockModal();
            }
        });

        $(document).on("keydown", function (event) {
            if (event.key === "Escape") {
                if (closeActiveOverlay()) {
                    event.preventDefault();
                }
            }
        });

        $("#clockApplyButton").on("click", function () {
            var newDatetime = $("#clockDatetime").val();
            var reasonCode = $("#clockReasonCode").val().trim();

            if (!newDatetime) {
                MARIA.ui.showError("변경할 업무시각을 입력해 주세요.");
                return;
            }
            if (!reasonCode) {
                MARIA.ui.showError("업무시각 변경 사유를 입력해 주세요.");
                return;
            }

            var $applyButton = $(this).prop("disabled", true);
            MARIA.auth.ajax({
                url: "/api/admin/system-clock",
                method: "PATCH",
                contentType: "application/json",
                data: JSON.stringify({
                    newDatetime: newDatetime.length === 16 ? newDatetime + ":00" : newDatetime,
                    reasonCode: reasonCode
                })
            })
                .done(function (res) {
                    if (res.data) {
                        currentBusinessTime = new Date(res.data);
                        renderBusinessTime();
                    }
                    $(document).trigger("maria:system-clock-changed");
                    closeClockModal();
                })
                .fail(function (xhr) {
                    var message = xhr.responseJSON && xhr.responseJSON.message;
                    MARIA.ui.showError(message || "업무시각 변경에 실패했습니다.");
                })
                .always(function () {
                    $applyButton.prop("disabled", false);
                });
        });

        function applyTheme(theme) {
            document.documentElement.setAttribute("data-theme", theme);
            $("#themeToggle").text(theme === "dark" ? "🌙" : "☀");
            $(document).trigger("maria:themeChange", [theme]);
        }

        function loadAccountReviewCount() {
            MARIA.auth.ajax({
                url: "/api/admin/account/requiring-action-count",
                method: "GET"
            }).done(function (res) {
                var count = res.data || 0;
                $("#accountReviewBadge").text(count).toggle(count > 0);
            });
        }

        function loadReferenceTime() {
            MARIA.auth.ajax({
                url: "/api/admin/system-clock",
                method: "GET"
            })
                .done(function (res) {
                    if (res.data) {
                        currentBusinessTime = new Date(res.data);
                        renderBusinessTime();
                    }
                })
                .fail(function (xhr) {
                    if (xhr.status !== 401) {
                        MARIA.ui.showError("시스템 업무시각을 불러오지 못했습니다.");
                    }
                });
        }

        function renderBusinessTime() {
            $("#clockValue").text(formatDateTime(currentBusinessTime));
        }

        function closeClockModal() {
            $("#clockModal").prop("hidden", true);
        }

        function closeActiveOverlay() {
            if (!$("#clockModal").prop("hidden")) {
                closeClockModal();
                return true;
            }

            var $openOverlay = $("[role='dialog']:visible, .is-open, [class*='drawer'].is-open")
                .filter(function () {
                    return $(this).is(":visible") && !$(this).is("[hidden]");
                })
                .last();
            if (!$openOverlay.length) {
                return false;
            }

            var $closeButton = $openOverlay
                .find("[data-drawer-close], [aria-label*='닫'], [id*='close'], [class*='close']")
                .filter(":visible")
                .first();
            if ($closeButton.length) {
                $closeButton.trigger("click");
                return true;
            }

            $openOverlay.removeClass("is-open").attr("aria-hidden", "true").prop("hidden", true);
            return true;
        }

        function toDateTimeLocalValue(value) {
            function pad(number) {
                return String(number).padStart(2, "0");
            }

            return value.getFullYear()
                + "-" + pad(value.getMonth() + 1)
                + "-" + pad(value.getDate())
                + "T" + pad(value.getHours())
                + ":" + pad(value.getMinutes());
        }

        function formatDateTime(value) {
            return new Intl.DateTimeFormat("ko-KR", {
                year: "numeric",
                month: "2-digit",
                day: "2-digit",
                hour: "2-digit",
                minute: "2-digit",
                second: "2-digit",
                hour12: false
            }).format(new Date(value));
        }
    });

    // requireAuth().done() 밖: 인증과 무관한 전역 핸들러.

    // 검색어가 필요한 화면에서 빈 값으로 조회하는 실수를 공통으로 방지한다.
    $(document).on("click", "button, input[type='submit']", function (event) {
        var $button = $(this);
        var label = ($button.attr("id") || "") + " " + ($button.attr("class") || "") + " " + $button.text();
        if (!/(search|조회|검색)/i.test(label)) {
            return;
        }

        var $scope = $button.closest("form, .search-bar, .search-panel, .filter-bar, .toolbar, .query-bar");
        var $textInputs = $scope.find("input[type='text'], input[type='search']");
        if (!$textInputs.length || $textInputs.filter(function () { return $.trim($(this).val()); }).length) {
            return;
        }

        event.preventDefault();
        event.stopImmediatePropagation();
        MARIA.ui.showError("검색어를 입력해 주세요.");
    });

    // 페이지별 클릭 핸들러보다 먼저 검사해야 하므로 capture 단계에서도 동일한
    // 방어 로직을 둔다. 텍스트 검색 입력이 있는 조회 버튼에만 적용된다.
    document.addEventListener("click", function (event) {
        var button = event.target.closest && event.target.closest("button, input[type='submit']");
        if (!button) {
            return;
        }
        var label = (button.id || "") + " " + (button.className || "") + " " + (button.textContent || "");
        if (!/(search|조회|검색)/i.test(label)) {
            return;
        }
        var scope = button.closest("form, .search-bar, .search-panel, .filter-bar, .toolbar, .query-bar");
        if (!scope) {
            return;
        }
        var inputs = Array.from(scope.querySelectorAll("input[type='text'], input[type='search']"));
        if (!inputs.length || inputs.some(function (input) { return input.value.trim(); })) {
            return;
        }
        event.preventDefault();
        event.stopPropagation();
        MARIA.ui.showError("검색어를 입력해 주세요.");
    }, true);
});
