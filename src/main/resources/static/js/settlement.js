$(function () {
    var batches = [];
    var selectedBatchId = null;
    var currentPage = 1;
    var PAGE_SIZE = 5;
    var items = [];
    var allBatchItems = [];
    var selectedItemId = null;
    var currentItemPage = 1;
    var ITEM_PAGE_SIZE = 10;
    var itemFilter = "all";
    var LABELS = { RUNNING: "진행 중", COMPLETED: "완료", FAILED: "실패", SUCCESS: "성공" };
    var DATE_TIME_FORMATTER = new Intl.DateTimeFormat("ko-KR", { year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
    var KRW_FORMATTER = new Intl.NumberFormat("ko-KR");
    var RATE_FORMATTER = new Intl.NumberFormat("ko-KR", { minimumFractionDigits: 2, maximumFractionDigits: 6 });
    var batchTrendChart = null;
    var ITEM_TREND_GROUPS = [
        { key: "total", label: "전체", color: "#64748b" },
        { key: "success", label: "성공", color: "#16a34a" },
        { key: "failed", label: "실패", color: "#dc2626" }
    ];

    function escapeHtml(value) { return $("<div>").text(value == null ? "-" : value).html(); }
    function toCount(value) { return Number(value || 0); }
    function formatDateTime(value) { return value ? DATE_TIME_FORMATTER.format(new Date(value)) : "-"; }
    function formatAmount(value) { return value == null ? "-" : "₩" + KRW_FORMATTER.format(value); }
    function formatRate(value) { return value == null ? "-" : RATE_FORMATTER.format(value); }
    function showError(message) { MARIA.ui.showError(message); }
    function statusBadge(status) { var key = (status || "").toLowerCase(); return '<span class="settlement-status ' + key + '">' + escapeHtml(LABELS[status] || status) + "</span>"; }
    function errorMessage(xhr, fallback) { return (xhr.responseJSON && xhr.responseJSON.message) || fallback; }
    function handleRequestFailure(xhr, fallback) { if (xhr.status !== 401) showError(errorMessage(xhr, fallback)); }
    function canExecuteSettlement() {
        var admin = MARIA.auth.currentAdmin();
        return !!admin && (admin.role === "ADMIN" || admin.role === "SETTLEMENT");
    }
    function setTextValues(values) {
        Object.keys(values).forEach(function (selector) { $(selector).text(values[selector]); });
    }
    function renderEmptyTable($body, paginationSelector, message) {
        $body.append('<tr><td colspan="8" class="settlement-empty">' + message + "</td></tr>");
        $(paginationSelector).hide();
    }

    function paginate(list, page, pageSize) {
        var totalPages = Math.max(1, Math.ceil(list.length / pageSize));
        var current = Math.min(page, totalPages);
        return { current: current, total: totalPages, items: list.slice((current - 1) * pageSize, current * pageSize) };
    }

    function renderPagination(containerSelector, page, onPageChange) {
        var $pagination = $(containerSelector).empty();
        if (page.total <= 1) {
            $pagination.hide();
            return;
        }
        var blockSize = 10;
        var blockStart = Math.floor((page.current - 1) / blockSize) * blockSize + 1;
        var blockEnd = Math.min(page.total, blockStart + blockSize - 1);

        function addButton(label, targetPage, disabled, active) {
            var $button = $("<button>", {
                type: "button",
                class: "page-btn" + (active ? " active" : ""),
                text: label,
                disabled: disabled || active
            });
            if (!disabled && !active) {
                $button.on("click", function () { onPageChange(targetPage); });
            }
            $pagination.append($button);
        }

        addButton("이전", blockStart - 1, blockStart === 1, false);
        for (var pageNumber = blockStart; pageNumber <= blockEnd; pageNumber += 1) {
            addButton(String(pageNumber), pageNumber, false, pageNumber === page.current);
        }
        addButton("다음", blockEnd + 1, blockEnd === page.total, false);
        $pagination.css("display", "flex");
    }

    function startOfDay(value) {
        var date = new Date(value);
        date.setHours(0, 0, 0, 0);
        return date;
    }

    function itemCounts(batchList) {
        return batchList.reduce(function (counts, batch) {
            var total = toCount(batch.totalCount);
            var processed = toCount(batch.processedCount);
            counts.total += total;
            counts.success += toCount(batch.successCount);
            counts.failed += toCount(batch.failedCount);
            counts.processed += processed;
            return counts;
        }, { total: 0, success: 0, failed: 0, processed: 0 });
    }

    function batchesExecutedOn(date) {
        return batches.filter(function (batch) {
            if (!batch.executedAt) return false;
            return startOfDay(batch.executedAt).getTime() === date.getTime();
        });
    }

    function formatDate(date) {
        return date.getFullYear() + "." + String(date.getMonth() + 1).padStart(2, "0") + "." + String(date.getDate()).padStart(2, "0");
    }

    function renderBatchCount() {
        $("#settlementBatchCount").text(batches.length + "건");
    }

    function renderBatchTrend() {
        var latestExecutedAt = batches.reduce(function (latest, batch) {
            if (!batch.executedAt) return latest;
            var executedAt = new Date(batch.executedAt);
            return !latest || executedAt > latest ? executedAt : latest;
        }, null);
        var latestDate = startOfDay(latestExecutedAt || new Date());
        var dates = Array.from({ length: 14 }, function (_, index) {
            var date = new Date(latestDate);
            date.setDate(latestDate.getDate() - 13 + index);
            return date;
        });
        var datasets = ITEM_TREND_GROUPS.map(function (group) {
            return {
                label: group.label,
                data: dates.map(function (date) {
                    return itemCounts(batchesExecutedOn(date))[group.key];
                }),
                borderColor: group.color,
                backgroundColor: group.color,
                tension: 0.3,
                borderWidth: 2,
                pointRadius: 3,
                pointHoverRadius: 5,
                pointHitRadius: 12
            };
        });
        var labels = dates.map(function (date) { return (date.getMonth() + 1) + "/" + date.getDate(); });
        $("#settlementTrendTotal").text(itemCounts(batches).total + "건");
        if (batchTrendChart) batchTrendChart.destroy();
        batchTrendChart = new Chart($("#settlementBatchTrend")[0], {
            type: "line",
            data: { labels: labels, datasets: datasets },
            options: {
                animation: false,
                maintainAspectRatio: false,
                plugins: { legend: { display: false }, tooltip: { displayColors: false, callbacks: { label: function (context) { return context.dataset.label + ": " + context.parsed.y + "건"; } } } },
                scales: { x: { grid: { display: false }, ticks: { color: "#64748b", font: { size: 10 } } }, y: { beginAtZero: true, ticks: { precision: 0, color: "#64748b", font: { size: 10 } }, grid: { color: "#e2e8f0" } } },
                onClick: function (_, elements) { if (elements.length) renderTrendDetail(dates[elements[0].index]); }
            }
        });
        renderTrendDetail(latestDate);
    }

    function renderTrendDetail(date) {
        var counts = itemCounts(batchesExecutedOn(date));
        setTextValues({
            "#settlementTrendDetailDate": formatDate(date) + " 정산 항목 상세",
            "#settlementTrendDetailTotal": counts.total + "건",
            "#settlementTrendDetailSuccess": counts.success + "건",
            "#settlementTrendDetailFailed": counts.failed + "건",
            "#settlementTrendDetailProcessed": counts.processed + "건"
        });
        $("#settlementTrendDetail").css("display", "block");
    }

    function renderBatches() {
        var $body = $("#settlementBatchBody").empty();
        var page = paginate(batches, currentPage, PAGE_SIZE);
        currentPage = page.current;
        if (!batches.length) { renderEmptyTable($body, "#settlementPagination", "실행 이력이 없습니다."); return; }
        page.items.forEach(function (batch) {
            $body.append('<tr class="settlement-batch-row' + (batch.batchId === selectedBatchId ? " is-selected" : "") + '" data-batch-id="' + batch.batchId + '">' +
                "<td>#" + batch.batchId + "</td><td>" + formatDateTime(batch.executedAt) + "</td><td>" + statusBadge(batch.status) + "</td>" +
                "<td>" + batch.totalCount + "</td><td>" + batch.successCount + "</td><td>" + batch.failedCount + "</td><td>" + batch.processedCount + "</td><td>" + escapeHtml(batch.runId) + "</td></tr>");
        });
        renderPagination("#settlementPagination", page, function (targetPage) {
            currentPage = targetPage;
            renderBatches();
        });
    }

    function renderItems(itemList) {
        var $body = $("#settlementItemBody").empty();
        var page = paginate(itemList, currentItemPage, ITEM_PAGE_SIZE);
        currentItemPage = page.current;
        if (!itemList.length) { renderEmptyTable($body, "#settlementItemPagination", "정산 항목이 없습니다."); return; }
        page.items.forEach(function (item) {
            $body.append('<tr class="settlement-item-row' + (item.itemId === selectedItemId ? " is-selected" : "") + '" data-item-id="' + item.itemId + '"><td>#' + item.itemId + "</td><td>" + MARIA.fmt.accountNoHtml(item.accountNo) + "</td><td>" + escapeHtml(item.ticker) + "</td><td>" + formatAmount(item.provisionalAmount) + "</td><td>" + formatAmount(item.finalAmount) + "</td><td>" + statusBadge(item.result) + "</td><td>" + escapeHtml(item.failureCode || item.failureMessage) + "</td><td>" + formatDateTime(item.processedAt) + "</td></tr>");
        });
        renderPagination("#settlementItemPagination", page, function (targetPage) {
            currentItemPage = targetPage;
            renderItems(items);
        });
    }

    function selectBatch(batchId, preserveItemPage) {
        selectedBatchId = Number(batchId);
        if (!preserveItemPage) {
            itemFilter = "all";
            currentItemPage = 1;
        }
        return MARIA.auth.ajax({ url: "/api/admin/settlement/batches/" + selectedBatchId, method: "GET" })
            .done(function (res) {
                var latestBatch = res.data;
                var batchIndex = batches.findIndex(function (item) { return item.batchId === selectedBatchId; });
                if (batchIndex !== -1) {
                    batches[batchIndex] = latestBatch;
                }
                renderBatchCount();
                renderBatchTrend();
                renderBatches();
                renderDetail(latestBatch);
                $("#settlementItemFilter").val(itemFilter);
                loadBatchItems(preserveItemPage);
            })
            .fail(function (xhr) { handleRequestFailure(xhr, "배치 상태를 불러오지 못했습니다."); });
    }

    function renderDetail(batch) {
        $("#settlementDetail").addClass("is-open").attr("aria-hidden", "false");
        $("#settlementDetailBackdrop").prop("hidden", false);
        $("body").addClass("settlement-drawer-open");
        setTextValues({
            "#detailBatchTitle": "배치 #" + selectedBatchId + " 상세",
            "#detailBatchFailure": batch.failureMessage || "",
            "#detailBatchExecutedAt": formatDateTime(batch.executedAt),
            "#detailBatchRunId": batch.runId || "-",
            "#detailBatchTotalCount": batch.totalCount + "건",
            "#detailBatchSuccessCount": batch.successCount + "건",
            "#detailBatchFailedCount": batch.failedCount + "건",
            "#detailBatchProcessedCount": batch.processedCount + "건"
        });
        $("#retrySettlementBatch").toggle(canExecuteSettlement() && batch.status === "FAILED");
        $("#settlementItemFilter").toggle(batch.status === "FAILED");
    }

    function closeSettlementItemDetail() {
        $("#settlementItemDetail").removeClass("is-open").attr("aria-hidden", "true");
        $("#settlementItemDetailBackdrop").prop("hidden", true);
    }

    function closeSettlementDetail() {
        closeSettlementItemDetail();
        $("#settlementDetail").removeClass("is-open").attr("aria-hidden", "true");
        $("#settlementDetailBackdrop").prop("hidden", true);
        $("body").removeClass("settlement-drawer-open");
    }

    function loadBatchItems(preserveItemPage) {
        $("#settlementItemBody").html('<tr><td colspan="8" class="settlement-empty">불러오는 중...</td></tr>');
        loadAllBatchItems(preserveItemPage);
    }

    function latestItemsByExchange(itemList) {
        var latestByExchange = {};
        itemList.forEach(function (item) {
            var key = String(item.exchangeId);
            if (!latestByExchange[key] || Number(item.itemId) > Number(latestByExchange[key].itemId)) {
                latestByExchange[key] = item;
            }
        });
        return Object.keys(latestByExchange).map(function (key) { return latestByExchange[key]; })
            .sort(function (left, right) { return Number(right.itemId) - Number(left.itemId); });
    }

    function applyItems(responseItems, preserveItemPage) {
        var latestItems = latestItemsByExchange(responseItems);
        items = itemFilter === "all"
            ? latestItems
            : latestItems.filter(function (item) {
                return item.result === (itemFilter === "success" ? "SUCCESS" : "FAILED");
            });
        if (!preserveItemPage) {
            currentItemPage = 1;
            selectedItemId = null;
            closeSettlementItemDetail();
        }
        renderItems(items);
    }

    function loadAllBatchItems(preserveItemPage) {
        MARIA.auth.ajax({ url: "/api/admin/settlement/batches/detail/" + selectedBatchId, method: "GET" })
            .done(function (res) {
                allBatchItems = res.data || [];
                applyItems(allBatchItems, preserveItemPage);
            })
            .fail(function (xhr) { if (xhr.status !== 401) $("#settlementItemBody").empty(); handleRequestFailure(xhr, "정산 항목을 불러오지 못했습니다."); });
    }

    function selectItem(itemId) {
        selectedItemId = Number(itemId);
        MARIA.auth.ajax({ url: "/api/admin/settlement/batches/" + selectedBatchId + "/items/" + selectedItemId, method: "GET" })
            .done(function (res) {
                var item = res.data;
                renderItems(items);
                $("#settlementItemDetail").addClass("is-open").attr("aria-hidden", "false");
                $("#settlementItemDetailBackdrop").prop("hidden", false);
                var difference = item.finalAmount == null || item.provisionalAmount == null ? null : Number(item.finalAmount) - Number(item.provisionalAmount);
                setTextValues({
                    "#detailItemTitle": "정산 항목 #" + item.itemId + " 상세",
                    "#detailItemFailure": item.failureMessage || "",
                    "#detailItemExchangeId": item.exchangeId || "-",
                    "#detailItemAccountId": item.accountId || "-",
                    "#detailItemOrderId": item.orderId || "-",
                    "#detailItemProduct": [item.ticker, item.productName].filter(Boolean).join(" · ") || "-",
                    "#detailItemExchangeStatus": item.settlementStatus || "-",
                    "#detailItemFailureCode": item.failureCode || "-",
                    "#detailItemProvisionalAmount": formatAmount(item.provisionalAmount),
                    "#detailItemProvisionalAt": formatDateTime(item.provisionalAt),
                    "#detailItemProvisionalRate": formatRate(item.settlementFxRate),
                    "#detailItemFinalAmount": formatAmount(item.finalAmount),
                    "#detailItemFinalAt": formatDateTime(item.finalAt),
                    "#detailItemFinalRate": formatRate(item.finalRate),
                    "#detailItemDifference": difference == null ? "-" : (difference > 0 ? "+" : "") + formatAmount(difference)
                });
                $("#detailItemAccountNo").html(MARIA.fmt.accountNoHtml(item.accountNo));
                renderRetryHistory(item.exchangeId);
                $("#retrySettlementItem").toggle(canExecuteSettlement() && item.result === "FAILED");
            })
            .fail(function (xhr) { handleRequestFailure(xhr, "정산 항목 상세를 불러오지 못했습니다."); });
    }

    function renderRetryHistory(exchangeId) {
        var $history = $("#settlementRetryHistory").empty();
        var history = allBatchItems.filter(function (item) { return item.exchangeId === exchangeId; })
            .sort(function (left, right) { return Number(right.itemId) - Number(left.itemId); });
        if (!history.length) {
            $history.append("<li>처리 이력이 없습니다.</li>");
            return;
        }
        history.forEach(function (historyItem) {
            $history.append("<li><strong>Item #" + historyItem.itemId + " " + escapeHtml(LABELS[historyItem.result] || historyItem.result || "대기") + "</strong><span>" + formatDateTime(historyItem.processedAt) + "</span><small>" + escapeHtml(historyItem.failureCode || historyItem.failureMessage || "처리 완료") + "</small></li>");
        });
    }

    function loadBatches() {
        MARIA.auth.ajax({ url: "/api/admin/settlement/batches", method: "GET" }).done(function (res) {
            batches = res.data || []; renderBatchCount(); renderBatchTrend(); renderBatches();
        }).fail(function (xhr) { if (xhr.status !== 401) $("#settlementBatchBody").empty(); handleRequestFailure(xhr, "배치 목록을 불러오지 못했습니다."); });
    }

    $(document).on("click", ".settlement-batch-row", function () { selectBatch($(this).data("batch-id")); });
    $(document).on("click", ".settlement-item-row", function () { selectItem($(this).data("item-id")); });
    $(document).on("mouseenter", ".settlement-item-table td, .settlement-batch-summary strong", function () {
        if (this.scrollWidth > this.clientWidth) {
            $(this).attr("title", $(this).text().trim()).attr("data-overflow-title", "true");
        }
    }).on("mouseleave", "[data-overflow-title='true']", function () {
        $(this).removeAttr("title data-overflow-title");
    });
    $("#closeSettlementDetail, #settlementDetailBackdrop").on("click", closeSettlementDetail);
    $("#closeSettlementItemDetail, #settlementItemDetailBackdrop").on("click", closeSettlementItemDetail);
    $(document).on("keydown", function (event) {
        if (event.key !== "Escape") return;
        if ($("#settlementItemDetail").hasClass("is-open")) {
            closeSettlementItemDetail();
        } else {
            closeSettlementDetail();
        }
    });
    $("#settlementItemFilter").on("change", function () {
        itemFilter = $(this).val();
        loadBatchItems();
    });
    $("#executeSettlement").prop("disabled", !canExecuteSettlement()).on("click", function () { if (!canExecuteSettlement()) return; MARIA.auth.ajax({ url: "/api/admin/settlement/jobs", method: "POST" }).done(function (res) { selectedBatchId = res.data.batchId; currentPage = 1; loadBatches(); }).fail(function (xhr) { handleRequestFailure(xhr, "정산 배치 실행에 실패했습니다."); }); });
    $("#retrySettlementBatch").on("click", function () { if (!selectedBatchId || !canExecuteSettlement()) return; MARIA.auth.ajax({ url: "/api/admin/settlement/batches/" + selectedBatchId + "/retry", method: "POST" }).done(function () { loadBatches(); }).fail(function (xhr) { handleRequestFailure(xhr, "정산 배치 재처리에 실패했습니다."); }); });
    $("#retrySettlementItem").on("click", function () {
        if (!selectedBatchId || !selectedItemId || !canExecuteSettlement()) return;
        MARIA.auth.ajax({
            url: "/api/admin/settlement/batches/" + selectedBatchId + "/items/" + selectedItemId + "/retry",
            method: "POST"
        }).done(function (res) {
            var retryItemId = res.data && res.data.itemId;
            if (res.data) allBatchItems.push(res.data);
            selectBatch(selectedBatchId, true).done(function () {
                if (retryItemId) selectItem(retryItemId);
            });
        }).fail(function (xhr) {
            handleRequestFailure(xhr, "정산 항목 재처리에 실패했습니다.");
        });
    });
    loadBatches();
});
