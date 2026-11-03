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

// 로그인 확인(/api/admin/me)을 이 파일이 로드되자마자 가장 먼저 요청한다.
// 확인이 끝나기 전에는 #admShell.is-pending 이 화면 전체를 숨긴다 (로그인 전 본문이 잠깐 보이지 않게).
var admAuthRequest = MARIA.auth.requireAuth();

MARIA.ui.setStatus = function (message) {
    var $msg = $("#admStatusMsg").text(message || "");
    clearTimeout(MARIA.ui._statusTimer);
    if (message) {
        MARIA.ui._statusTimer = setTimeout(function () { $msg.text(""); }, 2600);
    }
};

$(function () {
    var STORAGE = {
        fontScale: "ria-admin-fontscale",
        theme: "ria-admin-theme",
        groups: "ria-admin-groups",
        sideCollapsed: "ria-admin-sidecollapsed"
    };
    var ZOOM = [0.9, 1, 1.15, 1.3];
    var FONT_NAMES = ["작게", "보통", "크게", "아주 크게"];
    var ROLE_LABELS = { VIEWER: "조회전용", REVIEWER: "심사담당", SETTLEMENT: "정산담당", ADMIN: "최고관리자" };

    var $shell = $("#admShell");
    var $side = $("#admSide");
    var fontIndex = 1;
    var sideCollapsedByUser = false;

    function store(key, value) { try { localStorage.setItem(key, value); } catch (e) {} }
    function load(key) { try { return localStorage.getItem(key); } catch (e) { return null; } }

    // ---------- 글자 크기 / 접힘 / 좁은 화면 ----------
    function currentZoom() { return ZOOM[fontIndex]; }

    function applyLayoutState() {
        var zoom = currentZoom();
        var narrow = window.innerWidth / zoom < 900;
        $shell.css("--font-scale", zoom);
        $shell.toggleClass("is-collapsed", sideCollapsedByUser || narrow);
        $shell.toggleClass("is-compact", window.innerWidth / zoom < 1000);
        $("#admSideToggle").attr("aria-expanded", String(!$shell.hasClass("is-collapsed")));
        if ($shell.hasClass("is-collapsed")) { hideFly(); }
        $(".adm-fontsize button").each(function () {
            var step = $(this).data("fsStep");
            var on = step === "reset" ? fontIndex === 1 : (step < 0 ? fontIndex < 1 : fontIndex > 1);
            var enabled = step === "reset" ? true : (step < 0 ? fontIndex > 0 : fontIndex < 3);
            $(this).toggleClass("is-active", on).toggleClass("is-dim", !enabled && !on)
                .attr("title", "글자 크기: " + FONT_NAMES[fontIndex]);
        });
    }

    function setFontIndex(next, announce) {
        next = Math.max(0, Math.min(3, next));
        if (next === fontIndex) { return; }
        store(STORAGE.fontScale, String(next));
        $shell.addClass("is-fading");
        setTimeout(function () {
            fontIndex = next;
            applyLayoutState();
            $shell.removeClass("is-fading");
            if (announce) { MARIA.ui.setStatus("글자 크기: " + FONT_NAMES[fontIndex]); }
        }, 75);
    }

    var savedFont = parseInt(load(STORAGE.fontScale), 10);
    if (savedFont >= 0 && savedFont <= 3) { fontIndex = savedFont; }
    sideCollapsedByUser = load(STORAGE.sideCollapsed) === "1";

    // ---------- 접힌 사이드바의 그룹 메뉴 ----------
    var flyTimer = null;
    var $fly = $("#admSideFly");

    function hideFly() { clearTimeout(flyTimer); $fly.prop("hidden", true).empty(); }

    applyLayoutState();
    $(window).on("resize", applyLayoutState);

    $(".adm-fontsize button").on("click", function () {
        var step = $(this).data("fsStep");
        setFontIndex(step === "reset" ? 1 : fontIndex + step, true);
    });

    $("#admSideToggle").on("click", function () {
        // 좁은 화면에서는 항상 접힘이므로 사용자 설정만 바꾼다.
        sideCollapsedByUser = !sideCollapsedByUser;
        store(STORAGE.sideCollapsed, sideCollapsedByUser ? "1" : "0");
        applyLayoutState();
    });

    // ---------- 그룹 접기/펼치기 ----------
    var groupState = {};
    try { groupState = JSON.parse(load(STORAGE.groups) || "{}") || {}; } catch (e) { groupState = {}; }

    var $groups = $side.find(".adm-side-group");
    $groups.each(function () {
        var $group = $(this);
        if ($group.find(".adm-side-item.is-active").length) {
            groupState[$group.data("group")] = true; // 지금 화면이 있는 그룹은 항상 펼친다
            $group.addClass("is-current");
        }
    });

    function renderGroups() {
        $groups.each(function () {
            var $group = $(this);
            var open = !!groupState[$group.data("group")];
            $group.toggleClass("is-open", open);
            $group.find(".adm-side-grouphead").attr("aria-expanded", String(open))
                .find(".adm-side-caret").text(open ? "▾" : "▸");
            $group.find(".adm-side-item").attr("tabindex", open ? 0 : -1);
        });
    }
    renderGroups();

    $side.on("click", ".adm-side-grouphead", function () {
        var key = $(this).closest(".adm-side-group").data("group");
        groupState[key] = !groupState[key];
        store(STORAGE.groups, JSON.stringify(groupState));
        renderGroups();
    });

    // 알림 배지 합계: 접힌 그룹의 머리글 숫자와 접힌 사이드바의 점
    MARIA.ui.refreshSideBadges = function () {
        $groups.each(function () {
            var $group = $(this);
            var sum = 0;
            $group.find(".adm-side-item:not([hidden]) .adm-side-badge").each(function () {
                if ($(this).css("display") !== "none") { sum += parseInt($(this).text(), 10) || 0; }
            });
            $group.find(".adm-side-groupsum").text(sum).prop("hidden", sum <= 0);
            $group.find(".adm-side-dot").prop("hidden", sum <= 0);
        });
    };

    $side.on("mouseenter focusin", ".adm-side-groupicon", function () {
        if (!$shell.hasClass("is-collapsed")) { return; }
        clearTimeout(flyTimer);
        var $group = $(this).closest(".adm-side-group");
        var rect = this.getBoundingClientRect();
        var zoom = currentZoom();
        var $items = $group.find(".adm-side-item:not([hidden])");
        $fly.empty().append($("<div>", { "class": "adm-side-fly-head", text: $group.data("group") }));
        $items.each(function () {
            var $item = $(this);
            var $row = $("<a>", {
                "class": "adm-side-fly-item" + ($item.hasClass("is-active") ? " is-active" : ""),
                href: $item.attr("href"),
                title: $item.attr("title")
            });
            $row.append($("<span>", { "class": "adm-side-bar" }));
            $row.append($("<span>", { "class": "adm-side-fly-no", text: $item.data("no") }));
            $row.append($("<span>", { "class": "adm-side-fly-label", text: $item.data("label") }));
            var $badge = $item.find(".adm-side-badge");
            if ($badge.css("display") !== "none" && $badge.text()) {
                $row.append($("<span>", { "class": "adm-side-badge", text: $badge.text() }));
            }
            $fly.append($row);
        });
        var height = 36 + $items.length * 32;
        var top = Math.max(4, Math.min(rect.top / zoom, window.innerHeight / zoom - 30 - height));
        $fly.css("top", top + "px").prop("hidden", false);
    });
    $side.on("mouseleave", ".adm-side-groupicon", function () {
        flyTimer = setTimeout(hideFly, 160);
    });
    $fly.on("mouseenter", function () { clearTimeout(flyTimer); })
        .on("mouseleave", function () { flyTimer = setTimeout(hideFly, 160); });

    // ---------- 메뉴 검색 (화면번호 · 이름 · 그룹) ----------
    var $search = $("#admSideSearch");
    var $results = $("#admSideResults");
    var searchItems = $side.find(".adm-side-item:not([hidden])").map(function () {
        var $item = $(this);
        return {
            no: String($item.data("no")),
            label: String($item.data("label")),
            group: String($item.closest(".adm-side-group").data("group")),
            href: $item.attr("href")
        };
    }).get();
    var searchList = [];
    var searchIndex = 0;

    function squash(text) { return String(text).split(" ").join(""); }

    function renderSearch() {
        var query = $.trim($search.val());
        if (!query || !$search.is(":focus")) { $results.prop("hidden", true).empty(); return; }
        var squashed = squash(query);
        searchList = searchItems.filter(function (x) {
            return x.no.indexOf(query) >= 0 || squash(x.label).indexOf(squashed) >= 0 || squash(x.group).indexOf(squashed) >= 0;
        }).slice(0, 10);
        searchIndex = Math.min(searchIndex, Math.max(0, searchList.length - 1));
        $results.empty();
        $.each(searchList, function (i, x) {
            var $row = $("<button>", { type: "button", "class": "adm-side-result" + (i === searchIndex ? " is-on" : "") });
            $row.append($("<span>", { "class": "adm-side-result-no", text: "[" + x.no + "]" }))
                .append($("<span>", { "class": "adm-side-result-label", text: x.label }))
                .append($("<span>", { "class": "adm-side-result-group", text: x.group }));
            $row.on("mousedown", function (event) { event.preventDefault(); goTo(x); })
                .on("mouseenter", function () { searchIndex = i; $results.children().removeClass("is-on").eq(i).addClass("is-on"); });
            $results.append($row);
        });
        if (!searchList.length) {
            $results.append($("<div>", { "class": "adm-side-result-none", text: "일치하는 화면이 없어요" }));
        }
        $results.prop("hidden", false);
    }

    function goTo(item) { window.location.href = item.href; }

    $search.on("input focus", function () { searchIndex = 0; renderSearch(); })
        .on("blur", function () { $results.prop("hidden", true); })
        .on("keydown", function (event) {
            if (event.key === "ArrowDown") {
                event.preventDefault();
                searchIndex = Math.min(searchIndex + 1, Math.max(0, searchList.length - 1));
                renderSearch();
            } else if (event.key === "ArrowUp") {
                event.preventDefault();
                searchIndex = Math.max(0, searchIndex - 1);
                renderSearch();
            } else if (event.key === "Enter") {
                event.preventDefault();
                var query = $.trim($search.val());
                var exact = searchItems.filter(function (x) { return x.no === query; })[0];
                var target = exact || searchList[searchIndex];
                if (target) { goTo(target); }
                else if (query) { MARIA.ui.setStatus("화면번호 " + query + "을(를) 찾을 수 없습니다"); }
            } else if (event.key === "Escape") {
                $search.val("").trigger("blur");
            }
        });

    $(document).on("keydown", function (event) {
        if ((event.ctrlKey || event.metaKey) && (event.key === "k" || event.key === "K")) {
            event.preventDefault();
            if ($shell.hasClass("is-collapsed")) {
                sideCollapsedByUser = false;
                store(STORAGE.sideCollapsed, "0");
                applyLayoutState();
            }
            setTimeout(function () { $search.trigger("focus"); }, 40);
        }
    });

    // ---------- 테마 ----------
    function themeTitle() {
        return document.documentElement.getAttribute("data-theme") === "dark" ? "라이트 모드로 전환" : "다크 모드로 전환";
    }
    function applyTheme(theme) {
        document.documentElement.setAttribute("data-theme", theme);
        $("#themeToggle").attr("title", themeTitle());
        $(document).trigger("maria:themeChange", [theme]);
    }
    $("#themeToggle").attr("title", themeTitle()).on("click", function () {
        var next = document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark";
        store(STORAGE.theme, next);
        applyTheme(next);
    });

    // ---------- 로그인 확인이 끝난 뒤에 하는 일 ----------
    admAuthRequest.done(function (admin) {
        // admin = { adminId, name, role } — GET /api/admin/me 응답
        $shell.removeClass("is-pending");

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
        var roleLabel = ROLE_LABELS[admin.role] || admin.role || "";
        if (admin.name) {
            $("#adminName").text(admin.name);
            $("#admStatusUser").text(admin.name + (roleLabel ? " · " + roleLabel : ""));
        }
        if (roleLabel) {
            $("#adminRole").text(roleLabel).prop("hidden", false);
        }

        var isAdmin = admin.role === "ADMIN";
        $("#clockButton")
            .prop("disabled", !isAdmin)
            .toggleClass("is-editable", isAdmin)
            .attr("title", isAdmin ? "시스템 업무시각 설정" : "업무시각 조회");

        applyTheme(document.documentElement.getAttribute("data-theme") === "dark" ? "dark" : "light");
        loadReferenceTime();

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

        function loadAccountReviewCount() {
            MARIA.auth.ajax({
                url: "/api/admin/account/requiring-action-count",
                method: "GET"
            }).done(function (res) {
                var count = res.data || 0;
                $("#accountReviewBadge").text(count).toggle(count > 0);
                MARIA.ui.refreshSideBadges();
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
            $("#admStatusBizDate").text(formatDate(currentBusinessTime));
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
                    return $(this).is(":visible") && !$(this).is("[hidden]") && !$(this).is(".adm-side-group");
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

        function pad(number) {
            return String(number).padStart(2, "0");
        }

        function formatDate(value) {
            return value.getFullYear() + "-" + pad(value.getMonth() + 1) + "-" + pad(value.getDate());
        }

        function toDateTimeLocalValue(value) {
            return formatDate(value) + "T" + pad(value.getHours()) + ":" + pad(value.getMinutes());
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
    }).fail(function (xhr) {
        // 401은 MARIA.auth가 /login 으로 보낸다 (화면은 계속 숨김). 그 밖의 오류만 알린다.
        if (xhr.status !== 401) {
            $shell.removeClass("is-pending");
            MARIA.ui.showError("로그인 상태를 확인하지 못했습니다.");
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
