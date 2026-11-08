$(function () {
    var STATUS = MARIA.status; // 역할 표기는 js/admin/status-labels.js 의 공통 매핑
    // 화면에서 고를 수 있는 역할 (옛 화면과 같음: 최고관리자는 고를 수 없고, 최고관리자 계정은 바꿀 수 없다)
    var ASSIGNABLE_ROLES = ["VIEWER", "REVIEWER", "SETTLEMENT"];
    var ROLE_ORDER = ["VIEWER", "REVIEWER", "SETTLEMENT", "ADMIN"];

    var admins = [];
    var filtered = [];
    var sortSpec = null; // 머리글로 고른 정렬 (받아 온 목록 전체를 정렬한다)
    var isAdmin = false;
    var PAGE_SIZE = 20;
    var currentPage = 0;
    var selectedAdminId = null;
    var pickedRole = null;
    var submitting = false;
    var SKELETON_ROWS = 6;
    var SKELETON_WIDTHS = ["62%", "48%", "40%"];
    var COLUMN_COUNT = 4;

    function escapeHtml(value) {
        return $("<div>").text(value == null ? "" : value).html();
    }

    function chip(role) {
        return '<span class="adm-chip adm-chip-' + STATUS.adminRoleTone(role) + '">' +
            escapeHtml(STATUS.adminRoleLabel(role)) + "</span>";
    }

    function errorMessage(xhr, fallback) {
        return (xhr.responseJSON && xhr.responseJSON.message) || fallback;
    }

    function handleRequestFailure(xhr, fallback, onFailure) {
        if (xhr.status === 401) {
            return;
        }
        if (onFailure) {
            onFailure();
        }
        MARIA.ui.showError(errorMessage(xhr, fallback));
    }

    // ---------- 조회조건: 역할 목록은 공통 매핑에서 (서버 호출 없이 화면에서 거른다) ----------
    ["ADMIN", "REVIEWER", "SETTLEMENT", "VIEWER"].forEach(function (role) {
        $("#adminUserFilterRole").append($("<option>", { value: role, text: STATUS.adminRoleLabel(role) }));
    });

    function applyFilter() {
        var keyword = $("#adminUserFilterName").val().trim().toLowerCase();
        var role = $("#adminUserFilterRole").val();
        filtered = admins.filter(function (admin) {
            return (!role || admin.role === role) &&
                (!keyword || String(admin.name || "").toLowerCase().indexOf(keyword) >= 0 ||
                    String(admin.loginId || "").toLowerCase().indexOf(keyword) >= 0);
        });
        if (sortSpec) {
            filtered = MARIA.tableSort.sortList(filtered, function (admin) {
                return admin[sortSpec.key];
            }, sortSpec.dir);
        }
    }

    // ---------- 요약 (목록 전체 기준) ----------
    function renderSummary() {
        $("#adminSummaryTotal").text(admins.length);
        $("#adminSummaryAdmin").text(admins.filter(function (a) { return a.role === "ADMIN"; }).length);
    }

    // ---------- 표 ----------
    function stateRow(className, innerHtml, fill) {
        return '<tr class="is-state ' + (fill ? "is-state-fill " : "") + className + '"><td colspan="' + COLUMN_COUNT + '">' + innerHtml + "</td></tr>";
    }

    function renderLoading() {
        var html = "";
        for (var r = 0; r < SKELETON_ROWS; r++) {
            html += '<tr class="is-state admin-users-loading" aria-label="불러오는 중"><td colspan="' + COLUMN_COUNT + '"><div class="adm-skel-row">' +
                '<div class="adm-skel-cell"></div>';
            for (var c = 0; c < 3; c++) {
                html += '<div class="adm-skel-cell"><span style="width:' + SKELETON_WIDTHS[(c + r) % SKELETON_WIDTHS.length] +
                    ';animation-delay:' + ((c + r) % 5) * 0.06 + 's"></span></div>';
            }
            html += "</div></td></tr>";
        }
        $("#adminUserListBody").html(html);
    }

    function renderRows() {
        var $body = $("#adminUserListBody").empty();
        $("#adminUserCount").text(filtered.length);

        if (!filtered.length) {
            $body.append(stateRow("admin-users-empty",
                '<div class="adm-state-empty">' +
                '<img src="/images/admin/char-front.png" alt="">' +
                '<div class="adm-state-empty-title">' + (admins.length ? "조건에 맞는 관리자가 없습니다." : "등록된 관리자가 없습니다.") + "</div>" +
                (admins.length
                    ? '<div class="adm-state-empty-desc">조회조건을 바꾸거나 초기화한 뒤 다시 조회해 주세요.</div>' +
                      '<button type="button" class="adm-btn admin-users-empty-reset">조건 초기화</button>'
                    : "") +
                "</div>", true));
            renderPagination();
            return;
        }

        var start = currentPage * PAGE_SIZE;
        filtered.slice(start, start + PAGE_SIZE).forEach(function (admin, index) {
            var selected = admin.adminId === selectedAdminId ? " is-selected" : "";
            $body.append(
                '<tr class="is-clickable' + selected + '" tabindex="0" data-admin-id="' + admin.adminId + '">' +
                '<td class="adm-col-no">' + (start + index + 1) + "</td>" +
                "<td><span>" + escapeHtml(admin.name || "-") + "</span></td>" +
                '<td class="admin-users-col-id"><span class="admin-users-login-id">' + escapeHtml(admin.loginId || "-") + "</span></td>" +
                '<td class="is-c">' + chip(admin.role) + "</td>" +
                "</tr>"
            );
        });
        renderPagination();
    }

    function renderPagination() {
        var $pagination = $("#adminUserPagination").empty();
        var totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
        if (filtered.length === 0) {
            return;
        }

        var BLOCK_SIZE = 10;
        var blockStart = Math.floor(currentPage / BLOCK_SIZE) * BLOCK_SIZE;
        var blockEnd = Math.min(totalPages - 1, blockStart + BLOCK_SIZE - 1);

        function addButton(label, targetPage, isDisabled, isActive) {
            var classes = "adm-page-btn" + (isActive ? " is-active" : "") + (/^[0-9]+$/.test(label) ? "" : " is-nav");
            var $btn = $('<button type="button" class="' + classes + '">' + label + "</button>");
            $btn.prop("disabled", isDisabled || isActive);
            if (!isDisabled && !isActive) {
                $btn.on("click", function () {
                    currentPage = targetPage;
                    renderRows();
                });
            }
            $pagination.append($btn);
        }

        if (blockStart > 0) {
            addButton("이전", blockStart - 1, false, false);
        }
        for (var i = blockStart; i <= blockEnd; i++) {
            addButton(String(i + 1), i, false, i === currentPage);
        }
        addButton("다음", blockEnd + 1, blockEnd === totalPages - 1, false);
    }

    function loadAdmins() {
        closePanel();
        renderLoading();
        MARIA.auth.ajax({ url: "/api/admin/admins", method: "GET" })
            .done(function (res) {
                admins = res.data || [];
                renderSummary();
                applyFilter();
                currentPage = 0;
                renderRows();
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "관리자 목록을 불러오지 못했습니다.", function () {
                    admins = [];
                    filtered = [];
                    $("#adminUserCount").text("-");
                    $("#adminSummaryTotal, #adminSummaryAdmin").text("-");
                    $("#adminUserPagination").empty();
                    $("#adminUserListBody").html(stateRow("admin-users-error",
                        '<div class="adm-state-error">' +
                        '<div class="adm-state-error-text">' +
                        '<div class="adm-state-error-title">' + escapeHtml(errorMessage(xhr, "관리자 목록을 불러오지 못했습니다.")) + "</div>" +
                        '<div class="adm-state-error-desc">잠시 후 다시 시도해 주세요.</div>' +
                        "</div>" +
                        '<button type="button" class="adm-btn admin-users-retry">다시 시도</button>' +
                        "</div>", true));
                });
            });
    }

    // ---------- 상세 패널: 역할 변경 ----------
    function findAdmin(adminId) {
        return admins.filter(function (a) { return a.adminId === adminId; })[0];
    }

    function renderRoleList(admin) {
        var locked = admin.role === "ADMIN" || !isAdmin;
        $("#adminRoleList").html(ROLE_ORDER.map(function (role) {
            var disabled = locked || ASSIGNABLE_ROLES.indexOf(role) < 0;
            var on = role === pickedRole;
            return '<label class="adm-radio-row' + (on ? " is-on" : "") + (disabled ? " is-disabled" : "") + '">' +
                '<input type="radio" name="adminRole" value="' + role + '"' + (on ? " checked" : "") + (disabled ? " disabled" : "") + ">" +
                '<span class="adm-radio-name">' + escapeHtml(STATUS.adminRoleLabel(role)) + "</span>" +
                '<span class="adm-radio-desc">' + escapeHtml(STATUS.ADMIN_ROLE_DESC[role] || "") + "</span>" +
                (role === admin.role ? '<span class="adm-radio-tag">현재 역할</span>' : "") +
                "</label>";
        }).join(""));
    }

    function updateSubmit(admin) {
        var canChange = isAdmin && admin.role !== "ADMIN";
        $("#adminDetailFoot").prop("hidden", !canChange);
        $("#adminRoleSubmit").prop("disabled", submitting || !canChange || !pickedRole || pickedRole === admin.role);
        $("#adminRoleNotice").text(admin.role === "ADMIN"
            ? "최고관리자 계정의 역할은 여기서 바꿀 수 없어요. 담당자에게 문의하세요."
            : "역할을 바꾸면 변경 전·후 역할, 처리 관리자, 처리시각이 감사 로그에 기록됩니다.");
    }

    function openPanel(adminId) {
        var admin = findAdmin(adminId);
        if (!admin) {
            return;
        }
        selectedAdminId = adminId;
        pickedRole = admin.role;
        $("#adminUserListBody tr").removeClass("is-selected").filter('[data-admin-id="' + adminId + '"]').addClass("is-selected");
        $("#adminDetailTitle").text(admin.name || "-");
        $("#adminDetailSub").text(admin.loginId || "");
        renderRoleList(admin);
        updateSubmit(admin);
        $("#adminDetailScrim, #adminDetailPanel").addClass("is-open");
        $("#adminDetailPanel").attr("aria-hidden", "false");
        $("#adminDetailClose").trigger("focus");
    }

    function closePanel() {
        if (!$("#adminDetailPanel").hasClass("is-open")) {
            return;
        }
        $("#adminDetailScrim, #adminDetailPanel").removeClass("is-open");
        $("#adminDetailPanel").attr("aria-hidden", "true");
        var $row = $("#adminUserListBody tr.is-selected").removeClass("is-selected");
        if ($row.length) {
            $row.trigger("focus");
        }
        selectedAdminId = null;
    }

    $("#adminUserListBody").on("click", "tr[data-admin-id]", function () {
        openPanel(Number($(this).data("admin-id")));
    });
    $("#adminUserListBody").on("keydown", "tr[data-admin-id]", function (event) {
        if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            openPanel(Number($(this).data("admin-id")));
        }
    });
    $("#adminDetailClose, #adminDetailScrim").on("click", closePanel);

    $("#adminRoleList").on("change", "input[name=adminRole]", function () {
        var admin = findAdmin(selectedAdminId);
        if (!admin) {
            return;
        }
        pickedRole = $(this).val();
        $("#adminRoleList .adm-radio-row").each(function () {
            $(this).toggleClass("is-on", $(this).find("input").val() === pickedRole);
        });
        updateSubmit(admin);
    });

    // ---------- 확인 창 → 역할 변경 요청 (PATCH /api/admin/admins/{id}/role, 본문 {"role": "..."}) ----------
    function openModal() {
        var admin = findAdmin(selectedAdminId);
        if (!isAdmin || !admin || admin.role === "ADMIN" || !pickedRole || pickedRole === admin.role) {
            return;
        }
        $("#adminRoleModalDesc").text(
            (admin.name || "-") + " · " + (admin.loginId || "-") + " — " + STATUS.adminRoleLabel(admin.role) + " → " + STATUS.adminRoleLabel(pickedRole) +
            ". 변경 전·후 역할과 처리 관리자가 감사 로그에 기록돼요.");
        $("#adminRoleModal").prop("hidden", false);
        $("#adminRoleModalConfirm").trigger("focus");
    }

    function closeModal() {
        $("#adminRoleModal").prop("hidden", true);
        $("#adminRoleSubmit").trigger("focus");
    }

    $("#adminRoleSubmit").on("click", openModal);
    $("#adminRoleModalClose, #adminRoleModalCancel").on("click", closeModal);
    $("#adminRoleModal").on("click", function (event) {
        if (event.target === this) {
            closeModal();
        }
    });
    // 확인 창이 열려 있을 땐 Esc 가 창만 닫는다 (뒤의 패널까지 닫히지 않게 capture 단계에서 막는다)
    document.addEventListener("keydown", function (event) {
        if (event.key === "Escape" && !$("#adminRoleModal").prop("hidden")) {
            event.stopImmediatePropagation();
            closeModal();
        }
    }, true);

    $("#adminRoleModalConfirm").on("click", function () {
        var admin = findAdmin(selectedAdminId);
        if (submitting || !isAdmin || !admin || admin.role === "ADMIN" || !pickedRole) {
            return;
        }
        var adminId = admin.adminId;
        var role = pickedRole;
        submitting = true;
        $("#adminRoleModal").prop("hidden", true);
        $("#adminRoleModalConfirm, #adminRoleSubmit").prop("disabled", true);
        MARIA.auth.ajax({
            url: "/api/admin/admins/" + adminId + "/role",
            method: "PATCH",
            contentType: "application/json",
            data: JSON.stringify({ role: role })
        })
            .done(function () {
                MARIA.ui.showSuccess("역할을 변경했어요");
                MARIA.ui.setStatus("관리자 역할을 변경했어요");
                loadAdmins();
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "역할 변경에 실패했습니다.");
            })
            .always(function () {
                submitting = false;
                $("#adminRoleModalConfirm").prop("disabled", false);
                var current = findAdmin(selectedAdminId);
                if (current) {
                    updateSubmit(current);
                }
            });
    });

    // ---------- 조회 조건 ----------
    function runSearch() {
        closePanel();
        applyFilter();
        currentPage = 0;
        renderRows();
    }

    function resetFilters() {
        $("#adminUserFilterName").val("");
        $("#adminUserFilterRole").val("");
        runSearch();
    }

    $("#adminUserFilterSearch").on("click", runSearch);
    $("#adminUserFilterName").on("keydown", function (event) {
        if (event.key === "Enter") {
            runSearch();
        }
    });
    $("#adminUserFilterReset").on("click", resetFilters);
    $("#adminUserListBody").on("click", ".admin-users-empty-reset", resetFilters);
    $("#adminUserListBody").on("click", ".admin-users-retry", loadAdmins);

    $("#adminUserPageSize").on("click", "button", function () {
        var size = parseInt($(this).attr("data-size"), 10);
        if (!size || size === PAGE_SIZE) {
            return;
        }
        PAGE_SIZE = size;
        $("#adminUserPageSize button").removeClass("is-on");
        $(this).addClass("is-on");
        currentPage = 0;
        renderRows();
    });

    MARIA.tableSort.bind(".admin-users-table", function (key, dir) {
        sortSpec = { key: key, dir: dir };
        runSearch();
    });

    MARIA.auth.requireAuth().done(function (admin) {
        isAdmin = !!(admin && admin.role === "ADMIN");
        loadAdmins();
    });
});
