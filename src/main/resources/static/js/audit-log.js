$(function () {
    var TARGET_TABLE_LABEL = {
        ADMIN_USER: "관리자",
        SYSTEM_CLOCK: "시스템 시각",
        ACCOUNT: "계좌",
        SETTLEMENT_BATCH: "정산배치",
        SETTLEMENT_ITEM: "정산항목",
        TAX_SNAPSHOT_BATCH: "세액 스냅샷 배치"
    };
    // reasonCode → 작업유형(한글). 서버가 주는 코드가 여기에 없으면 코드를 그대로 보여 준다.
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
        SETTLEMENT_ITEM_RETRIED: "정산 항목 재처리",
        TAX_SNAPSHOT_BATCH_REQUESTED: "세액 스냅샷 배치 실행 요청"
    };
    // 요약 카드 집계용 (reasonCode 기준)
    var DECISION_CODES = ["ACCOUNT_OPENED", "ACCOUNT_OVERRIDE_OPENED", "ACCOUNT_REJECTED",
        "ACCOUNT_CLOSURE_APPROVED", "ACCOUNT_CLOSURE_REJECTED"];
    var CHANGE_CODES = ["ADMIN_ROLE_UPDATE"];
    // 변경 전·후 값이 한 덩어리 문자열일 때 비교표의 "항목" 이름
    var VALUE_FIELD_LABEL = {
        ADMIN_ROLE_UPDATE: "역할",
        ACCOUNT_CHANGE_LIMIT_AMOUNT: "한도"
    };
    var TARGET_TABLE_FIELD_LABEL = {
        SYSTEM_CLOCK: "시스템 업무시각",
        ACCOUNT: "상태",
        SETTLEMENT_BATCH: "상태",
        SETTLEMENT_ITEM: "상태",
        TAX_SNAPSHOT_BATCH: "상태"
    };
    var ROLE_LABEL = {
        VIEWER: "조회전용",
        REVIEWER: "심사담당",
        SETTLEMENT: "정산담당",
        ADMIN: "최고관리자"
    };
    var PAGE_SIZE = 10;
    var currentPage = 0;
    var sortSpec = null; // 머리글로 고른 정렬(서버가 전체 데이터에서 정렬)
    var totalCount = 0;
    var currentLogs = [];
    var selectedIndex = -1;
    var SKELETON_ROWS = 8;
    var SKELETON_WIDTHS = ["62%", "70%", "48%", "54%", "66%", "78%", "86%"];

    function escapeHtml(value) {
        return $("<div>").text(value == null ? "" : value).html();
    }

    function pad(n) {
        return String(n).padStart(2, "0");
    }

    function formatDateTime(value) {
        var date = value ? new Date(value) : null;
        if (!date || isNaN(date.getTime())) {
            return "-";
        }
        return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate()) +
            " " + pad(date.getHours()) + ":" + pad(date.getMinutes()) + ":" + pad(date.getSeconds());
    }

    function formatClockValue(value) {
        var date = new Date(value);
        return isNaN(date.getTime()) ? value : formatDateTime(date);
    }

    function formatAuditValue(targetTable, value) {
        if (value == null) {
            return "-";
        }
        return targetTable === "SYSTEM_CLOCK" ? formatClockValue(value) : value;
    }

    function reasonCodeLabel(log) {
        if (REASON_CODE_LABEL[log.reasonCode]) {
            return REASON_CODE_LABEL[log.reasonCode];
        }
        // 업무시각 변경은 사유 코드 자리에 입력한 글이 들어오므로 대상 구분으로 작업유형을 정한다.
        if (log.targetTable === "SYSTEM_CLOCK") {
            return "업무시각 변경";
        }
        return log.reasonCode || "-";
    }

    function targetLabel(log) {
        if (log.targetTable === "SYSTEM_CLOCK") {
            return "시스템";
        }
        return log.targetName ? log.targetName : "#" + log.targetPk;
    }

    // 서버 사유 코드(대문자·밑줄)는 고정폭, 업무시각 변경처럼 입력한 글은 그대로
    function reasonCodeHtml(code) {
        if (!code) {
            return "<span>-</span>";
        }
        var isCode = /^[A-Z0-9_]+$/.test(code);
        return '<span' + (isCode ? ' class="audit-log-reason-code"' : "") + ">" + escapeHtml(code) + "</span>";
    }

    function adminLabel(log) {
        return log.adminName ? log.adminName : "관리자 ID " + log.adminId;
    }

    function roleLabel(value) {
        return ROLE_LABEL[value] || value || "";
    }

    function showError(message) {
        MARIA.ui.showError(message);
    }

    // ---------- 변경 전/후 값 → 비교표 ----------
    // "키: 값, 키: 값" 이나 JSON 객체면 항목별로 나누고, 그렇지 않으면 null (원문 그대로 보여 준다).
    function parseValuePairs(value) {
        if (value == null) {
            return null;
        }
        var text = String(value).trim();
        if (text.charAt(0) === "{") {
            try {
                var obj = JSON.parse(text);
                if (obj && typeof obj === "object" && !Array.isArray(obj)) {
                    return Object.keys(obj).map(function (key) {
                        var v = obj[key];
                        return [key, v !== null && typeof v === "object" ? JSON.stringify(v) : String(v)];
                    });
                }
            } catch (e) {
                return null;
            }
            return null;
        }
        var parts = text.split(", ");
        var pairs = [];
        for (var i = 0; i < parts.length; i++) {
            var at = parts[i].indexOf(": ");
            if (at <= 0) {
                return null;
            }
            pairs.push([parts[i].slice(0, at), parts[i].slice(at + 2)]);
        }
        return pairs.length ? pairs : null;
    }

    function pairValue(pairs, key) {
        for (var i = 0; i < pairs.length; i++) {
            if (pairs[i][0] === key) {
                return pairs[i][1];
            }
        }
        return "-";
    }

    // 반환: [[항목, 전, 후], ...]  ·  parsed=true 면 항목별로 나눈 것, false 면 원문 한 줄
    function buildDiff(log) {
        var beforePairs = parseValuePairs(log.beforeValue);
        var afterPairs = parseValuePairs(log.afterValue);
        if (afterPairs && (beforePairs || log.beforeValue == null)) {
            var keys = [];
            (beforePairs || []).concat(afterPairs).forEach(function (pair) {
                if (keys.indexOf(pair[0]) < 0) {
                    keys.push(pair[0]);
                }
            });
            return {
                parsed: true,
                rows: keys.map(function (key) {
                    return [key, beforePairs ? pairValue(beforePairs, key) : "-", pairValue(afterPairs, key)];
                })
            };
        }
        var field = VALUE_FIELD_LABEL[log.reasonCode] || TARGET_TABLE_FIELD_LABEL[log.targetTable] || "값";
        return {
            parsed: false,
            rows: [[field,
                formatAuditValue(log.targetTable, log.beforeValue),
                formatAuditValue(log.targetTable, log.afterValue)]]
        };
    }

    function buildSearchParams() {
        var params = { page: currentPage, size: PAGE_SIZE };
        if (sortSpec) {
            params.sort = sortSpec.key;
            params.direction = sortSpec.dir;
        }
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

    // ---------- 요약 (지금 불러온 목록 기준) ----------
    function renderSummary() {
        var bizDate = $("#admStatusBizDate").text();
        var today = 0;
        var decision = 0;
        var change = 0;
        currentLogs.forEach(function (log) {
            if (/^\d{4}-\d{2}-\d{2}$/.test(bizDate) && formatDateTime(log.processedAt).slice(0, 10) === bizDate) {
                today += 1;
            }
            if (DECISION_CODES.indexOf(log.reasonCode) >= 0) {
                decision += 1;
            }
            if (CHANGE_CODES.indexOf(log.reasonCode) >= 0 || log.targetTable === "SYSTEM_CLOCK") {
                change += 1;
            }
        });
        var loaded = currentLogs.length > 0 || totalCount === 0;
        $("#auditSummaryToday").text(loaded ? today : "-");
        $("#auditSummaryDecision").text(loaded ? decision : "-");
        $("#auditSummaryChange").text(loaded ? change : "-");
    }

    // 업무일자는 공통 셸이 시스템 업무시각을 불러온 뒤에 채워지므로, 바뀌면 "오늘 기록"만 다시 센다.
    var bizDateNode = document.getElementById("admStatusBizDate");
    if (bizDateNode && window.MutationObserver) {
        new MutationObserver(renderSummary).observe(bizDateNode, { childList: true, characterData: true, subtree: true });
    }

    // ---------- 표 ----------
    function stateRow(className, innerHtml, fill) {
        return '<tr class="is-state ' + (fill ? "is-state-fill " : "") + className + '"><td colspan="8">' + innerHtml + "</td></tr>";
    }

    function renderLoading() {
        var html = "";
        for (var r = 0; r < SKELETON_ROWS; r++) {
            html += '<tr class="is-state audit-log-loading" aria-label="불러오는 중"><td colspan="8"><div class="adm-skel-row">' +
                '<div class="adm-skel-cell"></div>';
            for (var c = 0; c < 7; c++) {
                html += '<div class="adm-skel-cell"><span style="width:' + SKELETON_WIDTHS[(c + r) % SKELETON_WIDTHS.length] +
                    ';animation-delay:' + ((c + r) % 5) * 0.06 + 's"></span></div>';
            }
            html += "</div></td></tr>";
        }
        $("#auditLogListBody").html(html);
    }

    function renderRows(logs) {
        var $body = $("#auditLogListBody").empty();
        currentLogs = logs;
        selectedIndex = -1;

        if (!logs.length) {
            $body.append(stateRow("audit-log-empty",
                '<div class="adm-state-empty">' +
                '<img src="/images/admin/char-front.png" alt="">' +
                '<div class="adm-state-empty-title">조회된 감사로그가 없습니다.</div>' +
                '<div class="adm-state-empty-desc">조회조건을 바꾸거나 초기화한 뒤 다시 조회해 주세요.</div>' +
                '<button type="button" class="adm-btn audit-log-empty-reset">조건 초기화</button>' +
                "</div>", true));
            return;
        }

        logs.forEach(function (log, index) {
            var diff = buildDiff(log);
            var changeHtml;
            if (diff.parsed) {
                changeHtml = diff.rows.map(function (row) {
                    return escapeHtml(row[0]) + ": " +
                        '<span class="audit-log-change-from">' + escapeHtml(row[1]) + "</span>" +
                        '<span class="audit-log-change-arrow">→</span>' +
                        '<span class="audit-log-change-to">' + escapeHtml(row[2]) + "</span>";
                }).join(", ");
            } else if (log.beforeValue == null) {
                changeHtml = '<span class="audit-log-change-to request-only">' + escapeHtml(diff.rows[0][2]) + "</span>";
            } else {
                changeHtml =
                    '<span class="audit-log-change-from">' + escapeHtml(diff.rows[0][1]) + "</span>" +
                    '<span class="audit-log-change-arrow">→</span>' +
                    '<span class="audit-log-change-to">' + escapeHtml(diff.rows[0][2]) + "</span>";
            }
            // 사유: 업무시각 변경은 입력한 글, 그 밖에는 서버가 기록한 사유 코드 (서버에 자유 입력 사유 필드는 없음)
            var reasonHtml = reasonCodeHtml(log.reasonCode);
            $body.append(
                '<tr class="is-clickable" tabindex="0" data-index="' + index + '">' +
                '<td class="adm-col-no">' + (currentPage * PAGE_SIZE + index + 1) + "</td>" +
                "<td><span>" + formatDateTime(log.processedAt) + "</span></td>" +
                "<td><span>" + formatDateTime(log.recordedAt) + "</span></td>" +
                '<td><span class="audit-log-admin-name">' + escapeHtml(adminLabel(log)) + "</span></td>" +
                "<td><span>" + escapeHtml(reasonCodeLabel(log)) + "</span></td>" +
                '<td class="audit-log-target-name"><span>' + escapeHtml(targetLabel(log)) + "</span></td>" +
                "<td>" + reasonHtml + "</td>" +
                '<td class="audit-log-change-cell"><span>' + changeHtml + "</span></td>" +
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
            var classes = "adm-page-btn" + (isActive ? " is-active" : "") + (/^[0-9]+$/.test(label) ? "" : " is-nav");
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

        if (blockStart > 0) {
            addButton("이전", blockStart - 1, false, false);
        }
        for (var i = blockStart; i <= blockEnd; i++) {
            addButton(String(i + 1), i, false, i === currentPage);
        }
        addButton("다음", blockEnd + 1, blockEnd === totalPages - 1, false);
    }

    function loadAuditLogs() {
        var $content = $(".content");
        var scrollTop = $content.scrollTop();
        closeDetail();
        renderLoading();
        MARIA.auth.ajax({
            url: "/api/admin/audit-logs",
            method: "GET",
            data: buildSearchParams()
        })
            .done(function (res) {
                var page = res.data || { content: [], totalCount: 0 };
                totalCount = page.totalCount || 0;
                $("#auditLogCount").text(totalCount);
                renderRows(page.content || []);
                renderSummary();
                renderPagination();
                $content.scrollTop(scrollTop);
            })
            .fail(function (xhr) {
                if (xhr.status !== 401) {
                    currentLogs = [];
                    $("#auditLogListBody").html(stateRow("audit-log-error",
                        '<div class="adm-state-error">' +
                        '<div class="adm-state-error-text">' +
                        '<div class="adm-state-error-title">감사로그를 불러오지 못했습니다.</div>' +
                        '<div class="adm-state-error-desc">잠시 후 다시 시도해 주세요.</div>' +
                        "</div>" +
                        '<button type="button" class="adm-btn audit-log-retry">다시 시도</button>' +
                        "</div>", true));
                    showError((xhr.responseJSON && xhr.responseJSON.message) || "감사로그를 불러오지 못했습니다.");
                }
            });
    }

    // ---------- 상세 패널 ----------
    function kvRow(key, valueHtml) {
        return '<div class="adm-kv-k">' + escapeHtml(key) + '</div><div class="adm-kv-v">' + valueHtml + "</div>";
    }

    function openDetail(index) {
        var log = currentLogs[index];
        if (!log) {
            return;
        }
        selectedIndex = index;
        $("#auditLogListBody tr").removeClass("is-selected").filter('[data-index="' + index + '"]').addClass("is-selected");

        var diff = buildDiff(log);
        var admin = adminLabel(log) + (log.adminRole ? " · " + roleLabel(log.adminRole) : "");
        $("#auditDetailTitle").text(reasonCodeLabel(log));
        $("#auditDetailSub").text(adminLabel(log) + " · " + formatDateTime(log.processedAt));

        var html = '<div><div class="adm-section-title">기본 정보</div><div class="adm-kv">' +
            kvRow("관리자", escapeHtml(admin)) +
            kvRow("대상", escapeHtml(targetLabel(log))) +
            kvRow("대상 구분", escapeHtml(TARGET_TABLE_LABEL[log.targetTable] || log.targetTable || "-")) +
            kvRow("사유", reasonCodeHtml(log.reasonCode)) +
            kvRow("처리시각", escapeHtml(formatDateTime(log.processedAt))) +
            kvRow("기록시각", escapeHtml(formatDateTime(log.recordedAt))) +
            "</div></div>";

        html += '<div><div class="adm-section-title">변경 전 → 후</div><div class="adm-diff">' +
            '<div class="adm-diff-row adm-diff-head"><span>항목</span><span>변경 전</span><span>변경 후</span></div>' +
            diff.rows.map(function (row) {
                return '<div class="adm-diff-row"><span class="adm-diff-f">' + escapeHtml(row[0]) + "</span>" +
                    '<span class="adm-diff-b' + (diff.parsed ? "" : " audit-log-raw") + '">' + escapeHtml(row[1]) + "</span>" +
                    '<span class="adm-diff-a' + (diff.parsed ? "" : " audit-log-raw") + '">' + escapeHtml(row[2]) + "</span></div>";
            }).join("") +
            "</div></div>";
        $("#auditDetailBody").html(html).scrollTop(0);

        $("#auditDetailScrim, #auditDetailPanel").addClass("is-open");
        $("#auditDetailPanel").attr("aria-hidden", "false");
        $("#auditDetailClose").trigger("focus");
    }

    function closeDetail() {
        if (!$("#auditDetailPanel").hasClass("is-open")) {
            return;
        }
        $("#auditDetailScrim, #auditDetailPanel").removeClass("is-open");
        $("#auditDetailPanel").attr("aria-hidden", "true");
        var $row = $("#auditLogListBody tr.is-selected").removeClass("is-selected");
        if ($row.length) {
            $row.trigger("focus");
        }
        selectedIndex = -1;
    }

    $("#auditLogListBody").on("click", "tr[data-index]", function () {
        openDetail(parseInt($(this).attr("data-index"), 10));
    });
    $("#auditLogListBody").on("keydown", "tr[data-index]", function (event) {
        if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            openDetail(parseInt($(this).attr("data-index"), 10));
        }
    });
    $("#auditDetailClose, #auditDetailScrim").on("click", closeDetail);

    // ---------- 조회 조건 ----------
    function resetFilters() {
        $("#auditTargetTableFilter").val("");
        $("#auditAdminKeywordFilter").val("");
        $("#auditTargetKeywordFilter").val("");
        $("#auditReasonKeywordFilter").val("");
        $("#auditStartDateFilter").val("");
        $("#auditEndDateFilter").val("");
        currentPage = 0;
        loadAuditLogs();
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

    $("#auditLogReset").on("click", resetFilters);
    $("#auditLogListBody").on("click", ".audit-log-empty-reset", resetFilters);
    $("#auditLogListBody").on("click", ".audit-log-retry", loadAuditLogs);

    $("#auditLogPageSize").on("click", "button", function () {
        var size = parseInt($(this).attr("data-size"), 10);
        if (!size || size === PAGE_SIZE) {
            return;
        }
        PAGE_SIZE = size;
        $("#auditLogPageSize button").removeClass("is-on");
        $(this).addClass("is-on");
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

    MARIA.tableSort.bind("#auditLogTable", function (key, dir) {
        sortSpec = { key: key, dir: dir };
        currentPage = 0;
        loadAuditLogs();
    });

    loadAuditLogs();
});
