$(function () {
    var STOCK_TYPE_LABEL = {
        FOREIGN_STOCK: "해외주식",
        ETF: "ETF",
        ETN: "ETN",
        FUND: "펀드"
        };
    var TRADE_TYPE_LABEL = {
        BUY: "매수",
        SELL: "매도",
        INHERITANCE: "상속",
        GIFT: "증여"
    };
    var PAGE_SIZE = 20;
    var activeKpiFilter = null;
    var newJudgementIds = [];

    var KRW_FORMATTER = new Intl.NumberFormat("ko-KR");
    var DATETIME_FORMATTER = new Intl.DateTimeFormat("ko-KR", {
        year: "numeric", month: "2-digit", day: "2-digit",
        hour: "2-digit", minute: "2-digit"
    });

    function formatDateTime(isoString) {
        if (!isoString) {
            return "-";
        }
        return DATETIME_FORMATTER.format(new Date(isoString)).replace(/\. /g, "-").replace(".", "");
    }
    function formatAmount(amount) {
        var value = amount || 0;
        var sign = value < 0 ? "-" : "";
        return sign + "₩" + KRW_FORMATTER.format(Math.abs(value));
    }
    function escapeHtml(value) {
        return $("<div>").text(value).html();
    }
    function canTriggerBatch() {
        var admin = MARIA.auth.currentAdmin();
        return !!admin && (admin.role === "ADMIN" || admin.role === "SETTLEMENT");
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

    function renderTable(items) {
        var $body = $("#tpTableBody").empty();
        if (!items || items.length === 0) {
            $body.append('<tr><td colspan="8" class="dash-empty">탐지된 이벤트가 없습니다.</td></tr>');
            return;
        }
        items.forEach(function (item) {
            var netBuyClass = Number(item.netBuyAmount) < 0 ? ' style="color:var(--danger)"' : "";
            var targetBadge = item.isTarget
                ? '<span class="status-badge completed" title="' + escapeHtml(judgementReason(item)) + '">대상</span>'
                : '<span class="status-badge failed" title="' + escapeHtml(judgementReason(item)) + '">비대상</span>';
            var isNew = newJudgementIds.indexOf(item.judgementId) !== -1;
            var newBadge = isNew ? '<span class="tp-new-badge">NEW</span> ' : "";
            var row =
                "<tr" + (isNew ? ' class="tp-new-row"' : "") + ">" +
                "<td>" + newBadge + formatDateTime(item.judgedAt) + "</td>" +
                "<td>" + escapeHtml(item.customerName || "-") + "</td>" +
                "<td>" + (STOCK_TYPE_LABEL[item.stockType] || item.stockType) + "</td>" +
                "<td>" + assetLabel(item) + "</td>" +
                "<td>" + (TRADE_TYPE_LABEL[item.tradeType] || item.tradeType) + "</td>" +
                "<td>" + formatAmount(item.amount) + "</td>" +
                "<td" + netBuyClass + ">" + formatAmount(item.netBuyAmount) + "</td>" +
                "<td>" + targetBadge + "</td>" +
                "</tr>";
            $body.append(row);
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
            var classes = "page-btn" + (isActive ? " active" : "");
            var $btn = $('<button type="button" class="' + classes + '">' + label + "</button>");
            $btn.prop("disabled", isDisabled || isActive);
            if (!isDisabled && !isActive) {
                $btn.on("click", function () {
                    loadTargetProducts(targetPage);
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

    function renderSummary(summary) {
        $("#kpiTodayJudgement").text(summary.todayJudgementCount + " 건");
        $("#kpiTodayTarget").text(summary.todayTargetCount + " 건");
        $("#kpiTodayTargetAmount").text(formatAmount(summary.todayTargetNetBuyAmount));
        $("#kpiTotalJudgement").text(summary.totalJudgementCount + " 건");
        $("#kpiTodayInheritanceGift").text(summary.todayInheritanceGiftCount + " 건");
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

    function getCardFilterParams() {
        if (activeKpiFilter === "today") {
            return { todayOnly: true };
        }
        if (activeKpiFilter === "todayTarget") {
            return { todayOnly: true, isTarget: true };
        }
        if (activeKpiFilter === "todayInheritanceGift") {
            return { todayOnly: true, inheritanceGiftOnly: true };
        }
        return {};
    }

    function clearKpiFilter() {
        activeKpiFilter = null;
        $(".kpi-filter-card").removeClass("active");
    }

    function loadTargetProducts(page) {
        var targetPage = page || 0;
        var requestData = $.extend({ page: targetPage, size: PAGE_SIZE }, getFilterParams(), getCardFilterParams());
        MARIA.auth.ajax({
            url: "/api/admin/target-products",
            method: "GET",
            data: requestData
        })
            .done(function (res) {
                renderTable(res.data.content);
                renderPagination(res.data);
                $("#tpLoading").hide();
                $("#tpBody").css("display", "flex");
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                $("#tpLoading").hide();
                var message = "목록을 불러오지 못했습니다.";
                if (xhr.responseJSON && xhr.responseJSON.message) {
                    message = xhr.responseJSON.message;
                }
                $("#tpError").text(message).show();
            });
    }

    $("#tpFilterSubmit").on("click", function () {
        clearKpiFilter();
        loadTargetProducts(0);
    });
    $("#tpFilterReset").on("click", function () {
        clearKpiFilter();
        $("#tpFilterCustomerName").val("");
        $("#tpFilterStockType").val("");
        $("#tpFilterIsTarget").val("");
        $("#tpFilterTradeType").val("");
        loadTargetProducts(0);
    });
    $("#tpFilterCustomerName").on("keypress", function (e) {
        if (e.which === 13) {
            clearKpiFilter();
            loadTargetProducts(0);
        }
    });
    $(".kpi-filter-card").on("click", function () {
        var filter = $(this).data("filter");
        var resolvedFilter = filter === "all" ? null : filter;
        if (activeKpiFilter === resolvedFilter) {
            resolvedFilter = null;
        }
        activeKpiFilter = resolvedFilter;
        $("#tpFilterCustomerName").val("");
        $("#tpFilterStockType").val("");
        $("#tpFilterIsTarget").val("");
        $("#tpFilterTradeType").val("");
        $(".kpi-filter-card").removeClass("active");
        if (activeKpiFilter === null) {
            $('.kpi-filter-card[data-filter="all"]').addClass("active");
        } else {
            $(this).addClass("active");
        }
        loadTargetProducts(0);
    });

    $("#tpBatchTriggerGroup").toggle(canTriggerBatch());
    $("#tpTriggerBatch").on("click", function () {
        if (!canTriggerBatch()) return;
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
                loadSummary();
                loadTargetProducts(0);
            })
            .fail(function (xhr) {
                if (xhr.status === 401) return;
                $("#tpBatchTriggerResult").text("실패");
                MARIA.ui.showError((xhr.responseJSON && xhr.responseJSON.message) || "배치 실행에 실패했습니다.");
            });
    });

    loadSummary();
    loadTargetProducts(0);
});
