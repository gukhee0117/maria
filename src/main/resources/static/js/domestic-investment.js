$(function () {
    var QTY_FORMATTER = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 4 });
    var KRW_FORMATTER = new Intl.NumberFormat("ko-KR");
    var DATETIME_FORMATTER = new Intl.DateTimeFormat("ko-KR", {
        year: "numeric", month: "2-digit", day: "2-digit",
        hour: "2-digit", minute: "2-digit"
    });

    var investments = [];
    var selectedAccountId = null;
    var PAGE_SIZE = 10;
    var businessToday = null;
    var recentDays = 7;
    var currentDetailType = null;
    var accountListFilter = null;
    var LIST_FILTER_TITLES = {
        restricted: "거래제한·정지 계좌 목록",
        unpurchasable: "매수불가 종목 보유 계좌 목록",
        recentBuy: "최근 매수 있는 계좌 목록",
        noRecentBuy: "최근 매수 없는 계좌 목록"
    };
    var LIST_FILTER_TYPES = ["restricted", "unpurchasable", "recentBuy", "noRecentBuy", "none"];

    function setAccountListFilter(type) {
        accountListFilter = type;
        $("#diListTitle").text(LIST_FILTER_TITLES[type] || "계좌 목록");
        $("#diFilterCustomerName").val("");
        $("#diFilterStatus").val("");
        loadInvestments(0);
    }

    var STATUS_LABEL = {
        HOLDING: "보유중",
        PARTIALLY_SOLD: "일부매도",
        SOLD_OUT: "전량매도",
        TRADE_RESTRICTED: "거래제한",
        TRADE_SUSPENDED: "거래정지",
        TERMINATED: "보유종료"
    };
    var DANGER_STATUSES = ["TRADE_RESTRICTED", "TRADE_SUSPENDED"];
    var TYPE_LABEL = { STOCK: "국내주식", FUND: "펀드" };
    var TRADE_TYPE_LABEL = { BUY: "매수", SELL: "매도" };

    function formatDateTime(isoString) {
        if (!isoString) {
            return "-";
        }
        return DATETIME_FORMATTER.format(new Date(isoString)).replace(/\. /g, "-").replace(".", "");
    }
    function formatQty(qty) {
        return QTY_FORMATTER.format(qty || 0) + "주";
    }
    function formatAmount(amount) {
        return "₩" + KRW_FORMATTER.format(amount || 0);
    }
    function escapeHtml(value) {
        return $("<div>").text(value).html();
    }
    function statusBadge(status) {
        var label = STATUS_LABEL[status] || status;
        var cls = DANGER_STATUSES.indexOf(status) >= 0 ? "failed" : "completed";
        return '<span class="status-badge ' + cls + '">' + label + '</span>';
    }
    function formatPercent(ratio) {
        return (Number(ratio) * 100).toFixed(1) + "%";
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

    function renderList() {
        var $items = $("#diListItems").empty();
        if (investments.length === 0) {
            $items.append('<div class="dash-empty">국내투자 계좌가 없습니다.</div>');
            return;
        }
        investments.forEach(function (item) {
            var isSelected = item.accountId === selectedAccountId;
            var $row = $(
                '<div class="di-list-item' + (isSelected ? " selected" : "") + '">' +
                '<div class="di-list-item-top">' +
                '<span class="di-list-item-name">' + escapeHtml(item.customerName) + '</span>' +
                (item.hasRestrictedHolding
                    ? '<span class="status-badge failed">거래제한/정지</span>'
                    : "") +
                '</div>' +
                '<div class="di-list-item-bottom">' +
                '<span>계좌 ' + MARIA.fmt.accountNoHtml(item.accountNo) + '</span>' +
                '<span>예탁금 ' + formatAmount(item.cashAmount) + '</span>' +
                '<span>보유종목 ' + item.holdingCount + '건</span>' +
                '</div>' +
                '</div>'
            );
            $row.on("click", function () {
                selectedAccountId = item.accountId;
                renderList();
                loadDetail(item.accountId);
            });
            $items.append($row);
        });
    }

    function renderPagination(page) {
        var $pagination = $("#diPagination").empty();
        if (!page || page.totalPages <= 1) {
            return;
        }

        var current = page.page;
        var totalPages = page.totalPages;
        var BLOCK_SIZE = 10;
        var blockStart = Math.floor(current / BLOCK_SIZE) * BLOCK_SIZE;
        var blockEnd = Math.min(totalPages - 1, blockStart + BLOCK_SIZE - 1);

        function addButton(label, targetPage, isDisabled, isActive) {
            var classes = "page-btn" + (isActive ? " active" : "");
            var $btn = $('<button type="button" class="' + classes + '">' + label + "</button>");
            $btn.prop("disabled", isDisabled || isActive);
            if (!isDisabled && !isActive) {
                $btn.on("click", function () {
                    loadInvestments(targetPage);
                });
            }
            $pagination.append($btn);
        }

        addButton("이전", blockStart - 1, blockStart === 0, false);
        for (var i = blockStart; i <= blockEnd; i++) {
            addButton(String(i + 1), i, false, i === current);
        }
        addButton("다음", blockEnd + 1, blockEnd === totalPages - 1, false);
    }

    function renderDetail(data) {
        var $detail = $("#diDetail").empty();

        $detail.append(
            '<div class="section-header">' +
            '<span>' + MARIA.fmt.accountNoHtml(data.accountNo) + ' · ' + escapeHtml(data.customerName) + '</span>' +
            '<span class="section-sub">예탁금 ' + formatAmount(data.cashAmount) + '</span>' +
            '</div>'
        );

        var holdings = data.holdings || [];
        var holdingRows = holdings.map(function (h) {
            return (
                '<tr>' +
                '<td>' + escapeHtml(h.name) + ' (' + escapeHtml(h.ticker) + ')</td>' +
                '<td>' + (TYPE_LABEL[h.type] || escapeHtml(h.type)) + '</td>' +
                '<td>' + formatQty(h.qty) + '</td>' +
                '<td>' + formatAmount(h.avgPurchasePrice) + '</td>' +
                '<td>' + formatDateTime(h.lastPurchaseDate) + '</td>' +
                '<td>' + statusBadge(h.status) + '</td>' +
                '<td>' + (h.currentlyPurchasable
                    ? '<span class="status-badge completed" title="' + escapeHtml(purchasabilityReason(h)) + '">매수가능</span>'
                    : '<span class="status-badge failed" title="' + escapeHtml(purchasabilityReason(h)) + '">매수불가</span>') + '</td>' +
                '</tr>'
            );
        }).join("");

        $detail.append(
            '<div class="section-header">' +
            '<span>보유종목</span>' +
            '<span class="section-sub">' + holdings.length + '건</span>' +
            '</div>' +
            '<table class="dash-table di-holdings-table">' +
            '<thead><tr><th>종목</th><th>구분</th><th>수량</th><th>평균매수단가</th><th>최근매수일</th><th>상태</th><th>매수가능여부</th></tr></thead>' +
            '<tbody>' + (holdingRows || '<tr><td colspan="7" class="dash-empty">보유종목이 없습니다.</td></tr>') + '</tbody>' +
            '</table>'
        );

        var tradeHistory = data.tradeHistory || [];
        var tradeItems = tradeHistory.map(function (t) {
            return (
                '<li>' + (TRADE_TYPE_LABEL[t.tradeType] || escapeHtml(t.tradeType)) + ' ' +
                escapeHtml(t.stockCode) + ' ' + formatQty(t.qty) +
                ' @ ' + formatAmount(t.price) + ' · ' + formatDateTime(t.executedAt) +
                '</li>'
            );
        }).join("");

        $detail.append(
            '<div class="section-header">' +
            '<span>개별 매매내역</span>' +
            '<span class="section-sub">' + tradeHistory.length + '건</span>' +
            '</div>' +
            (tradeItems
                ? '<ul class="di-trade-history">' + tradeItems + '</ul>'
                : '<div class="dash-empty">매매내역이 없습니다.</div>')
        );
    }

    function loadDetail(accountId) {
        $("#diDetail").empty().append('<div class="dash-loading">불러오는 중...</div>');
        MARIA.auth.ajax({
            url: "/api/admin/domestic-investments/" + accountId,
            method: "GET"
        })
            .done(function (res) {
                renderDetail(res.data);
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                var message = "상세 정보를 불러오지 못했습니다.";
                if (xhr.responseJSON && xhr.responseJSON.message) {
                    message = xhr.responseJSON.message;
                }
                $("#diDetail").empty().append('<div class="dash-error">' + escapeHtml(message) + '</div>');
            });
    }

    function getFilterParams() {
        var params = {};
        var keyword = $("#diFilterCustomerName").val();
        if (keyword) {
            params.keyword = keyword;
        }
        var status = $("#diFilterStatus").val();
        if (status) {
            params.status = status;
        }
        if (accountListFilter === "restricted") {
            params.hasRestrictedHolding = true;
        } else if (accountListFilter === "unpurchasable") {
            params.hasUnpurchasableHolding = true;
        } else if (accountListFilter === "recentBuy") {
            params.hasRecentBuy = true;
            params.recentBuyDays = recentDays;
        } else if (accountListFilter === "noRecentBuy") {
            params.hasRecentBuy = false;
            params.recentBuyDays = recentDays;
        }
        return params;
    }

    function loadInvestments(page) {
        var targetPage = page || 0;
        var requestData = $.extend({ page: targetPage, size: PAGE_SIZE }, getFilterParams());
        MARIA.auth.ajax({
            url: "/api/admin/domestic-investments",
            method: "GET",
            data: requestData
        })
            .done(function (res) {
                investments = res.data.content || [];
                selectedAccountId = null;
                renderList();
                renderPagination(res.data);
                if (investments.length > 0) {
                    selectedAccountId = investments[0].accountId;
                    renderList();
                    loadDetail(investments[0].accountId);
                } else {
                    $("#diDetail").empty().append('<div class="dash-empty">왼쪽에서 계좌를 선택하세요.</div>');
                }
                $("#diLoading").hide();
                $("#diBody").css("display", "flex");
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                $("#diLoading").hide();
                var message = "목록을 불러오지 못했습니다.";
                if (xhr.responseJSON && xhr.responseJSON.message) {
                    message = xhr.responseJSON.message;
                }
                $("#diError").text(message).show();
            });
    }

    $("#diFilterSubmit").on("click", function () {
        loadInvestments(0);
    });
    $("#diFilterReset").on("click", function () {
        $(".kpi-clickable").removeClass("active");
        setAccountListFilter(null);
    });
    $("#diFilterCustomerName").on("keypress", function (e) {
        if (e.which === 13) {
            loadInvestments(0);
        }
    });

    function renderSummary(summary) {
        $("#kpiTotalAccount").text(summary.totalAccountCount + " 개");
        $("#kpiRestrictedAccount").text(summary.restrictedAccountCount + " 개");
        $("#kpiUnpurchasable").text(summary.unpurchasableHoldingCount + " 건");
        $("#kpiTotalCash").text(formatAmount(summary.totalCashAmount));
        $("#kpiRecentBuy").text(summary.recentBuyAccountCount);
        $("#kpiNoRecentBuy").text(summary.noRecentBuyAccountCount);
    }

    function loadSummary() {
        MARIA.auth.ajax({
            url: "/api/admin/domestic-investments/summary",
            method: "GET",
            data: { days: recentDays }
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

    var DETAIL_CONFIG = {
        cashHeavy: {
            title: "예탁금 비중 높은 순",
            columns: ["고객명", "계좌번호", "예탁금", "투자금액", "예탁금 비중"],
            load: function (page) {
                return MARIA.auth.ajax({
                    url: "/api/admin/domestic-investments/cash-heavy-accounts",
                    method: "GET",
                    data: { page: page, size: 20 }
                });
            },
            row: function (r) {
                return [
                    escapeHtml(r.customerName),
                    escapeHtml(r.accountNo || "-"),
                    formatAmount(r.cashAmount),
                    formatAmount(r.investedAmount),
                    formatPercent(r.cashRatio)
                ];
            }
        }
    };

    function openDetailPanel(type, page) {
        currentDetailType = type;
        $(".kpi-clickable").removeClass("active");
        $('[data-detail="' + type + '"]').addClass("active");

        var config = DETAIL_CONFIG[type];
        var targetPage = page || 0;
        $("#diDetailTitle").text(config.title);
        $("#diDetailTableHead").html(
            "<tr>" + config.columns.map(function (c) { return "<th>" + c + "</th>"; }).join("") + "</tr>"
        );
        $("#diDetailTableBody").html(
            '<tr><td colspan="' + config.columns.length + '" class="dash-empty">불러오는 중...</td></tr>'
        );
        $("#diDetailPagination").empty();
        $("#diDetailPanel").show();

        config.load(targetPage)
            .done(function (res) {
                if (currentDetailType !== type) {
                    return;
                }
                var pageData = res.data;
                var rows = pageData.content || [];
                $("#diDetailCount").text(pageData.totalElements + "건");
                var $body = $("#diDetailTableBody").empty();
                if (rows.length === 0) {
                    $body.append(
                        '<tr><td colspan="' + config.columns.length + '" class="dash-empty">해당하는 항목이 없습니다.</td></tr>'
                    );
                } else {
                    rows.forEach(function (r) {
                        var cells = config.row(r);
                        $body.append("<tr><td>" + cells.join("</td><td>") + "</td></tr>");
                    });
                }
                renderDetailPagination(pageData, type);
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                $("#diDetailTableBody").empty().append(
                    '<tr><td colspan="' + config.columns.length + '" class="dash-empty">불러오지 못했습니다.</td></tr>'
                );
            });
    }

    function renderDetailPagination(page, type) {
        var $pagination = $("#diDetailPagination").empty();
        if (!page || page.totalPages <= 1) {
            return;
        }

        var current = page.page;
        var totalPages = page.totalPages;
        var BLOCK_SIZE = 10;
        var blockStart = Math.floor(current / BLOCK_SIZE) * BLOCK_SIZE;
        var blockEnd = Math.min(totalPages - 1, blockStart + BLOCK_SIZE - 1);

        function addButton(label, targetPage, isDisabled, isActive) {
            var classes = "page-btn" + (isActive ? " active" : "");
            var $btn = $('<button type="button" class="' + classes + '">' + label + "</button>");
            $btn.prop("disabled", isDisabled || isActive);
            if (!isDisabled && !isActive) {
                $btn.on("click", function () {
                    openDetailPanel(type, targetPage);
                });
            }
            $pagination.append($btn);
        }

        addButton("이전", blockStart - 1, blockStart === 0, false);
        for (var i = blockStart; i <= blockEnd; i++) {
            addButton(String(i + 1), i, false, i === current);
        }
        addButton("다음", blockEnd + 1, blockEnd === totalPages - 1, false);
    }

    function closeDetailPanel() {
        currentDetailType = null;
        $(".kpi-clickable").removeClass("active");
        $("#diDetailPanel").hide();
    }

    $(".kpi-clickable").on("click", function () {
        var type = $(this).data("detail");

        if (LIST_FILTER_TYPES.indexOf(type) >= 0) {
            closeDetailPanel();
            $(".kpi-clickable").removeClass("active");
            if (accountListFilter === type || type === "none") {
                setAccountListFilter(null);
            } else {
                $(this).addClass("active");
                setAccountListFilter(type);
            }
            return;
        }

        if (accountListFilter) {
            setAccountListFilter(null);
        }
        if (currentDetailType === type) {
            closeDetailPanel();
        } else {
            openDetailPanel(type, 0);
        }
    });

    $("#diDetailClose").on("click", closeDetailPanel);

    $("#diRecentDays").on("change", function () {
        recentDays = Number($(this).val());
        loadSummary();
        if (accountListFilter === "recentBuy" || accountListFilter === "noRecentBuy") {
            loadInvestments(0);
        }
    });

    MARIA.auth.ajax({ url: "/api/admin/system-clock", method: "GET" })
        .done(function (res) {
            businessToday = new Date(res.data);
        });
    loadSummary();
    loadInvestments(0);
});
