$(function () {
    var STATUS = MARIA.status; // 보유 상태·매수가능 여부 표기는 js/admin/status-labels.js 의 공통 매핑

    var QTY_FORMATTER = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 4 });
    var KRW_FORMATTER = new Intl.NumberFormat("ko-KR");

    var investments = [];
    var selectedAccountId = null;
    var PAGE_SIZE = 20;
    var currentPage = 0;
    var sortSpec = null; // 머리글로 고른 정렬(서버가 전체 데이터에서 정렬)
    var totalCount = 0;
    var totalCashAmount = null; // 예탁금 비중의 분모 (summary.totalCashAmount)
    var businessToday = null;
    var recentDays = 7;
    var REFRESH_MS = 10000;
    var listRequestSeq = 0; // 늦게 도착한 이전 목록 응답은 버린다
    var appliedFilterParams = null; // 마지막으로 조회한 조건(자동 새로고침은 이 조건을 그대로 쓴다)
    var SKELETON_ROWS = 8;
    var SKELETON_WIDTHS = ["62%", "70%", "48%", "54%", "66%", "78%"];
    var TYPE_LABEL = STATUS.DOMESTIC_TYPE_LABEL;

    // ---------- 서식 ----------
    function pad(n) {
        return String(n).padStart(2, "0");
    }
    function escapeHtml(value) {
        return $("<div>").text(value == null ? "" : value).html();
    }
    function formatDateTime(isoString) {
        var date = isoString ? new Date(isoString) : null;
        if (!date || isNaN(date.getTime())) {
            return "-";
        }
        return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate()) +
            " " + pad(date.getHours()) + ":" + pad(date.getMinutes());
    }
    // 금액·수량은 숫자만 (단위는 열 이름·요약 단위가 말해 준다)
    function formatAmount(amount) {
        return amount == null ? "-" : KRW_FORMATTER.format(Math.trunc(amount));
    }
    function formatQty(qty) {
        return qty == null ? "-" : QTY_FORMATTER.format(qty);
    }
    // 큰 금액은 원본처럼 "186억 4,230만" 으로 (원 단위 아래는 버림)
    function formatEok(amount) {
        var value = Math.trunc(Number(amount) || 0);
        var eok = Math.floor(value / 100000000);
        var man = Math.floor((value % 100000000) / 10000);
        var parts = [];
        if (eok > 0) {
            parts.push(KRW_FORMATTER.format(eok) + "억");
        }
        if (man > 0) {
            parts.push(KRW_FORMATTER.format(man) + "만");
        }
        return parts.length ? parts.join(" ") : KRW_FORMATTER.format(value);
    }
    // 예탁금 비중 = 이 계좌 예탁금 ÷ 전체 예탁금 합계 (요약 API 의 totalCashAmount). 새 요청 없음.
    function cashRatioText(cashAmount) {
        if (totalCashAmount == null || Number(totalCashAmount) <= 0 || cashAmount == null) {
            return "-";
        }
        return (Number(cashAmount) / Number(totalCashAmount) * 100).toFixed(1) + "%";
    }
    function chip(tone, label, title) {
        return '<span class="adm-chip adm-chip-' + tone + '"' + (title ? ' title="' + escapeHtml(title) + '"' : "") + ">" +
            escapeHtml(label) + "</span>";
    }
    function buyableChip(key) {
        return chip(STATUS.buyableTone(key), STATUS.buyableLabel(key));
    }
    function statusChip(status) {
        return chip(STATUS.domesticStatusTone(status), STATUS.domesticStatusLabel(status));
    }
    function purchasabilityReason(h) {
        if (h.type !== "FUND") {
            return "✓ 국내주식은 비중요건 없이 매수가능합니다.";
        }
        var ratio = h.domesticStockRatio;
        var ratioMet = ratio != null && Number(ratio) >= 80;
        var ratioText = ratio != null
            ? "국내주식비중 " + Number(ratio).toFixed(2) + "% (80% 이상 필요)"
            : "국내주식비중 정보 없음";

        var inceptionMet;
        if (h.inceptionDate && businessToday) {
            var inceptionDate = new Date(h.inceptionDate);
            var gracePeriodEnd = new Date(businessToday);
            gracePeriodEnd.setMonth(gracePeriodEnd.getMonth() - 1);
            inceptionMet = inceptionDate <= gracePeriodEnd;
        } else {
            inceptionMet = h.currentlyPurchasable === true;
        }
        var inceptionText = h.inceptionDate
            ? "설정일 " + h.inceptionDate + " (설정 1개월 경과 필요)"
            : "설정일 정보 없음";

        return (
            (ratioMet ? "✓ " : "✗ ") + ratioText + "\n" +
            (inceptionMet ? "✓ " : "✗ ") + inceptionText
        );
    }

    // ---------- 계좌 목록 ----------
    function stateRow(className, innerHtml, fill) {
        return '<tr class="is-state ' + (fill ? "is-state-fill " : "") + className + '"><td colspan="7">' + innerHtml + "</td></tr>";
    }

    function renderLoading() {
        var html = "";
        for (var r = 0; r < SKELETON_ROWS; r++) {
            html += '<tr class="is-state di-loading" aria-label="불러오는 중"><td colspan="7"><div class="adm-skel-row">' +
                '<div class="adm-skel-cell"></div>';
            for (var c = 0; c < 6; c++) {
                html += '<div class="adm-skel-cell"><span style="width:' + SKELETON_WIDTHS[(c + r) % SKELETON_WIDTHS.length] +
                    ';animation-delay:' + ((c + r) % 5) * 0.06 + 's"></span></div>';
            }
            html += "</div></td></tr>";
        }
        $("#diListBody").html(html);
    }

    function renderList() {
        var $body = $("#diListBody").empty();
        if (investments.length === 0) {
            $body.append(stateRow("di-empty",
                '<div class="adm-state-empty">' +
                '<img src="/images/admin/char-front.png" alt="">' +
                '<div class="adm-state-empty-title">국내투자 계좌가 없습니다.</div>' +
                '<div class="adm-state-empty-desc">조회조건을 바꾸거나 초기화한 뒤 다시 조회해 주세요.</div>' +
                '<button type="button" class="adm-btn di-empty-reset">조건 초기화</button>' +
                "</div>", true));
            return;
        }
        investments.forEach(function (item, index) {
            var selected = item.accountId === selectedAccountId ? " is-selected" : "";
            // 목록 응답에는 거래제한·정지 보유 여부만 있어 "거래제한·정지" 로 보인다 (거래제한/거래정지 구분은 상세에서)
            var buyable = item.hasRestrictedHolding ? "RESTRICTED_ANY" : "AVAILABLE";
            $body.append(
                '<tr class="is-clickable' + selected + '" tabindex="0" data-account-id="' + item.accountId + '">' +
                '<td class="adm-col-no">' + (currentPage * PAGE_SIZE + index + 1) + "</td>" +
                "<td><span>" + escapeHtml(MARIA.fmt.hyphenateAccountNo(item.accountNo)) + "</span></td>" +
                "<td><span>" + escapeHtml(MARIA.fmt.maskName(item.customerName) || "") + "</span></td>" +
                '<td class="is-r is-strong"><span>' + formatAmount(item.cashAmount) + "</span></td>" +
                '<td class="is-r"><span>' + cashRatioText(item.cashAmount) + "</span></td>" +
                '<td class="is-r"><span>' + KRW_FORMATTER.format(item.holdingCount || 0) + "</span></td>" +
                '<td class="is-c">' + buyableChip(buyable) + "</td>" +
                "</tr>"
            );
        });
    }

    function renderPagination() {
        var $pagination = $("#diPagination").empty();
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
                    loadInvestments(targetPage);
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

    function getFilterParams() {
        var params = {};
        var keyword = $("#diFilterCustomerName").val();
        if (keyword) {
            params.keyword = keyword;
        }
        var status = $("#diFilterStatus").val();
        if (status === "AVAILABLE") {
            params.hasRestrictedHolding = false; // 매수가능 = 거래제한·정지 보유가 없는 계좌
        } else if (status) {
            params.status = status;
        }
        // 옛 요약 칸(매수불가 종목 보유 / 최근 매수 있음·없음)을 눌러 보내던 파라미터를 그대로 보낸다
        if ($("#diFilterUnpurchasable").prop("checked")) {
            params.hasUnpurchasableHolding = true;
        }
        var recentBuy = $("#diFilterRecentBuy").val();
        if (recentBuy) {
            params.hasRecentBuy = recentBuy === "true";
            params.recentBuyDays = recentDays;
        }
        return params;
    }

    // silent: 자동 새로고침. 로딩 표시·상세 닫기 없이 같은 조건·같은 페이지를 다시 받아 숫자만 바꾼다(실패하면 화면 유지)
    function loadInvestments(page, silent) {
        var targetPage = page || 0;
        var $content = $(".content");
        var scrollTop = $content.scrollTop();
        var seq = ++listRequestSeq;
        if (!silent) {
            closeDetail();
            renderLoading();
            appliedFilterParams = getFilterParams();
        }
        MARIA.auth.ajax({
            url: "/api/admin/domestic-investments",
            method: "GET",
            data: $.extend({ page: targetPage, size: PAGE_SIZE }, appliedFilterParams, sortSpec ? { sort: sortSpec.key, direction: sortSpec.dir } : {})
        })
            .done(function (res) {
                if (seq !== listRequestSeq) {
                    return;
                }
                var data = res.data || {};
                investments = data.content || [];
                currentPage = data.page || 0;
                totalCount = data.totalElements || 0;
                if (!silent) {
                    selectedAccountId = null;
                }
                $("#diTotalCount").text(KRW_FORMATTER.format(totalCount));
                renderList();
                renderPagination();
                $content.scrollTop(scrollTop);
            })
            .fail(function (xhr) {
                if (xhr.status === 401 || silent || seq !== listRequestSeq) {
                    return;
                }
                var message = (xhr.responseJSON && xhr.responseJSON.message) || "목록을 불러오지 못했습니다.";
                investments = [];
                totalCount = 0;
                $("#diTotalCount").text("-");
                $("#diPagination").empty();
                $("#diListBody").html(stateRow("di-error",
                    '<div class="adm-state-error">' +
                    '<div class="adm-state-error-text">' +
                    '<div class="adm-state-error-title">' + escapeHtml(message) + "</div>" +
                    '<div class="adm-state-error-desc">잠시 후 다시 시도해 주세요.</div>' +
                    "</div>" +
                    '<button type="button" class="adm-btn di-retry" data-page="' + targetPage + '">다시 시도</button>' +
                    "</div>", true));
            });
    }

    // ---------- 요약 ----------
    function renderSummary(summary) {
        $("#kpiTotalAccount").text(KRW_FORMATTER.format(summary.totalAccountCount || 0));
        $("#kpiTotalCash").text(formatEok(summary.totalCashAmount));
        $("#kpiRestrictedAccount").text(KRW_FORMATTER.format(summary.restrictedAccountCount || 0));
        // 조회조건으로 옮긴 기존 요소의 건수
        $("#kpiUnpurchasable").text(summary.unpurchasableHoldingCount);
        $("#diRecentOptYes").text("있음 (" + summary.recentBuyAccountCount + "개)");
        $("#diRecentOptNo").text("없음 (" + summary.noRecentBuyAccountCount + "개)");
    }

    function loadSummary() {
        MARIA.auth.ajax({
            url: "/api/admin/domestic-investments/summary",
            method: "GET",
            data: { days: recentDays }
        })
            .done(function (res) {
                var summary = res.data || {};
                renderSummary(summary);
                totalCashAmount = summary.totalCashAmount;
                if (investments.length) {
                    renderList(); // 예탁금 비중을 다시 계산해 채운다
                }
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
            });
    }

    // ---------- 상세 패널 ----------
    function setTab(name) {
        $("#diDetailTabs .adm-tab").each(function () {
            var on = $(this).attr("data-tab") === name;
            $(this).toggleClass("is-on", on).attr("aria-selected", on ? "true" : "false");
        });
        $("#diDetailTabs [data-pane]").each(function () {
            $(this).prop("hidden", $(this).attr("data-pane") !== name);
        });
    }
    $("#diDetailTabs").on("click", ".adm-tab", function () {
        setTab($(this).attr("data-tab"));
    });

    function emptyRow(cols, text) {
        return '<tr class="is-state"><td colspan="' + cols + '"><div class="di-panel-empty">' + text + "</div></td></tr>";
    }

    function renderDetail(data, listItem) {
        var holdings = data.holdings || [];
        var trades = data.tradeHistory || [];
        var buyable = STATUS.domesticBuyable(holdings);

        $("#diDetailTitle").text((MARIA.fmt.maskName(data.customerName) || "-") + " 님 국내투자");
        $("#diDetailSub").text(data.accountNo ? MARIA.fmt.hyphenateAccountNo(data.accountNo) : "");
        $("#diDetailCash").text(data.cashAmount == null ? "-" : formatAmount(data.cashAmount) + "원");
        $("#diDetailRatio").text(cashRatioText(data.cashAmount));
        $("#diDetailCount").text((listItem ? listItem.holdingCount : holdings.length) + "종목");
        $("#diDetailBuyable").html(buyableChip(buyable));

        $("#diHoldingsBody").html(holdings.length ? holdings.map(function (h, i) {
            return "<tr>" +
                '<td class="adm-col-no">' + (i + 1) + "</td>" +
                '<td><span title="' + escapeHtml(h.ticker || "") + '">' + escapeHtml(h.name || "-") + "</span></td>" +
                '<td class="is-r"><span>' + formatQty(h.qty) + "</span></td>" +
                '<td class="is-r"><span>' + formatAmount(h.avgPurchasePrice) + "</span></td>" +
                '<td class="is-c">' + statusChip(h.status) + "</td>" +
                "</tr>";
        }).join("") : emptyRow(5, "보유종목이 없습니다."));

        $("#diExtraBody").html(holdings.length ? holdings.map(function (h, i) {
            return "<tr>" +
                '<td class="adm-col-no">' + (i + 1) + "</td>" +
                "<td><span>" + escapeHtml(h.name || "-") + "</span></td>" +
                "<td><span>" + escapeHtml(TYPE_LABEL[h.type] || h.type || "-") + "</span></td>" +
                '<td class="is-c"><span>' + formatDateTime(h.lastPurchaseDate) + "</span></td>" +
                '<td class="is-c">' + (h.currentlyPurchasable
                    ? chip("ok", "매수가능", purchasabilityReason(h))
                    : chip("fail", "매수불가", purchasabilityReason(h))) + "</td>" +
                "</tr>";
        }).join("") : emptyRow(5, "보유종목이 없습니다."));

        $("#diTradesBody").html(trades.length ? trades.map(function (t, i) {
            return "<tr>" +
                '<td class="adm-col-no">' + (i + 1) + "</td>" +
                "<td><span>" + formatDateTime(t.executedAt) + "</span></td>" +
                "<td><span>" + escapeHtml(t.stockCode || "-") + "</span></td>" +
                '<td class="is-c">' + chip(STATUS.tradeTypeTone(t.tradeType), STATUS.tradeTypeLabel(t.tradeType)) + "</td>" +
                '<td class="is-r"><span>' + formatQty(t.qty) + "</span></td>" +
                '<td class="is-r"><span>' + formatAmount(t.price) + "</span></td>" +
                "</tr>";
        }).join("") : emptyRow(6, "매매내역이 없습니다."));

        $("#diDetailState").prop("hidden", true).empty();
        $("#diDetailTabs").prop("hidden", false);
        $("#diFold").prop("hidden", false);
    }

    // 종목별 추가 정보 접기 (처음엔 접힘, 패널을 새로 열 때마다 다시 접는다)
    function setFold(open) {
        $("#diFoldToggle").attr("aria-expanded", open ? "true" : "false");
        $("#diFoldBody").prop("hidden", !open);
    }
    $("#diFoldToggle").on("click", function () {
        setFold($(this).attr("aria-expanded") !== "true");
    });

    function openPanel() {
        $("#diDetailScrim, #diDetailPanel").addClass("is-open");
        $("#diDetailPanel").attr("aria-hidden", "false");
        $("#diDetailBody").scrollTop(0);
        $("#diDetailClose").trigger("focus");
    }

    function closeDetail() {
        if (!$("#diDetailPanel").hasClass("is-open")) {
            return;
        }
        $("#diDetailScrim, #diDetailPanel").removeClass("is-open");
        $("#diDetailPanel").attr("aria-hidden", "true");
        var $row = $("#diListBody tr.is-selected").removeClass("is-selected");
        if ($row.length) {
            $row.trigger("focus");
        }
        selectedAccountId = null;
    }

    function loadDetail(accountId) {
        var item = investments.filter(function (i) { return i.accountId === accountId; })[0];
        setTab("holdings");
        $("#diDetailTitle").text(item ? MARIA.fmt.maskName(item.customerName) + " 님 국내투자" : "-");
        $("#diDetailSub").text(item ? MARIA.fmt.hyphenateAccountNo(item.accountNo) : "");
        $("#diDetailTabs").prop("hidden", true);
        $("#diFold").prop("hidden", true);
        setFold(false);
        $("#diDetailState").prop("hidden", false).removeClass("di-detail-state").html('<div class="di-panel-empty">불러오는 중...</div>');
        openPanel();
        MARIA.auth.ajax({
            url: "/api/admin/domestic-investments/" + accountId,
            method: "GET"
        })
            .done(function (res) {
                if (selectedAccountId !== accountId) {
                    return;
                }
                renderDetail(res.data || {}, item);
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                var message = (xhr.responseJSON && xhr.responseJSON.message) || "상세 정보를 불러오지 못했습니다.";
                $("#diDetailState").addClass("di-detail-state").html(
                    "<span>" + escapeHtml(message) + '</span><button type="button" class="adm-btn di-detail-retry" data-id="' + accountId + '">다시 시도</button>');
            });
    }

    function selectRow($row) {
        selectedAccountId = Number($row.data("account-id"));
        $("#diListBody tr[data-account-id]").removeClass("is-selected");
        $row.addClass("is-selected");
        loadDetail(selectedAccountId);
    }

    $("#diListBody").on("click", "tr[data-account-id]", function () {
        selectRow($(this));
    });
    $("#diListBody").on("keydown", "tr[data-account-id]", function (event) {
        if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            selectRow($(this));
        }
    });
    $("#diDetailState").on("click", ".di-detail-retry", function () {
        loadDetail(parseInt($(this).attr("data-id"), 10));
    });
    $("#diDetailClose, #diDetailScrim").on("click", closeDetail);

    // ---------- 조회 조건 ----------
    $("#diFilterSubmit").on("click", function () {
        loadInvestments(0);
    });
    function resetFilters() {
        $("#diFilterCustomerName").val("");
        $("#diFilterStatus").val("");
        $("#diFilterRecentBuy").val("");
        $("#diFilterUnpurchasable").prop("checked", false);
        loadInvestments(0);
    }
    MARIA.tableSort.bind(".di-table", function (key, dir) {
        sortSpec = { key: key, dir: dir };
        loadInvestments(0);
    });
    $("#diFilterReset").on("click", resetFilters);
    $("#diListBody").on("click", ".di-empty-reset", resetFilters);
    $("#diListBody").on("click", ".di-retry", function () {
        loadSummary();
        loadInvestments(parseInt($(this).attr("data-page"), 10) || 0);
    });
    $("#diFilterCustomerName").on("keydown", function (event) {
        if (event.key === "Enter") {
            loadInvestments(0);
        }
    });

    $("#diPageSize").on("click", "button", function () {
        var size = parseInt($(this).attr("data-size"), 10);
        if (!size || size === PAGE_SIZE) {
            return;
        }
        PAGE_SIZE = size;
        $("#diPageSize button").removeClass("is-on");
        $(this).addClass("is-on");
        loadInvestments(0);
    });

    $("#diRecentDays").on("change", function () {
        recentDays = Number($(this).val());
        loadSummary();
        if ($("#diFilterRecentBuy").val()) {
            loadInvestments(0);
        }
    });

    // ---------- 점검 목록 패널 (거래제한·정지 / 매수불가 / 최근 매수 / 예탁금 비중) ----------
    var checkTab = "restricted";
    var checkCashPage = 0;
    var CHECK_API = "/api/admin/domestic-investments/";

    function accountNoText(no) {
        return no ? MARIA.fmt.hyphenateAccountNo(no) : "-";
    }
    function checkNo(i) {
        return '<td class="adm-col-no">' + (i + 1) + "</td>";
    }
    function checkState(selector, cols, text) {
        $(selector).html('<tr class="is-state"><td colspan="' + cols + '"><div class="di-panel-empty">' + escapeHtml(text) + "</div></td></tr>");
    }
    function checkList(selector, cols, rows, emptyText, rowHtml) {
        if (!rows || !rows.length) {
            checkState(selector, cols, emptyText);
            return;
        }
        $(selector).html(rows.map(rowHtml).join(""));
    }
    function productCell(r) {
        return "<td>" + escapeHtml(r.productName || "-") + ' <span class="di-check-sub">' + escapeHtml(r.ticker || "") + "</span></td>";
    }
    function loadCheck(tab) {
        checkTab = tab;
        if (tab === "restricted") {
            checkState("#diCheckRestricted", 5, "불러오는 중...");
            MARIA.auth.ajax({ url: CHECK_API + "restricted-holdings", method: "GET" }).done(function (res) {
                checkList("#diCheckRestricted", 5, res.data, "거래제한·정지 종목이 없습니다.", function (r, i) {
                    return "<tr>" + checkNo(i) + "<td>" + escapeHtml(MARIA.fmt.maskName(r.customerName) || "-") + "</td><td>" + escapeHtml(accountNoText(r.accountNo)) + "</td>" +
                        productCell(r) + '<td class="is-c">' + statusChip(r.status) + "</td></tr>";
                });
            }).fail(function () { checkState("#diCheckRestricted", 5, "목록을 불러오지 못했습니다."); });
        } else if (tab === "unpurchasable") {
            checkState("#diCheckUnpurchasable", 6, "불러오는 중...");
            MARIA.auth.ajax({ url: CHECK_API + "unpurchasable-holdings", method: "GET" }).done(function (res) {
                checkList("#diCheckUnpurchasable", 6, res.data, "매수불가 종목이 없습니다.", function (r, i) {
                    return "<tr>" + checkNo(i) + "<td>" + escapeHtml(MARIA.fmt.maskName(r.customerName) || "-") + "</td><td>" + escapeHtml(accountNoText(r.accountNo)) + "</td>" +
                        productCell(r) +
                        '<td class="is-r">' + (r.domesticStockRatio == null ? "-" : escapeHtml(Number(r.domesticStockRatio).toFixed(1)) + "%") + "</td>" +
                        '<td class="is-c">' + escapeHtml(r.inceptionDate || "-") + "</td></tr>";
                });
            }).fail(function () { checkState("#diCheckUnpurchasable", 6, "목록을 불러오지 못했습니다."); });
        } else if (tab === "recent") {
            var flag = $("#diRecentFlag .is-on").attr("data-flag") === "true";
            checkState("#diCheckRecent", 3, "불러오는 중...");
            MARIA.auth.ajax({ url: CHECK_API + "recent-buy-accounts", method: "GET", data: { days: recentDays, hasRecentBuy: flag } }).done(function (res) {
                checkList("#diCheckRecent", 3, res.data, "해당하는 계좌가 없습니다.", function (r, i) {
                    return "<tr>" + checkNo(i) + "<td>" + escapeHtml(MARIA.fmt.maskName(r.customerName) || "-") + "</td><td>" + escapeHtml(accountNoText(r.accountNo)) + "</td></tr>";
                });
            }).fail(function () { checkState("#diCheckRecent", 3, "목록을 불러오지 못했습니다."); });
        } else {
            loadCheckCash(checkCashPage);
        }
    }
    function ratioText(v) {
        if (v == null) {
            return "-";
        }
        var n = Number(v);
        return (n <= 1 ? n * 100 : n).toFixed(1) + "%";
    }
    var cashSort = null; // 머리글로 고른 정렬(서버가 전체 데이터에서 정렬)
    function loadCheckCash(page) {
        checkCashPage = page;
        checkState("#diCheckCash", 6, "불러오는 중...");
        MARIA.auth.ajax({ url: CHECK_API + "cash-heavy-accounts", method: "GET", data: $.extend({ page: page, size: 20 }, cashSort ? { sort: cashSort.key, direction: cashSort.dir } : {}) }).done(function (res) {
            var d = res.data || {};
            checkList("#diCheckCash", 6, d.content, "예탁금 비중이 높은 계좌가 없습니다.", function (r, i) {
                return "<tr>" + checkNo(page * 20 + i) + "<td>" + escapeHtml(MARIA.fmt.maskName(r.customerName) || "-") + "</td><td>" + escapeHtml(accountNoText(r.accountNo)) + "</td>" +
                    '<td class="is-r">' + escapeHtml(formatAmount(r.cashAmount)) + '</td><td class="is-r">' + escapeHtml(formatAmount(r.investedAmount)) +
                    '</td><td class="is-r">' + escapeHtml(ratioText(r.cashRatio)) + "</td></tr>";
            });
            var pages = d.totalPages || 0;
            $("#diCheckCashPager").html(pages > 1
                ? '<button type="button" class="adm-btn" data-cash-page="' + (page - 1) + '"' + (page <= 0 ? " disabled" : "") + ">이전</button>" +
                  "<span>" + (page + 1) + " / " + pages + " (총 " + (d.totalElements || 0) + "건)</span>" +
                  '<button type="button" class="adm-btn" data-cash-page="' + (page + 1) + '"' + (page >= pages - 1 ? " disabled" : "") + ">다음</button>"
                : "");
        }).fail(function () { checkState("#diCheckCash", 6, "목록을 불러오지 못했습니다."); });
    }
    function setCheckTab(name) {
        $("#diCheckTabs .adm-tab").each(function () {
            var on = $(this).attr("data-check") === name;
            $(this).toggleClass("is-on", on).attr("aria-selected", on ? "true" : "false");
        });
        $("[data-check-pane]").each(function () {
            $(this).prop("hidden", $(this).attr("data-check-pane") !== name);
        });
        $("#diCheckTools").prop("hidden", name !== "recent");
        loadCheck(name);
    }
    MARIA.tableSort.bind(".di-check-cash", function (key, dir) {
        cashSort = { key: key, dir: dir };
        loadCheckCash(0);
    });

    function openCheck() {
        $("#diCheckScrim, #diCheckPanel").addClass("is-open");
        $("#diCheckPanel").attr("aria-hidden", "false");
        setCheckTab(checkTab);
        $("#diCheckClose").trigger("focus");
    }
    function closeCheck() {
        $("#diCheckScrim, #diCheckPanel").removeClass("is-open");
        $("#diCheckPanel").attr("aria-hidden", "true");
        $("#diCheckOpen").trigger("focus");
    }
    $("#diCheckOpen").on("click", openCheck);
    $("#diCheckClose, #diCheckScrim").on("click", closeCheck);
    $("#diCheckTabs").on("click", ".adm-tab", function () { setCheckTab($(this).attr("data-check")); });
    $("#diRecentFlag").on("click", "button", function () {
        $("#diRecentFlag button").removeClass("is-on");
        $(this).addClass("is-on");
        loadCheck("recent");
    });
    $("#diCheckCashPager").on("click", "[data-cash-page]", function () {
        loadCheckCash(parseInt($(this).attr("data-cash-page"), 10) || 0);
    });
    $("#diRecentDays").on("change", function () {
        if ($("#diCheckPanel").hasClass("is-open") && checkTab === "recent") {
            loadCheck("recent");
        }
    });

    MARIA.auth.ajax({ url: "/api/admin/system-clock", method: "GET" })
        .done(function (res) {
            businessToday = new Date(res.data);
        });
    loadSummary();
    loadInvestments(0);

    // 국내 시세·거래가 계속 바뀌므로 10초마다 요약과 지금 보는 페이지를 조용히 다시 받는다(탭이 안 보이면 건너뜀)
    setInterval(function () {
        if (document.hidden) {
            return;
        }
        loadSummary();
        loadInvestments(currentPage, true);
    }, REFRESH_MS);
});
