$(function () {
    var TARGET_TABLE_LABEL = {
        ADMIN_USER: "관리자",
        SYSTEM_CLOCK: "시스템 시각",
        ACCOUNT: "계좌",
        SETTLEMENT_BATCH: "정산배치",
        SETTLEMENT_ITEM: "정산항목"
    };
    var REASON_CODE_LABEL = {
        ADMIN_ROLE_UPDATE: "관리자 권한 변경",
        ACCOUNT_APPLY: "계좌 개설 신청",
        ACCOUNT_REAPPLY: "계좌 재신청",
        ACCOUNT_CHANGE_LIMIT_AMOUNT: "한도 변경",
        ACCOUNT_OPENED: "계좌 개설",
        ACCOUNT_REJECTED: "계좌 반려",
        ACCOUNT_OVERRIDE_OPENED: "계좌 오버라이드 개설",
        ACCOUNT_CLOSURE_REQUESTED: "계좌 해지 신청",
        ACCOUNT_CLOSURE_APPROVED: "계좌 해지 승인",
        ACCOUNT_CLOSURE_REJECTED: "계좌 해지 반려",
        SETTLEMENT_BATCH_REQUESTED: "정산 배치 실행 요청",
        SETTLEMENT_BATCH_RETRIED: "정산 배치 재처리",
        SETTLEMENT_ITEM_RETRIED: "정산 항목 재처리"
    };
    var ROLE_LABEL = {
        VIEWER: "조회전용",
        REVIEWER: "심사담당",
        SETTLEMENT: "정산담당",
        ADMIN: "최고관리자"
    };
    var DATE_TIME_FORMATTER = new Intl.DateTimeFormat("ko-KR", {
        year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"
    });
    var PAGE_SIZE = 10;
    var currentPage = 0;
    var totalCount = 0;

    function escapeHtml(value) {
        return $("<div>").text(value == null ? "" : value).html();
    }

    function formatDateTime(value) {
        return value ? DATE_TIME_FORMATTER.format(new Date(value)) : "-";
    }

    function formatClockValue(value) {
        var date = new Date(value);
        if (isNaN(date.getTime())) {
            return value;
        }
        function pad(n) {
            return String(n).padStart(2, "0");
        }
        return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate()) +
            " " + pad(date.getHours()) + ":" + pad(date.getMinutes()) + ":" + pad(date.getSeconds());
    }

    function formatAuditValue(targetTable, value) {
        if (value == null) {
            return "-";
        }
        return targetTable === "SYSTEM_CLOCK" ? formatClockValue(value) : value;
    }

    var TARGET_TABLE_BADGE_CLASS = {
        ADMIN_USER: "type-admin-user",
        SYSTEM_CLOCK: "type-system-clock",
        ACCOUNT: "type-account",
        SETTLEMENT_BATCH: "type-settlement-batch",
        SETTLEMENT_ITEM: "type-settlement-item"
    };

    function targetTableLabel(value) {
        return TARGET_TABLE_LABEL[value] || value || "-";
    }

    function targetTableBadgeClass(value) {
        return TARGET_TABLE_BADGE_CLASS[value] || "";
    }

    function reasonCodeLabel(value) {
        return REASON_CODE_LABEL[value] || value || "-";
    }

    function roleLabel(value) {
        return ROLE_LABEL[value] || value || "";
    }

    function showError(message) {
        MARIA.ui.showError(message);
    }

    function buildSearchParams() {
        var params = { page: currentPage, size: PAGE_SIZE };
        var targetTable = $("#auditTargetTableFilter").val();
        var adminKeyword = ($("#auditAdminKeywordFilter").val() || "").trim();
        var targetKeyword = ($("#auditTargetKeywordFilter").val() || "").trim();
        var reasonKeyword = ($("#auditReasonKeywordFilter").val() || "").trim();
        var startDate = $("#auditStartDateFilter").val();
        var endDate = $("#auditEndDateFilter").val();

        if (targetTable) {
            params.targetTable = targetTable;
        }
        if (adminKeyword) {
            params.adminKeyword = adminKeyword;
        }
        if (targetKeyword) {
            params.targetKeyword = targetKeyword;
        }
        if (reasonKeyword) {
            params.reasonKeyword = reasonKeyword;
        }
        if (startDate) {
            params.startDate = startDate;
        }
        if (endDate) {
            params.endDate = endDate;
        }
        return params;
    }

    function renderRows(logs) {
        var $body = $("#auditLogListBody").empty();

        if (!logs.length) {
            $body.append(
                '<tr><td colspan="7" class="audit-log-empty">조회된 감사로그가 없습니다.</td></tr>'
            );
            return;
        }

        logs.forEach(function (log) {
            var adminLabel = log.adminName ? log.adminName : "관리자 ID " + log.adminId;
            var targetLabel = log.targetName ? log.targetName : "#" + log.targetPk;
            $body.append(
                "<tr>" +
                "<td>" + formatDateTime(log.processedAt) + "</td>" +
                "<td>" + formatDateTime(log.recordedAt) + "</td>" +
                "<td><div class=\"audit-log-admin-name\">" + escapeHtml(adminLabel) + "</div>" +
                "<div class=\"audit-log-admin-role\">" + escapeHtml(roleLabel(log.adminRole)) + "</div></td>" +
                "<td><span class=\"audit-log-target-badge " + targetTableBadgeClass(log.targetTable) + "\">" +
                escapeHtml(targetTableLabel(log.targetTable)) + "</span></td>" +
                "<td class=\"audit-log-target-name\">" + escapeHtml(targetLabel) + "</td>" +
                "<td>" + escapeHtml(reasonCodeLabel(log.reasonCode)) + "</td>" +
                "<td class=\"audit-log-before\">" +
                escapeHtml(formatAuditValue(log.targetTable, log.beforeValue)) + "</td>" +
                "<td class=\"audit-log-after\">" +
                escapeHtml(formatAuditValue(log.targetTable, log.afterValue)) + "</td>" +
                "</tr>"
            );
        });
    }

    function renderPagination() {
        var $pagination = $("#auditLogPagination").empty();
        var totalPages = Math.max(1, Math.ceil(totalCount / PAGE_SIZE));
        if (totalCount === 0 || totalPages <= 1) {
            return;
        }

        var BLOCK_SIZE = 10;
        var blockStart = Math.floor(currentPage / BLOCK_SIZE) * BLOCK_SIZE;
        var blockEnd = Math.min(totalPages - 1, blockStart + BLOCK_SIZE - 1);

        function addButton(label, targetPage, isDisabled, isActive) {
            var classes = "page-btn" + (isActive ? " active" : "");
            var $btn = $('<button type="button" class="' + classes + '">' + label + "</button>");
            $btn.prop("disabled", isDisabled || isActive);
            if (!isDisabled && !isActive) {
                $btn.on("click", function () {
                    currentPage = targetPage;
                    loadAuditLogs();
                });
            }
            $pagination.append($btn);
        }

        addButton("이전", blockStart - 1, blockStart === 0, false);
        for (var i = blockStart; i <= blockEnd; i++) {
            addButton(String(i + 1), i, false, i === currentPage);
        }
        addButton("다음", blockEnd + 1, blockEnd === totalPages - 1, false);
    }

    function loadAuditLogs() {
        var $content = $(".content");
        var scrollTop = $content.scrollTop();
        $("#auditLogListBody").html(
            '<tr><td colspan="7" class="audit-log-loading">불러오는 중...</td></tr>'
        );
        MARIA.auth.ajax({
            url: "/api/admin/audit-logs",
            method: "GET",
            data: buildSearchParams()
        })
            .done(function (res) {
                var page = res.data || { content: [], totalCount: 0 };
                totalCount = page.totalCount || 0;
                $("#auditLogCount").text(totalCount + "건");
                renderRows(page.content || []);
                renderPagination();
                $content.scrollTop(scrollTop);
            })
            .fail(function (xhr) {
                if (xhr.status !== 401) {
                    $("#auditLogListBody").html(
                        '<tr><td colspan="7" class="audit-log-error">감사로그를 불러오지 못했습니다.</td></tr>'
                    );
                    showError((xhr.responseJSON && xhr.responseJSON.message) || "감사로그를 불러오지 못했습니다.");
                }
            });
    }

    $("#auditLogSearch").on("click", function () {
        currentPage = 0;
        loadAuditLogs();
    });

    $("#auditAdminKeywordFilter, #auditTargetKeywordFilter, #auditReasonKeywordFilter").on(
        "keydown",
        function (event) {
            if (event.key === "Enter") {
                currentPage = 0;
                loadAuditLogs();
            }
        }
    );

    $("#auditLogReset").on("click", function () {
        $("#auditTargetTableFilter").val("");
        $("#auditAdminKeywordFilter").val("");
        $("#auditTargetKeywordFilter").val("");
        $("#auditReasonKeywordFilter").val("");
        $("#auditStartDateFilter").val("");
        $("#auditEndDateFilter").val("");
        currentPage = 0;
        loadAuditLogs();
    });

    $("#previousAuditLogPage").on("click", function () {
        if (currentPage > 0) {
            currentPage -= 1;
            loadAuditLogs();
        }
    });

    $("#nextAuditLogPage").on("click", function () {
        var totalPages = Math.max(1, Math.ceil(totalCount / PAGE_SIZE));
        if (currentPage < totalPages - 1) {
            currentPage += 1;
            loadAuditLogs();
        }
    });

    $(document).on("maria:system-clock-changed", function () {
        currentPage = 0;
        loadAuditLogs();
    });

    loadAuditLogs();
});
