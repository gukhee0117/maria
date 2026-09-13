$(function () {
    var SELL_ORDER_STATUS_LABEL = {
        RECEIVED: "접수",
        EXECUTED: "체결",
        REJECTED: "거부"
    };

    var KRW_FORMATTER = new Intl.NumberFormat("ko-KR");
    var QTY_FORMATTER = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 4 });
    var DATETIME_FORMATTER = new Intl.DateTimeFormat("ko-KR", {
        year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"
    });

    var PAGE_SIZE = 10;
    var currentPage = 0;
    var totalPages = 1;
    var selectedOrderId = null;

    function escapeHtml(value) {
        return $("<div>").text(value == null ? "" : value).html();
    }

    function formatAmount(amount) {
        return amount == null ? "-" : "₩" + KRW_FORMATTER.format(amount);
    }

    function truncateAmount(amount) {
        return amount == null ? amount : Math.trunc(amount);
    }

    function formatQty(qty) {
        return qty == null ? "-" : QTY_FORMATTER.format(qty) + "주";
    }

    function formatDateTime(value) {
        return value ? DATETIME_FORMATTER.format(new Date(value)) : "-";
    }

    function sellOrderStatusLabel(status) {
        return SELL_ORDER_STATUS_LABEL[status] || status || "-";
    }

    function statusClassOf(status) {
        return (status || "").toLowerCase();
    }

    function showError(message) {
        $("#sellOrderError").text(message).show();
    }

    function hideError() {
        $("#sellOrderError").hide();
    }

    function buildFilterParams() {
        var params = { page: currentPage, size: PAGE_SIZE };
        var keyword = $("#sellOrderFilterKeyword").val().trim();
        var status = $("#sellOrderFilterStatus").val();
        var startDate = $("#sellOrderFilterStartDate").val();
        var endDate = $("#sellOrderFilterEndDate").val();
        if (keyword) {
            params.keyword = keyword;
        }
        if (status) {
            params.status = status;
        }
        if (startDate) {
            params.startDate = startDate;
        }
        if (endDate) {
            params.endDate = endDate;
        }
        return params;
    }

    function formatChangeRate(rate) {
        if (rate === null || rate === undefined) {
            return { text: "—", cls: "" };
        }
        var num = Number(rate);
        var arrow = num > 0 ? "▲" : num < 0 ? "▼" : "-";
        var cls = num > 0 ? "up" : num < 0 ? "down" : "";
        return { text: arrow + Math.abs(num).toFixed(1) + "%", cls: cls };
    }

    function renderSummary(summary) {
        $("#kpiTodaySellAmount").text(formatAmount(truncateAmount(summary.todaySellAmount)));
        $("#kpiTodayExecutedCount").text((summary.todayExecutedCount || 0) + "건");
        $("#kpiPendingProvisionalAmount").text(formatAmount(truncateAmount(summary.pendingProvisionalAmount)));
        $("#kpiTodayFinalizedAmount").text(formatAmount(truncateAmount(summary.todayFinalizedAmount)));

        var sellChange = formatChangeRate(summary.todaySellAmountChangeRate);
        $("#kpiTodaySellAmountChange").text(sellChange.text).attr("class", "kpi-change " + sellChange.cls);

        var countChange = formatChangeRate(summary.todayExecutedCountChangeRate);
        $("#kpiTodayExecutedCountChange").text(countChange.text).attr("class", "kpi-change " + countChange.cls);
    }

    function loadSellOrderSummary() {
        MARIA.auth.ajax({
            url: "/api/admin/sell-orders/summary",
            method: "GET"
        })
            .done(function (res) {
                renderSummary(res.data || {});
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
            });
    }

    function renderRows(orders) {
        var $body = $("#sellOrderListBody").empty();

        if (!orders.length) {
            $body.append('<tr><td colspan="8" class="sellorder-empty">매도 · 환전 내역이 없습니다.</td></tr>');
            return;
        }

        orders.forEach(function (order) {
            var productLabel = order.ticker
                ? escapeHtml(order.ticker) + (order.name ? " · " + escapeHtml(order.name) : "")
                : "-";
            var selectedClass = Number(order.orderId) === Number(selectedOrderId) ? " is-selected" : "";
            var row =
                '<tr class="sellorder-row' + selectedClass + '" data-order-id="' + order.orderId + '">' +
                '<td class="sellorder-ellipsis"><div class="sellorder-account-no">' + MARIA.fmt.hyphenateAccountNo(order.accountNo) + "</div>" +
                '<div class="sellorder-account-name">' + escapeHtml(order.customerName || "") + "</div></td>" +
                '<td class="sellorder-ellipsis">' + productLabel + "</td>" +
                '<td class="sellorder-amount">' + formatQty(order.sellQty) + "</td>" +
                '<td class="sellorder-amount">' + formatAmount(truncateAmount(order.basePrice)) + "</td>" +
                "<td>" + formatDateTime(order.processedAt) + "</td>" +
                '<td class="sellorder-amount">' + formatAmount(truncateAmount(order.provisionalAmount)) + "</td>" +
                '<td class="sellorder-amount">' + formatAmount(truncateAmount(order.finalAmount)) + "</td>" +
                "<td><span class=\"sellorder-status-badge " + statusClassOf(order.status) + '">' +
                escapeHtml(sellOrderStatusLabel(order.status)) + "</span></td>" +
                "</tr>";
            $body.append(row);
        });
    }

    function renderPagination() {
        var $pagination = $("#sellOrderPagination").empty();
        if (totalPages <= 1) {
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
                    loadSellOrders();
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

    var SETTLEMENT_STATUS_LABEL = {
        PROVISIONAL: "가환전",
        FINALIZED: "확정산"
    };

    function formatRate(rate) {
        return rate == null ? "-" : "₩" + Number(rate).toFixed(2);
    }

    function formatPrice(price, currency) {
        if (price == null) {
            return "-";
        }
        return Number(price).toLocaleString("ko-KR", { maximumFractionDigits: 4 }) + " " + (currency || "");
    }

    function formatVariance(orderAmount, finalAmount) {
        if (orderAmount == null || finalAmount == null) {
            return "-";
        }
        var diff = Math.trunc(finalAmount - orderAmount);
        var sign = diff > 0 ? "+" : "";
        return sign + KRW_FORMATTER.format(diff) + "원";
    }

    function closeDrawer() {
        $("#sellorder-detail-drawer").removeClass("is-open").attr("aria-hidden", "true");
        $("#sellorder-drawer-backdrop").prop("hidden", true);
    }

    function renderDrawer(detail) {
        var orderAmount = detail.sellQty != null && detail.basePrice != null
            ? detail.sellQty * detail.basePrice
            : null;

        $("#sellorder-detail-title").text((detail.customerName || "-") + " 고객 매도");
        $("#sellorder-detail-status")
            .attr("class", "sellorder-status-badge " + statusClassOf(detail.status))
            .text(sellOrderStatusLabel(detail.status));

        $("#sellorder-detail-account-no").text(detail.accountNo || "-");
        $("#sellorder-detail-customer-name").text(detail.customerName || "-");
        $("#sellorder-detail-product").text(
            detail.ticker ? detail.ticker + (detail.name ? " · " + detail.name : "") : "-"
        );
        $("#sellorder-detail-qty").text(formatQty(detail.sellQty));
        $("#sellorder-detail-processed-at").text(formatDateTime(detail.processedAt));

        $("#sellorder-detail-base-price").text(formatAmount(truncateAmount(detail.basePrice)));
        $("#sellorder-detail-fx-rate").text(
            detail.settlementFxRate == null ? "-" : "정산환율 " + formatRate(detail.settlementFxRate)
        );

        $("#sellorder-detail-provisional-amount").text(formatAmount(truncateAmount(detail.provisionalAmount)));
        $("#sellorder-detail-provisional-amount-detail").text(formatAmount(truncateAmount(detail.provisionalAmount)));
        $("#sellorder-detail-provisional-at").text(formatDateTime(detail.provisionalAt));
        $("#sellorder-detail-final-rate").text(formatRate(detail.finalRate));
        $("#sellorder-detail-final-amount").text(formatAmount(truncateAmount(detail.finalAmount)));
        $("#sellorder-detail-final-amount-detail").text(formatAmount(truncateAmount(detail.finalAmount)));
        $("#sellorder-detail-final-at").text(formatDateTime(detail.finalAt));
        $("#sellorder-detail-settlement-status").text(
            SETTLEMENT_STATUS_LABEL[detail.settlementStatus] || detail.settlementStatus || "-"
        );

        $("#sellorder-detail-variance").text(
            orderAmount == null || detail.finalAmount == null
                ? "-"
                : "체결대비 " + formatVariance(orderAmount, detail.finalAmount)
        );

        $("#sellorder-detail-source-broker").text(detail.sourceBroker || "-");
        $("#sellorder-detail-purchase-date").text(formatDateTime(detail.purchaseDate));
        $("#sellorder-detail-purchase-price").text(formatPrice(truncateAmount(detail.purchasePrice), detail.purchaseCurrency));

        $("#sellorder-detail-drawer").addClass("is-open").attr("aria-hidden", "false");
        $("#sellorder-drawer-backdrop").prop("hidden", false);
    }

    function openDrawer(orderId) {
        MARIA.auth.ajax({
            url: "/api/admin/sell-orders/" + orderId + "/detail",
            method: "GET"
        })
            .done(function (res) {
                renderDrawer(res.data || {});
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                showError((xhr.responseJSON && xhr.responseJSON.message) || "매도 상세 정보를 불러오지 못했습니다.");
            });
    }

    $(document).on("click", "#sellOrderListBody tr[data-order-id]", function () {
        selectedOrderId = Number($(this).data("order-id"));
        $("#sellOrderListBody tr[data-order-id]").removeClass("is-selected");
        $(this).addClass("is-selected");
        openDrawer($(this).data("order-id"));
    });

    $("#sellorder-drawer-close, #sellorder-drawer-backdrop").on("click", closeDrawer);

    $(document).on("keydown", function (event) {
        if (event.key === "Escape") {
            closeDrawer();
        }
    });

    function loadSellOrders() {
        var $content = $(".content");
        var scrollTop = $content.scrollTop();
        $("#sellOrderListBody").html('<tr><td colspan="8" class="sellorder-loading">불러오는 중...</td></tr>');
        hideError();

        MARIA.auth.ajax({
            url: "/api/admin/sell-orders/history",
            method: "GET",
            data: buildFilterParams()
        })
            .done(function (res) {
                var page = res.data || { content: [], totalCount: 0 };
                var totalCount = page.totalCount || 0;
                totalPages = Math.max(1, Math.ceil(totalCount / PAGE_SIZE));
                $("#sellOrderCount").text(totalCount + "건");
                renderRows(page.content || []);
                renderPagination();
                $content.scrollTop(scrollTop);
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                $("#sellOrderListBody").empty();
                showError((xhr.responseJSON && xhr.responseJSON.message) || "매도 · 환전 내역을 불러오지 못했습니다.");
            });
    }

    $("#sellOrderFilterSearch").on("click", function () {
        currentPage = 0;
        loadSellOrders();
    });

    $("#sellOrderFilterKeyword").on("keydown", function (event) {
        if (event.key === "Enter") {
            currentPage = 0;
            loadSellOrders();
        }
    });

    $("#sellOrderFilterStatus").on("change", function () {
        currentPage = 0;
        loadSellOrders();
    });

    $("#sellOrderFilterReset").on("click", function () {
        $("#sellOrderFilterKeyword").val("");
        $("#sellOrderFilterStatus").val("");
        $("#sellOrderFilterStartDate").val("");
        $("#sellOrderFilterEndDate").val("");
        currentPage = 0;
        loadSellOrders();
    });

    var deepLinkAccountNo = MARIA.deeplink.accountNoFromUrl();
    if (deepLinkAccountNo) {
        $("#sellOrderFilterKeyword").val(deepLinkAccountNo);
        currentPage = 0;
    }

    loadSellOrderSummary();
    loadSellOrders();
});
