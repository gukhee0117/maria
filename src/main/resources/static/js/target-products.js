$(function () {
    var STATUS = MARIA.status; // 구분 한글 표기는 js/admin/status-labels.js 의 공통 매핑
    var PAGE_SIZE = 20;
    var currentPage = 0;
    var sortSpec = null; // 머리글로 고른 정렬(서버가 전체 데이터에서 정렬)
    var newJudgementIds = [];
    var SKELETON_ROWS = 8;
    var SKELETON_WIDTHS = ["62%", "70%", "48%", "54%", "66%", "78%", "56%", "40%"];

    var KRW_FORMATTER = new Intl.NumberFormat("ko-KR");

    function pad(n) {
        return String(n).padStart(2, "0");
    }
    function formatDateTime(isoString) {
        var date = isoString ? new Date(isoString) : null;
        if (!date || isNaN(date.getTime())) {
            return "-";
        }
        return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate()) +
            " " + pad(date.getHours()) + ":" + pad(date.getMinutes());
    }
    // 금액은 숫자만 (단위 "원"은 열 이름·요약 단위가 말해 준다). 음수는 앞에 -.
    function formatAmount(amount) {
        var value = Number(amount) || 0;
        return (value < 0 ? "-" : "") + KRW_FORMATTER.format(Math.abs(value));
    }
    function escapeHtml(value) {
        return $("<div>").text(value == null ? "" : value).html();
    }
    function chip(tone, label, title) {
        return '<span class="adm-chip adm-chip-' + tone + '"' + (title ? ' title="' + escapeHtml(title) + '"' : "") + ">" +
            escapeHtml(label) + "</span>";
    }
    function canTriggerBatch() {
        // 배치 실행 API(POST /api/admin/external-trade-sync/jobs)는 SETTLEMENT 권한만 열려 있다
        var admin = MARIA.auth.currentAdmin();
        return !!admin && admin.role === "SETTLEMENT";
    }
    function assetLabel(item) {
        if (item.stockType === "FUND") {
            return escapeHtml(item.fundName || "-");
        }
        return escapeHtml(item.ticker || "-");
    }

    function judgementReason(item) {
        if (item.stockType !== "FUND") {
            return "해외주식·ETF·ETN은 비중요건 없이 전부 대상입니다.";
        }

        var ratio = item.foreignStockRatio;
        var ratioMet = ratio != null && Number(ratio) >= 60;
        var ratioText = ratio != null
            ? "해외주식비중 " + Number(ratio).toFixed(2) + "% (60% 이상 필요)"
            : "해외주식비중 정보 없음";

        var inceptionMet = false;
        var inceptionText = "설정일 정보 없음";
        if (item.inceptionDate) {
            var inceptionDate = new Date(item.inceptionDate);
            var gracePeriodEnd = new Date(item.tradeDate);
            gracePeriodEnd.setMonth(gracePeriodEnd.getMonth() - 1);
            inceptionMet = inceptionDate <= gracePeriodEnd;
            inceptionText = "설정일 " + item.inceptionDate + " (설정 1개월 경과 필요)";
        }

        return (
            (ratioMet ? "✓ " : "✗ ") + ratioText + "\n" +
            (inceptionMet ? "✓ " : "✗ ") + inceptionText
        );
    }

    // ---------- 조회조건의 선택 목록: 공통 매핑에서 채운다 ----------
    function fillOptions(selector, labels) {
        var $select = $(selector);
        Object.keys(labels).forEach(function (value) {
            $select.append($("<option>", { value: value, text: labels[value] }));
        });
    }
    fillOptions("#tpFilterStockType", STATUS.STOCK_TYPE_LABEL);
    fillOptions("#tpFilterTradeType", STATUS.TRADE_TYPE_LABEL);

    // ---------- 표 ----------
    function stateRow(className, innerHtml, fill) {
        return '<tr class="is-state ' + (fill ? "is-state-fill " : "") + className + '"><td colspan="9">' + innerHtml + "</td></tr>";
    }

    function renderLoading() {
        var html = "";
        for (var r = 0; r < SKELETON_ROWS; r++) {
            html += '<tr class="is-state tp-loading" aria-label="불러오는 중"><td colspan="9"><div class="adm-skel-row">' +
                '<div class="adm-skel-cell"></div>';
            for (var c = 0; c < 8; c++) {
                html += '<div class="adm-skel-cell"><span style="width:' + SKELETON_WIDTHS[(c + r) % SKELETON_WIDTHS.length] +
                    ';animation-delay:' + ((c + r) % 5) * 0.06 + 's"></span></div>';
            }
            html += "</div></td></tr>";
        }
        $("#tpTableBody").html(html);
    }

    function renderTable(items) {
        var $body = $("#tpTableBody").empty();
        if (!items || items.length === 0) {
            $body.append(stateRow("tp-empty",
                '<div class="adm-state-empty">' +
                '<img src="/images/admin/char-front.png" alt="">' +
                '<div class="adm-state-empty-title">탐지된 이벤트가 없습니다.</div>' +
                '<div class="adm-state-empty-desc">조회조건을 바꾸거나 초기화한 뒤 다시 조회해 주세요.</div>' +
                '<button type="button" class="adm-btn tp-empty-reset">조건 초기화</button>' +
                "</div>", true));
            return;
        }
        items.forEach(function (item, index) {
            var reason = judgementReason(item);
            var targetBadge = item.isTarget ? chip("ok", "대상", reason) : chip("off", "비대상", reason);
            var isNew = newJudgementIds.indexOf(item.judgementId) !== -1;
            var newBadge = isNew ? '<span class="tp-new-badge">NEW</span>' : "";
            var netBuyClass = Number(item.netBuyAmount) < 0 ? " tp-negative" : "";
            $body.append(
                "<tr" + (isNew ? ' class="tp-new-row"' : "") + ">" +
                '<td class="adm-col-no">' + (currentPage * PAGE_SIZE + index + 1) + "</td>" +
                "<td><span>" + formatDateTime(item.judgedAt) + "</span></td>" +
                "<td><span>" + escapeHtml(MARIA.fmt.maskName(item.customerName) || "-") + newBadge + "</span></td>" +
                "<td><span>" + escapeHtml(STATUS.stockTypeLabel(item.stockType)) + "</span></td>" +
                "<td><span>" + assetLabel(item) + "</span></td>" +
                '<td class="is-c">' + chip(STATUS.tradeTypeTone(item.tradeType), STATUS.tradeTypeLabel(item.tradeType)) + "</td>" +
                '<td class="is-r"><span>' + formatAmount(item.amount) + "</span></td>" +
                '<td class="is-r"><span class="' + netBuyClass.trim() + '">' + formatAmount(item.netBuyAmount) + "</span></td>" +
                '<td class="is-c">' + targetBadge + "</td>" +
                "</tr>"
            );
        });
    }

    function renderPagination(page) {
        var $pagination = $("#tpPagination").empty();
        if (!page || page.totalPages <= 1) {
            return;
        }

        var current = page.page;
        var totalPages = page.totalPages;
        var BLOCK_SIZE = 10;
        var blockStart = Math.floor(current / BLOCK_SIZE) * BLOCK_SIZE;
        var blockEnd = Math.min(totalPages - 1, blockStart + BLOCK_SIZE - 1);

        function addButton(label, targetPage, isDisabled, isActive) {
            var classes = "adm-page-btn" + (isActive ? " is-active" : "") + (/^[0-9]+$/.test(label) ? "" : " is-nav");
            var $btn = $('<button type="button" class="' + classes + '">' + label + "</button>");
            $btn.prop("disabled", isDisabled || isActive);
            if (!isDisabled && !isActive) {
                $btn.on("click", function () {
                    loadTargetProducts(targetPage);
                });
            }
            $pagination.append($btn);
        }

        if (blockStart > 0) {
            addButton("이전", blockStart - 1, false, false);
        }
        for (var i = blockStart; i <= blockEnd; i++) {
            addButton(String(i + 1), i, false, i === current);
        }
        addButton("다음", blockEnd + 1, blockEnd === totalPages - 1, false);
    }

    // ---------- 요약 ----------
    function renderSummary(summary) {
        $("#kpiTodayJudgement").text(KRW_FORMATTER.format(summary.todayJudgementCount));
        $("#kpiTodayTarget").text(KRW_FORMATTER.format(summary.todayTargetCount));
        $("#kpiTodayTargetAmount").text(formatAmount(summary.todayTargetNetBuyAmount));
    }

    function loadSummary() {
        MARIA.auth.ajax({
            url: "/api/admin/target-products/summary",
            method: "GET"
        })
            .done(function (res) {
                renderSummary(res.data);
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
            });
    }

    // ---------- 조회 ----------
    function getFilterParams() {
        var params = {};
        var customerName = $("#tpFilterCustomerName").val();
        if (customerName) {
            params.customerName = customerName;
        }
        var stockType = $("#tpFilterStockType").val();
        if (stockType) {
            params.stockType = stockType;
        }
        var isTarget = $("#tpFilterIsTarget").val();
        if (isTarget) {
            params.isTarget = isTarget;
        }
        var tradeType = $("#tpFilterTradeType").val();
        if (tradeType) {
            params.tradeType = tradeType;
        }
        return params;
    }

    // 체크 칸: 옛 요약 카드(오늘 판정 / 오늘 상속·증여)가 보내던 파라미터를 그대로 보낸다
    function getCardFilterParams() {
        var params = {};
        var todayOnly = $("#tpFilterTodayOnly").prop("checked");
        var giftOnly = $("#tpFilterGiftOnly").prop("checked");
        if (todayOnly) {
            params.todayOnly = true;
        }
        if (giftOnly) {
            params.inheritanceGiftOnly = true;
        }
        return params;
    }

    function loadTargetProducts(page) {
        var targetPage = page || 0;
        var requestData = $.extend({ page: targetPage, size: PAGE_SIZE }, getFilterParams(), getCardFilterParams(), sortSpec ? { sort: sortSpec.key, direction: sortSpec.dir } : {});
        renderLoading();
        MARIA.auth.ajax({
            url: "/api/admin/target-products",
            method: "GET",
            data: requestData
        })
            .done(function (res) {
                currentPage = res.data.page || 0;
                $("#tpTotalCount").text(KRW_FORMATTER.format(res.data.totalElements || 0));
                renderTable(res.data.content);
                renderPagination(res.data);
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                var message = "목록을 불러오지 못했습니다.";
                if (xhr.responseJSON && xhr.responseJSON.message) {
                    message = xhr.responseJSON.message;
                }
                $("#tpTotalCount").text("-");
                $("#tpPagination").empty();
                $("#tpTableBody").html(stateRow("tp-error",
                    '<div class="adm-state-error">' +
                    '<div class="adm-state-error-text">' +
                    '<div class="adm-state-error-title">' + escapeHtml(message) + "</div>" +
                    '<div class="adm-state-error-desc">잠시 후 다시 시도해 주세요.</div>' +
                    "</div>" +
                    '<button type="button" class="adm-btn tp-retry" data-page="' + targetPage + '">다시 시도</button>' +
                    "</div>", true));
            });
    }

    function resetFilters() {
        $("#tpFilterCustomerName").val("");
        $("#tpFilterStockType").val("");
        $("#tpFilterIsTarget").val("");
        $("#tpFilterTradeType").val("");
        $("#tpFilterTodayOnly, #tpFilterGiftOnly").prop("checked", false);
        loadTargetProducts(0);
    }

    $("#tpFilterSubmit").on("click", function () {
        loadTargetProducts(0);
    });
    MARIA.tableSort.bind(".tp-judgement-table", function (key, dir) {
        sortSpec = { key: key, dir: dir };
        loadTargetProducts(0);
    });
    $("#tpFilterReset").on("click", resetFilters);
    $("#tpTableBody").on("click", ".tp-empty-reset", resetFilters);
    $("#tpTableBody").on("click", ".tp-retry", function () {
        loadSummary();
        loadTargetProducts(parseInt($(this).attr("data-page"), 10) || 0);
    });
    $("#tpFilterCustomerName").on("keypress", function (e) {
        if (e.which === 13) {
                loadTargetProducts(0);
        }
    });

    $("#tpPageSize").on("click", "button", function () {
        var size = parseInt($(this).attr("data-size"), 10);
        if (!size || size === PAGE_SIZE) {
            return;
        }
        PAGE_SIZE = size;
        $("#tpPageSize button").removeClass("is-on");
        $(this).addClass("is-on");
        loadTargetProducts(0);
    });

    // ---------- 배치 수동 실행 ----------
    // 로그인 확인(/api/admin/me)이 끝난 뒤에 버튼을 보인다 (같은 요청을 다시 보내지 않고 결과만 받는다)
    MARIA.auth.requireAuth().done(function () {
        $("#tpBatchTriggerGroup").prop("hidden", !canTriggerBatch());
    });

    function closeBatchModal() {
        $("#tpBatchModal").prop("hidden", true);
        $("#tpTriggerBatch").trigger("focus");
    }
    $("#tpTriggerBatch").on("click", function () {
        if (!canTriggerBatch()) return;
        $("#tpBatchModal").prop("hidden", false);
        $("#tpBatchModalConfirm").trigger("focus");
    });
    $("#tpBatchModalClose, #tpBatchModalCancel").on("click", closeBatchModal);
    $("#tpBatchModal").on("click", function (event) {
        if (event.target === this) {
            closeBatchModal();
        }
    });
    $(document).on("keydown", function (event) {
        if (event.key === "Escape" && !$("#tpBatchModal").prop("hidden")) {
            closeBatchModal();
        }
    });

    $("#tpBatchModalConfirm").on("click", function () {
        if (!canTriggerBatch()) return;
        $("#tpBatchModal").prop("hidden", true);
        $("#tpTriggerBatch").prop("disabled", true);
        $("#tpBatchTriggerResult").text("실행 중...");
        MARIA.auth.ajax({ url: "/api/admin/external-trade-sync/jobs", method: "POST" })
            .done(function (res) {
                var r = res.data;
                newJudgementIds = r.newJudgementIds || [];
                var text = "완료 · 신규 " + r.newJudgementCount + "건 · 스킵 " + r.skippedJudgementCount + "건";
                if (r.failedCustomerCount > 0) {
                    text += " · 실패 " + r.failedCustomerCount + "명";
                }
                $("#tpBatchTriggerResult").text(text);
                MARIA.ui.setStatus("외부 감시 배치를 실행했어요");
                loadSummary();
                loadTargetProducts(0);
            })
            .fail(function (xhr) {
                if (xhr.status === 401) return;
                $("#tpBatchTriggerResult").text("실패");
                MARIA.ui.showError((xhr.responseJSON && xhr.responseJSON.message) || "배치 실행에 실패했습니다.");
            })
            .always(function () {
                $("#tpTriggerBatch").prop("disabled", false);
            });
    });

    loadSummary();
    loadTargetProducts(0);
    // ---------- 판정 실패(영구 실패) 목록 패널 ----------
    var failPage = 0;
    var failSort = null; // 머리글로 고른 정렬(서버가 전체 데이터에서 정렬)
    function failState(text) {
        $("#tpFailBody").html('<tr class="is-state"><td colspan="7"><div class="tp-fail-empty">' + escapeHtml(text) + "</div></td></tr>");
    }
    function loadFailures(page) {
        failPage = page;
        failState("불러오는 중...");
        MARIA.auth.ajax({ url: "/api/admin/target-products/failures", method: "GET", data: $.extend({ page: page, size: 20 }, failSort ? { sort: failSort.key, direction: failSort.dir } : {}) })
            .done(function (res) {
                var d = res.data || {};
                var rows = d.content || [];
                $("#tpFailTotal").text("총 " + (d.totalElements || 0) + "건");
                if (!rows.length) {
                    failState("판정 실패 건이 없습니다.");
                } else {
                    $("#tpFailBody").html(rows.map(function (r, i) {
                        return "<tr>" + '<td class="adm-col-no">' + (page * 20 + i + 1) + "</td>" +
                            "<td>" + escapeHtml(MARIA.fmt.maskName(r.customerName) || "-") + "</td>" +
                            '<td class="is-c">' + escapeHtml(r.tradeDate || "-") + "</td>" +
                            '<td class="is-r">' + escapeHtml(r.failureCount) + "</td>" +
                            '<td><span title="' + escapeHtml(r.lastError || "") + '">' + escapeHtml(r.lastError || "-") + "</span></td>" +
                            "<td>" + escapeHtml(formatDateTime(r.firstFailedAt).slice(5)) + "</td>" +
                            "<td>" + escapeHtml(formatDateTime(r.lastFailedAt).slice(5)) + "</td></tr>";
                    }).join(""));
                }
                var pages = d.totalPages || 0;
                $("#tpFailPager").html(pages > 1
                    ? '<button type="button" class="adm-btn" data-fail-page="' + (page - 1) + '"' + (page <= 0 ? " disabled" : "") + ">이전</button>" +
                      "<span>" + (page + 1) + " / " + pages + "</span>" +
                      '<button type="button" class="adm-btn" data-fail-page="' + (page + 1) + '"' + (page >= pages - 1 ? " disabled" : "") + ">다음</button>"
                    : "");
            })
            .fail(function () { failState("판정 실패 목록을 불러오지 못했습니다."); });
    }
    function openFail() {
        $("#tpFailScrim, #tpFailPanel").addClass("is-open");
        $("#tpFailPanel").attr("aria-hidden", "false");
        loadFailures(0);
        $("#tpFailClose").trigger("focus");
    }
    function closeFail() {
        $("#tpFailScrim, #tpFailPanel").removeClass("is-open");
        $("#tpFailPanel").attr("aria-hidden", "true");
        $("#tpFailOpen").trigger("focus");
    }
    $("#tpFailOpen").on("click", openFail);
    MARIA.tableSort.bind(".tp-fail-table", function (key, dir) {
        failSort = { key: key, dir: dir };
        loadFailures(0);
    });
    $("#tpFailClose, #tpFailScrim").on("click", closeFail);
    $("#tpFailPager").on("click", "[data-fail-page]", function () {
        loadFailures(parseInt($(this).attr("data-fail-page"), 10) || 0);
    });

});
