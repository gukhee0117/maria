$(function () {
    var STATUS = MARIA.status; // 처리 상태·인출 구분 표기는 js/admin/status-labels.js 의 공통 매핑

    var KRW_FORMATTER = new Intl.NumberFormat("ko-KR");

    var PAGE_SIZE = 20;
    var currentPage = 0;
    var SKELETON_ROWS = 8;
    var SKELETON_WIDTHS = ["62%", "70%", "48%", "54%", "66%", "78%", "56%", "40%", "60%"];
    var COLUMN_COUNT = 7;

    var allRows = [];            // 처리 상태 조건 없이 받은 인출 내역 (요약 집계 기준)
    var viewRows = [];           // 지금 조회조건(처리 상태)으로 받은 인출 내역
    var accountMetaByNo = {};    // 계좌 목록(GET /api/admin/account/list) — 개설 시각·잔액·세제혜택
    var businessToday = null;    // 업무일자(GET /api/admin/system-clock) — "오늘 인출 신청" 기준
    var filteredRows = [];       // 화면 조건(검색어·계좌 구분)까지 거른 결과
    var sortSpec = null;         // 머리글로 고른 정렬 (받아 온 목록 전체를 정렬한다)
    var selectedWithdrawalId = null;
    var summaryLoaded = false;   // 조건 없는 목록을 한 번이라도 받았는지

    // ---------- 서식 ----------
    function escapeHtml(value) {
        return $("<div>").text(value == null ? "" : value).html();
    }

    function pad(n) {
        return String(n).padStart(2, "0");
    }

    // 금액은 숫자만 (단위는 열 이름·요약 단위가 말해 준다). 값이 없으면 "-".
    function formatAmount(amount) {
        return amount == null ? "-" : KRW_FORMATTER.format(Math.trunc(Number(amount)));
    }

    function parseDate(value) {
        var date = value ? new Date(value) : null;
        return !date || isNaN(date.getTime()) ? null : date;
    }

    function formatDate(value) {
        var date = parseDate(value);
        return !date ? "-" : date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate());
    }

    function formatDateTime(value) {
        var date = parseDate(value);
        return !date ? "-" : formatDate(value) + " " + pad(date.getHours()) + ":" + pad(date.getMinutes());
    }

    function chip(tone, label, title) {
        return '<span class="adm-chip adm-chip-' + tone + '"' + (title ? ' title="' + escapeHtml(title) + '"' : "") + ">" +
            escapeHtml(label) + "</span>";
    }

    function kindChip(item) {
        var kind = STATUS.withdrawalKind(item);
        if (!kind) {
            return '<span title="배분 정보가 없어 판정 불가">-</span>';
        }
        return chip(STATUS.withdrawalKindTone(kind), STATUS.withdrawalKindLabel(kind),
            STATUS.withdrawalTypeLabel(STATUS.withdrawalType(item)));
    }

    function statusChip(status) {
        return chip(STATUS.withdrawalStatusTone(status), STATUS.withdrawalStatusLabel(status));
    }

    // ---------- 조회조건 목록 ----------
    // 처리 상태: 서버에는 지금과 같은 status 값(WithdrawalStatus)만 보낸다. 취소(CANCELLED)는 옛 화면처럼 목록에서 뺀다.
    [["REQUESTED"], ["COMPLETED"], ["FAILED"]].forEach(function (option) {
        $("#withdrawal-filter-status").append($("<option>", {
            value: option[0], text: STATUS.withdrawalStatusLabel(option[0])
        }));
    });
    // 계좌 구분: 서버에 거르는 조건이 없어 화면에서 거른다.
    ["NORMAL", "EARLY"].forEach(function (kind) {
        $("#withdrawal-filter-kind").append($("<option>", { value: kind, text: STATUS.withdrawalKindLabel(kind) }));
    });

    // ---------- 요약 (인출 내역 목록 전체를 화면에서 센다) ----------
    function renderSummary() {
        if (!summaryLoaded) {
            return;
        }
        var early = 0;
        var failed = 0;
        var today = 0;
        var todayText = businessToday ? formatDate(businessToday) : null;
        allRows.forEach(function (item) {
            if (STATUS.withdrawalKind(item) === "EARLY") {
                early += 1;
            }
            if (item.status === "FAILED") {
                failed += 1;
            }
            if (todayText && formatDate(item.processedAt) === todayText) {
                today += 1;
            }
        });
        $("#withdrawal-summary-today").text(todayText ? KRW_FORMATTER.format(today) : "-");
        $("#withdrawal-summary-early").text(KRW_FORMATTER.format(early));
        $("#withdrawal-summary-failed").text(KRW_FORMATTER.format(failed));
    }

    function clearSummary() {
        $("#withdrawal-summary-today, #withdrawal-summary-early, #withdrawal-summary-failed").text("-");
    }

    // ---------- 표 ----------
    function stateRow(className, innerHtml, fill) {
        return '<tr class="is-state ' + (fill ? "is-state-fill " : "") + className + '"><td colspan="' + COLUMN_COUNT + '">' +
            innerHtml + "</td></tr>";
    }

    function renderLoading() {
        var html = "";
        for (var r = 0; r < SKELETON_ROWS; r++) {
            html += '<tr class="is-state withdrawals-loading" aria-label="불러오는 중"><td colspan="' + COLUMN_COUNT +
                '"><div class="adm-skel-row"><div class="adm-skel-cell"></div>';
            for (var c = 0; c < COLUMN_COUNT - 1; c++) {
                html += '<div class="adm-skel-cell"><span style="width:' + SKELETON_WIDTHS[(c + r) % SKELETON_WIDTHS.length] +
                    ';animation-delay:' + ((c + r) % 5) * 0.06 + 's"></span></div>';
            }
            html += "</div></td></tr>";
        }
        $("#withdrawal-list").html(html);
    }

    function renderRows() {
        var $body = $("#withdrawal-list").empty();
        var start = currentPage * PAGE_SIZE;
        var pageRows = filteredRows.slice(start, start + PAGE_SIZE);

        if (!pageRows.length) {
            $body.append(stateRow("withdrawals-empty",
                '<div class="adm-state-empty">' +
                '<img src="/images/admin/char-front.png" alt="">' +
                '<div class="adm-state-empty-title">인출 내역이 없습니다.</div>' +
                '<div class="adm-state-empty-desc">조회조건을 바꾸거나 초기화한 뒤 다시 조회해 주세요.</div>' +
                '<button type="button" class="adm-btn withdrawals-empty-reset">조건 초기화</button>' +
                "</div>", true));
            return;
        }

        pageRows.forEach(function (item, index) {
            var selectedClass = Number(item.withdrawalId) === Number(selectedWithdrawalId) ? " is-selected" : "";
            $body.append(
                '<tr class="is-clickable' + selectedClass + '" tabindex="0" data-withdrawal-id="' + item.withdrawalId + '">' +
                '<td class="adm-col-no">' + (start + index + 1) + "</td>" +
                "<td><span>" + escapeHtml(MARIA.fmt.hyphenateAccountNo(item.riaAccountNo)) + "</span></td>" +
                '<td class="withdrawals-col-name"><span>' + escapeHtml(MARIA.fmt.maskName(item.customerName) || "") + "</span></td>" +
                '<td class="is-c">' + kindChip(item) + "</td>" +
                '<td class="is-r is-strong"><span>' + formatAmount(item.requestedAmount) + "</span></td>" +
                "<td><span>" + formatDateTime(item.processedAt) + "</span></td>" +
                '<td class="is-c">' + statusChip(item.status) + "</td>" +
                "</tr>"
            );
        });
    }

    function renderPagination() {
        var $pagination = $("#withdrawal-pagination").empty();
        var totalPages = Math.max(1, Math.ceil(filteredRows.length / PAGE_SIZE));
        if (filteredRows.length === 0 || totalPages <= 1) {
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
                    closeDrawer();
                    renderRows();
                    renderPagination();
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

    // 검색어(계좌번호·고객명)와 계좌 구분을 화면에서 거른다. 순서는 서버 순서(최근 신청 순) 그대로.
    function applyClientFilters() {
        var keyword = ($("#withdrawal-keyword").val() || "").trim().toLowerCase();
        var kind = $("#withdrawal-filter-kind").val();

        filteredRows = viewRows.filter(function (item) {
            if (kind && STATUS.withdrawalKind(item) !== kind) {
                return false;
            }
            if (!keyword) {
                return true;
            }
            var accountNo = String(item.riaAccountNo || "").toLowerCase();
            return String(item.customerName || "").toLowerCase().indexOf(keyword) >= 0 ||
                accountNo.indexOf(keyword.replace(/-/g, "")) >= 0;
        });

        if (sortSpec) {
            filteredRows = MARIA.tableSort.sortList(filteredRows, function (item) {
                return sortSpec.key === "kind" ? STATUS.withdrawalKind(item) : item[sortSpec.key];
            }, sortSpec.dir);
        }

        currentPage = 0;
        $("#withdrawal-count").text(KRW_FORMATTER.format(filteredRows.length));
        renderRows();
        renderPagination();
    }

    function loadWithdrawals() {
        var status = $("#withdrawal-filter-status").val();
        closeDrawer();
        renderLoading();

        MARIA.auth.ajax({
            url: "/api/admin/withdrawals",
            method: "GET",
            data: status ? { status: status } : undefined
        })
            .done(function (res) {
                viewRows = (res.data || []).filter(function (item) {
                    return item.status !== "CANCELLED";
                });
                if (!status) {
                    allRows = viewRows;
                    summaryLoaded = true;
                    renderSummary();
                }
                applyClientFilters();
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                var message = (xhr.responseJSON && xhr.responseJSON.message) || "인출 내역을 불러오지 못했습니다.";
                viewRows = [];
                filteredRows = [];
                if (!status) {
                    allRows = [];
                    summaryLoaded = false;
                    clearSummary();
                }
                $("#withdrawal-count").text("-");
                $("#withdrawal-pagination").empty();
                $("#withdrawal-list").html(stateRow("withdrawals-error",
                    '<div class="adm-state-error">' +
                    '<div class="adm-state-error-text">' +
                    '<div class="adm-state-error-title">' + escapeHtml(message) + "</div>" +
                    '<div class="adm-state-error-desc">잠시 후 다시 시도해 주세요.</div>' +
                    "</div>" +
                    '<button type="button" class="adm-btn withdrawals-retry">다시 시도</button>' +
                    "</div>", true));
            });
    }

    function loadAccountMeta() {
        MARIA.auth.ajax({ url: "/api/admin/account/list", method: "GET" })
            .done(function (res) {
                accountMetaByNo = {};
                (res.data || []).forEach(function (account) {
                    accountMetaByNo[String(account.accountNo || "-")] = account;
                });
            });
    }

    function loadBusinessToday() {
        MARIA.auth.ajax({ url: "/api/admin/system-clock", method: "GET" })
            .done(function (res) {
                businessToday = parseDate(res.data);
                renderSummary();
            });
    }

    // ---------- 상세 패널 ----------
    // 의무유지기간 경과율: 원화 확정(finalAt)부터 1년 경과일(maturityAt)까지 중 인출 시점이 어디인지 (옛 화면과 같은 계산)
    function calculateProgress(allocation) {
        if (!allocation.finalAt || !allocation.maturityAt) {
            return null;
        }
        var finalAt = new Date(allocation.finalAt).getTime();
        var maturityAt = new Date(allocation.maturityAt).getTime();
        var withdrawalAt = new Date(allocation.withdrawalAt).getTime();
        var total = maturityAt - finalAt;
        if (total <= 0 || Number.isNaN(withdrawalAt)) {
            return null;
        }
        return Math.round(Math.max(0, Math.min(1, (withdrawalAt - finalAt) / total)) * 100);
    }

    // 납입건별 배분 바. 채움 = 이 배분 금액 ÷ 이 인출의 배분 합계
    function renderAllocations(detail) {
        var allocations = detail.allocations || [];
        var $container = $("#withdrawal-allocations").empty();

        if (!allocations.length) {
            $container.html('<div class="withdrawals-alloc-empty">' +
                (detail.status === "FAILED" ? "인출 실패 — " : "") + "저장된 배분 내역이 없습니다.</div>");
            return;
        }

        var total = allocations.reduce(function (sum, allocation) {
            return sum + Number(allocation.allocatedAmount || 0);
        }, 0);
        var depositNo = 0;
        var html = "";

        allocations.forEach(function (allocation) {
            var isEarnings = allocation.type === "EARNINGS_ONLY";
            var isEarly = allocation.type === "IMMATURE_PRINCIPAL_INCLUDED";
            var amount = Number(allocation.allocatedAmount || 0);
            var ratio = total > 0 ? amount / total : 0;
            var progress = calculateProgress(allocation);
            var title;
            var avail;
            var meta = [];

            if (isEarnings) {
                title = "수익금 <small>· 의무유지기간 비대상</small>";
                avail = "";
            } else {
                depositNo += 1;
                title = "납입 " + depositNo + " <small>· 납입 " + escapeHtml(formatDate(allocation.finalAt)) + "</small>";
                avail = "<span>인출가능일</span><span>" + escapeHtml(formatDate(allocation.maturityAt)) + "</span>";
                if (allocation.productName) {
                    meta.push(escapeHtml(allocation.productName + (allocation.ticker ? " (" + allocation.ticker + ")" : "")));
                }
                if (progress != null) {
                    meta.push("인출 시점 기준 " + progress + "% 경과");
                }
            }
            if (allocation.exchangeId != null) {
                meta.push("환전건 #" + escapeHtml(allocation.exchangeId));
            }

            html += '<div class="adm-alloc-item' + (isEarly ? " is-early" : "") + '" data-allocation-id="' +
                escapeHtml(allocation.allocationId) + '" data-ratio="' + ratio + '">' +
                '<div class="adm-alloc-head"><span class="adm-alloc-name">' + title + "</span>" +
                '<span class="adm-alloc-avail">' + avail +
                chip(STATUS.withdrawalTypeTone(allocation.type), STATUS.withdrawalAllocLabel(allocation.type),
                    STATUS.withdrawalTypeLabel(allocation.type)) + "</span></div>" +
                '<div class="adm-alloc-bar"><div class="adm-alloc-fill" style="transform:scaleX(' + ratio + ')"></div>' +
                '<span class="adm-alloc-amt">' + formatAmount(amount) +
                " <small>/ " + formatAmount(total) + "</small></span></div>" +
                (meta.length ? '<div class="adm-alloc-meta">' + meta.join(" · ") + "</div>" : "") +
                "</div>";
        });

        $container.html('<div class="adm-alloc">' + html + "</div>");
    }

    function renderDrawer(detail) {
        var meta = accountMetaByNo[String(detail.riaAccountNo || "-")] || {};
        var kind = STATUS.withdrawalKind(detail);

        $("#withdrawal-detail-title").text((MARIA.fmt.maskName(detail.customerName) || "-") + " 님 인출");
        $("#withdrawal-account-no").text(detail.riaAccountNo ? MARIA.fmt.hyphenateAccountNo(detail.riaAccountNo) : "-");

        $("#withdrawal-detail-kind").html(kindChip(detail));
        $("#withdrawal-detail-status").html(statusChip(detail.status));
        $("#withdrawal-requested-amount").text(detail.requestedAmount == null ? "-" : formatAmount(detail.requestedAmount) + "원");
        $("#withdrawal-processed-at").text(formatDateTime(detail.processedAt));

        renderAllocations(detail);

        $("#withdrawal-early-warning").prop("hidden", !(detail.earlyWithdrawal || kind === "EARLY"));
        $("#withdrawal-account-benefit-warning").prop("hidden", meta.benefit !== "IMPOSSIBLE");

        // 원본에 없는 기존 요소: 인출 상세
        $("#withdrawal-customer-name").text(MARIA.fmt.maskName(detail.customerName) || "-");
        $("#withdrawal-account-opened-at").text(formatDateTime(meta.openedAt));
        $("#withdrawal-current-balance").text(meta.amount == null ? "-" : formatAmount(meta.amount) + "원");
        $("#withdrawal-destination-account").text(
            detail.destinationAccountNo ? MARIA.fmt.hyphenateAccountNo(detail.destinationAccountNo) : "-"
        );
        $("#withdrawal-early-result").text(detail.earlyWithdrawal ? "발생" : "없음");
        $("#withdrawal-earnings-amount").text(formatAmount(detail.earningsAmount) + "원");
        $("#withdrawal-matured-amount").text(formatAmount(detail.maturedPrincipalAmount) + "원");
        $("#withdrawal-immature-amount").text(formatAmount(detail.immaturePrincipalAmount) + "원");

        setFold(false);
        $("#withdrawal-drawer-backdrop, #withdrawal-detail-drawer").addClass("is-open");
        $("#withdrawal-detail-drawer").attr("aria-hidden", "false");
        $("#withdrawal-detail-drawer .adm-panel-body").scrollTop(0);
        $("#withdrawal-drawer-close").trigger("focus");
    }

    function closeDrawer() {
        if (!$("#withdrawal-detail-drawer").hasClass("is-open")) {
            return;
        }
        $("#withdrawal-drawer-backdrop, #withdrawal-detail-drawer").removeClass("is-open");
        $("#withdrawal-detail-drawer").attr("aria-hidden", "true");
        var $row = $("#withdrawal-list tr.is-selected").removeClass("is-selected");
        if ($row.length) {
            $row.trigger("focus");
        }
        selectedWithdrawalId = null;
    }

    // 선택한 인출 건의 계좌 인출 내역: GET /api/admin/withdrawals/accounts/{accountId}
    function accountHistoryState(text) {
        $("#withdrawal-account-history").html('<tr class="is-state"><td colspan="4">' + escapeHtml(text) + "</td></tr>");
    }
    function loadAccountWithdrawals(withdrawalId, accountId) {
        $("#withdrawal-account-count").text("");
        if (!accountId) {
            accountHistoryState("-");
            return;
        }
        accountHistoryState("불러오는 중...");
        MARIA.auth.ajax({ url: "/api/admin/withdrawals/accounts/" + accountId, method: "GET" })
            .done(function (res) {
                if (Number(selectedWithdrawalId) !== Number(withdrawalId)) {
                    return;
                }
                var rows = res.data || [];
                $("#withdrawal-account-count").text("· " + rows.length + "건");
                if (!rows.length) {
                    accountHistoryState("인출 내역이 없습니다.");
                    return;
                }
                $("#withdrawal-account-history").html(rows.map(function (r, i) {
                    var current = Number(r.withdrawalId) === Number(withdrawalId) ? ' class="is-selected"' : "";
                    return "<tr" + current + '><td class="adm-col-no">' + (i + 1) + "</td>" +
                        '<td class="is-r">' + escapeHtml(formatAmount(r.requestedAmount)) + "</td>" +
                        '<td class="is-c">' + statusChip(r.status) + "</td>" +
                        "<td>" + escapeHtml(formatDateTime(r.processedAt)) + "</td></tr>";
                }).join(""));
            })
            .fail(function (xhr) {
                if (xhr.status !== 401 && Number(selectedWithdrawalId) === Number(withdrawalId)) {
                    accountHistoryState("계좌 인출 내역을 불러오지 못했습니다.");
                }
            });
    }

    function openDrawer(withdrawalId) {
        MARIA.auth.ajax({
            url: "/api/admin/withdrawals/" + withdrawalId,
            method: "GET"
        })
            .done(function (res) {
                if (Number(selectedWithdrawalId) !== Number(withdrawalId)) {
                    return;
                }
                renderDrawer(res.data || {});
                loadAccountWithdrawals(withdrawalId, (res.data || {}).accountId);
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                MARIA.ui.showError((xhr.responseJSON && xhr.responseJSON.message) || "인출 배분 내역을 불러오지 못했습니다.");
            });
    }

    function selectRow($row) {
        selectedWithdrawalId = Number($row.data("withdrawal-id"));
        $("#withdrawal-list tr[data-withdrawal-id]").removeClass("is-selected");
        $row.addClass("is-selected");
        openDrawer(selectedWithdrawalId);
    }

    $("#withdrawal-list").on("click", "tr[data-withdrawal-id]", function () {
        selectRow($(this));
    });
    $("#withdrawal-list").on("keydown", "tr[data-withdrawal-id]", function (event) {
        if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            selectRow($(this));
        }
    });

    $("#withdrawal-drawer-close, #withdrawal-drawer-backdrop").on("click", closeDrawer);

    // 인출 상세 접기 (처음엔 접힘, 패널을 새로 열 때마다 다시 접는다)
    function setFold(open) {
        $("#withdrawal-fold-toggle").attr("aria-expanded", open ? "true" : "false");
        $("#withdrawal-fold-body").prop("hidden", !open);
    }
    $("#withdrawal-fold-toggle").on("click", function () {
        setFold($(this).attr("aria-expanded") !== "true");
    });

    // ---------- 조회 조건 ----------
    function resetFilters() {
        $("#withdrawal-keyword").val("");
        $("#withdrawal-filter-status").val("");
        $("#withdrawal-filter-kind").val("");
        loadWithdrawals();
    }

    $("#withdrawal-search-button").on("click", loadWithdrawals);

    $("#withdrawal-keyword").on("keydown", function (event) {
        if (event.key === "Enter") {
            loadWithdrawals();
        }
    });

    // 처리 상태는 서버 조건이라 바꾸면 다시 조회하고, 계좌 구분은 화면에서만 거른다.
    $("#withdrawal-filter-status").on("change", loadWithdrawals);
    $("#withdrawal-filter-kind").on("change", function () {
        closeDrawer();
        applyClientFilters();
    });

    $("#withdrawal-filter-reset").on("click", resetFilters);
    $("#withdrawal-list").on("click", ".withdrawals-empty-reset", resetFilters);
    $("#withdrawal-list").on("click", ".withdrawals-retry", loadWithdrawals);

    $("#withdrawal-page-size").on("click", "button", function () {
        var size = parseInt($(this).attr("data-size"), 10);
        if (!size || size === PAGE_SIZE) {
            return;
        }
        PAGE_SIZE = size;
        $("#withdrawal-page-size button").removeClass("is-on");
        $(this).addClass("is-on");
        closeDrawer();
        currentPage = 0;
        renderRows();
        renderPagination();
    });

    $(document).on("maria:system-clock-changed", loadBusinessToday);

    var deepLinkAccountNo = MARIA.deeplink.accountNoFromUrl();
    if (deepLinkAccountNo) {
        $("#withdrawal-keyword").val(deepLinkAccountNo);
    }

    MARIA.tableSort.bind(".withdrawals-table", function (key, dir) {
        sortSpec = { key: key, dir: dir };
        applyClientFilters();
    });

    loadBusinessToday();
    loadAccountMeta();
    loadWithdrawals();
});
