$(function () {
    var STATUS = MARIA.status; // 처리 단계 표기는 js/admin/status-labels.js 의 공통 매핑

    var KRW_FORMATTER = new Intl.NumberFormat("ko-KR");
    var QTY_FORMATTER = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 4 });

    var PAGE_SIZE = 20;
    var currentPage = 0;
    var sortSpec = null; // 머리글로 고른 정렬(서버가 전체 데이터에서 정렬)
    var totalCount = 0;
    var selectedOrderId = null;
    var SKELETON_ROWS = 8;
    var SKELETON_WIDTHS = ["62%", "70%", "48%", "54%", "66%", "78%", "56%", "40%", "60%"];

    // ---------- 서식 ----------
    function escapeHtml(value) {
        return $("<div>").text(value == null ? "" : value).html();
    }

    function pad(n) {
        return String(n).padStart(2, "0");
    }

    // 금액·수량은 숫자만 (단위는 열 이름·요약 단위가 말해 준다). 값이 없으면 "-".
    function formatAmount(amount) {
        return amount == null ? "-" : KRW_FORMATTER.format(Math.trunc(amount));
    }

    function formatQty(qty) {
        return qty == null ? "-" : QTY_FORMATTER.format(qty);
    }

    function parseDate(value) {
        var date = value ? new Date(value) : null;
        return !date || isNaN(date.getTime()) ? null : date;
    }

    function formatDateTime(value) {
        var date = parseDate(value);
        return !date ? "-" : date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate()) +
            " " + pad(date.getHours()) + ":" + pad(date.getMinutes());
    }

    function formatDate(value) {
        var date = parseDate(value);
        return !date ? "-" : date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate());
    }

    function formatRate(rate) {
        return rate == null ? "-" : Number(rate).toFixed(2) + "원";
    }

    function formatPrice(price, currency) {
        if (price == null) {
            return "-";
        }
        return Number(price).toLocaleString("ko-KR", { maximumFractionDigits: 4 }) + (currency ? " " + currency : "");
    }

    function formatVariance(orderAmount, finalAmount) {
        if (orderAmount == null || finalAmount == null) {
            return "-";
        }
        var diff = Math.trunc(finalAmount - orderAmount);
        return (diff > 0 ? "+" : "") + KRW_FORMATTER.format(diff) + "원";
    }

    function formatChangeRate(rate) {
        if (rate === null || rate === undefined) {
            return "—";
        }
        var num = Number(rate);
        var arrow = num > 0 ? "▲" : num < 0 ? "▼" : "-";
        return arrow + Math.abs(num).toFixed(1) + "%";
    }

    function productLabel(order) {
        if (!order.ticker) {
            return "-";
        }
        return (order.name ? order.name + " " : "") + order.ticker;
    }

    function chip(stage) {
        return '<span class="adm-chip adm-chip-' + STATUS.sellStageTone(stage) + '">' +
            escapeHtml(STATUS.sellStageLabel(stage)) + "</span>";
    }

    // 조회조건의 상태 목록: 처리 단계 이름으로 보이되, 서버에는 지금과 같은 status 값(SellOrderStatus)만 보낸다.
    // 체결 값(EXECUTED)에는 원화 확정 건도 들어 있다 (서버에 정산 상태로 거르는 조건이 없다).
    var STATUS_OPTIONS = [
        { value: "RECEIVED", label: STATUS.sellStageLabel("ORDER") },
        { value: "EXECUTED", label: STATUS.sellStageLabel("EXECUTED") + " · " + STATUS.sellStageLabel("CONFIRMED") },
        { value: "REJECTED", label: STATUS.sellStageLabel("FAILED") }
    ];
    STATUS_OPTIONS.forEach(function (option) {
        $("#sellOrderFilterStatus").append($("<option>", { value: option.value, text: option.label }));
    });

    // ---------- 요약 ----------
    function renderSummary(summary) {
        $("#kpiTodayExecutedCount").text(KRW_FORMATTER.format(summary.todayExecutedCount || 0));
        $("#kpiPendingProvisionalAmount").text(formatAmount(summary.pendingProvisionalAmount));
        $("#kpiTodayFinalizedAmount").text(formatAmount(summary.todayFinalizedAmount));
        // 원본에 없는 기존 요소 (숨김): 오늘 매도금액과 전일 대비 증감
        $("#kpiTodaySellAmount").text(formatAmount(summary.todaySellAmount));
        $("#kpiTodaySellAmountChange").text("매도금액 " + formatChangeRate(summary.todaySellAmountChangeRate));
        $("#kpiTodayExecutedCountChange").text("건수 " + formatChangeRate(summary.todayExecutedCountChangeRate));
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

    // ---------- 조회 ----------
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
        if (sortSpec) {
            params.sort = sortSpec.key;
            params.direction = sortSpec.dir;
        }
        return params;
    }

    // ---------- 표 ----------
    function stateRow(className, innerHtml, fill) {
        return '<tr class="is-state ' + (fill ? "is-state-fill " : "") + className + '"><td colspan="10">' + innerHtml + "</td></tr>";
    }

    function renderLoading() {
        var html = "";
        for (var r = 0; r < SKELETON_ROWS; r++) {
            html += '<tr class="is-state sellorder-loading" aria-label="불러오는 중"><td colspan="10"><div class="adm-skel-row">' +
                '<div class="adm-skel-cell"></div>';
            for (var c = 0; c < 9; c++) {
                html += '<div class="adm-skel-cell"><span style="width:' + SKELETON_WIDTHS[(c + r) % SKELETON_WIDTHS.length] +
                    ';animation-delay:' + ((c + r) % 5) * 0.06 + 's"></span></div>';
            }
            html += "</div></td></tr>";
        }
        $("#sellOrderListBody").html(html);
    }

    function renderRows(orders) {
        var $body = $("#sellOrderListBody").empty();

        if (!orders.length) {
            $body.append(stateRow("sellorder-empty",
                '<div class="adm-state-empty">' +
                '<img src="/images/admin/char-front.png" alt="">' +
                '<div class="adm-state-empty-title">매도 · 환전 내역이 없습니다.</div>' +
                '<div class="adm-state-empty-desc">조회조건을 바꾸거나 초기화한 뒤 다시 조회해 주세요.</div>' +
                '<button type="button" class="adm-btn sellorder-empty-reset">조건 초기화</button>' +
                "</div>", true));
            return;
        }

        orders.forEach(function (order, index) {
            var stage = STATUS.sellStage(order.status, null, order.finalAmount);
            var selectedClass = Number(order.orderId) === Number(selectedOrderId) ? " is-selected" : "";
            $body.append(
                '<tr class="is-clickable' + selectedClass + '" tabindex="0" data-order-id="' + order.orderId + '">' +
                '<td class="adm-col-no">' + (currentPage * PAGE_SIZE + index + 1) + "</td>" +
                "<td><span>" + escapeHtml(MARIA.fmt.hyphenateAccountNo(order.accountNo)) + "</span></td>" +
                "<td><span>" + escapeHtml(order.customerName || "") + "</span></td>" +
                "<td><span>" + escapeHtml(productLabel(order)) + "</span></td>" +
                '<td class="is-r"><span>' + formatQty(order.sellQty) + "</span></td>" +
                '<td class="is-r"><span>' + formatAmount(order.basePrice) + "</span></td>" +
                "<td><span>" + formatDateTime(order.processedAt) + "</span></td>" +
                '<td class="is-r"><span>' + formatAmount(order.provisionalAmount) + "</span></td>" +
                '<td class="is-r"><span>' + formatAmount(order.finalAmount) + "</span></td>" +
                '<td class="is-c">' + chip(stage) + "</td>" +
                "</tr>"
            );
        });
    }

    function renderPagination() {
        var $pagination = $("#sellOrderPagination").empty();
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
                    loadSellOrders();
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

    function loadSellOrders() {
        var $content = $(".content");
        var scrollTop = $content.scrollTop();
        closeDrawer();
        renderLoading();

        MARIA.auth.ajax({
            url: "/api/admin/sell-orders/history",
            method: "GET",
            data: buildFilterParams()
        })
            .done(function (res) {
                var page = res.data || { content: [], totalCount: 0 };
                totalCount = page.totalCount || 0;
                $("#sellOrderCount").text(KRW_FORMATTER.format(totalCount));
                renderRows(page.content || []);
                renderPagination();
                $content.scrollTop(scrollTop);
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                var message = (xhr.responseJSON && xhr.responseJSON.message) || "매도 · 환전 내역을 불러오지 못했습니다.";
                totalCount = 0;
                $("#sellOrderCount").text("-");
                $("#sellOrderPagination").empty();
                $("#sellOrderListBody").html(stateRow("sellorder-error",
                    '<div class="adm-state-error">' +
                    '<div class="adm-state-error-text">' +
                    '<div class="adm-state-error-title">' + escapeHtml(message) + "</div>" +
                    '<div class="adm-state-error-desc">잠시 후 다시 시도해 주세요.</div>' +
                    "</div>" +
                    '<button type="button" class="adm-btn sellorder-retry">다시 시도</button>' +
                    "</div>", true));
            });
    }

    // ---------- 상세 패널 ----------
    function flowStep(index, label, state, sub) {
        var STATE_LABEL = { done: "완료", cur: "진행 중", todo: "대기", fail: "실패" };
        return '<div class="adm-flow-step is-' + state + '">' +
            '<div class="adm-flow-head"><span class="adm-flow-arrow">' + (index ? "→" : "") + "</span>" +
            (index + 1) + "단계 · " + STATE_LABEL[state] + "</div>" +
            '<div class="adm-flow-label">' + escapeHtml(label) + "</div>" +
            '<div class="adm-flow-sub">' + escapeHtml(sub) + "</div></div>";
    }

    // 처리 단계: 주문 → 체결 → 원화 확정. 시각은 상세 응답의 processedAt / finalAt 으로 채운다.
    function renderFlow(detail, stage) {
        var finalDate = detail.finalAt ? formatDate(detail.finalAt) : null;
        var state = {
            ORDER: ["cur", "todo", "todo"],
            EXECUTED: ["done", "cur", "todo"],
            CONFIRMED: ["done", "done", "done"],
            FAILED: ["done", "fail", "todo"]
        }[stage] || ["todo", "todo", "todo"];
        var orderSub = formatDateTime(detail.processedAt);
        var executedSub = stage === "FAILED" ? "체결 실패" : stage === "ORDER" ? "-" : orderSub;
        var confirmSub = stage === "CONFIRMED"
            ? (finalDate ? finalDate + " · " : "") + "정산 완료 · 1년 유지 시작"
            : stage === "EXECUTED" && finalDate ? finalDate + " 예정" : "-";
        $("#sellorder-detail-flow").html(
            flowStep(0, STATUS.sellStageLabel("ORDER"), state[0], orderSub) +
            flowStep(1, STATUS.sellStageLabel("EXECUTED"), state[1], executedSub) +
            flowStep(2, STATUS.sellStageLabel("CONFIRMED"), state[2], confirmSub)
        );
    }

    function renderDrawer(detail) {
        var stage = STATUS.sellStage(detail.status, detail.settlementStatus, detail.finalAmount);
        var orderAmount = detail.sellQty != null && detail.basePrice != null
            ? detail.sellQty * detail.basePrice
            : null;

        $("#sellorder-detail-title").text(productLabel(detail));
        $("#sellorder-detail-customer-name").text(detail.customerName || "-");
        $("#sellorder-detail-account-no").text(detail.accountNo ? MARIA.fmt.hyphenateAccountNo(detail.accountNo) : "-");

        $("#sellorder-detail-status").html(chip(stage));
        $("#sellorder-detail-base-price").text(detail.basePrice == null ? "-" : formatAmount(detail.basePrice) + "원");
        $("#sellorder-detail-provisional-amount").text(detail.provisionalAmount == null ? "-" : formatAmount(detail.provisionalAmount) + "원");
        $("#sellorder-detail-final-amount").text(detail.finalAmount == null ? "-" : formatAmount(detail.finalAmount) + "원");

        renderFlow(detail, stage);

        // 매수 lot
        $("#sellorder-detail-source-broker").text(detail.sourceBroker || "-");
        $("#sellorder-detail-purchase-date").text(formatDate(detail.purchaseDate));
        $("#sellorder-detail-purchase-price").text(formatPrice(detail.purchasePrice, detail.purchaseCurrency));
        $("#sellorder-detail-lot-qty").text(formatQty(detail.sellQty));

        // 원본에 없는 기존 요소: 가환전 · 확정산
        $("#sellorder-detail-qty").text(detail.sellQty == null ? "-" : formatQty(detail.sellQty) + "주");
        $("#sellorder-detail-processed-at").text(formatDateTime(detail.processedAt));
        $("#sellorder-detail-fx-rate").text(detail.settlementFxRate == null ? "-" : formatRate(detail.settlementFxRate));
        $("#sellorder-detail-variance").text(
            orderAmount == null || detail.finalAmount == null ? "-" : formatVariance(orderAmount, detail.finalAmount)
        );
        $("#sellorder-detail-provisional-at").text(formatDateTime(detail.provisionalAt));
        $("#sellorder-detail-final-rate").text(formatRate(detail.finalRate));
        $("#sellorder-detail-final-at").text(formatDateTime(detail.finalAt));
        $("#sellorder-detail-settlement-status").text(STATUS.settlementStatusLabel(detail.settlementStatus));

        setFold(false);
        $("#sellorder-drawer-backdrop, #sellorder-detail-panel").addClass("is-open");
        $("#sellorder-detail-panel").attr("aria-hidden", "false");
        $("#sellorder-detail-panel .adm-panel-body").scrollTop(0);
        $("#sellorder-drawer-close").trigger("focus");
    }

    function closeDrawer() {
        if (!$("#sellorder-detail-panel").hasClass("is-open")) {
            return;
        }
        $("#sellorder-drawer-backdrop, #sellorder-detail-panel").removeClass("is-open");
        $("#sellorder-detail-panel").attr("aria-hidden", "true");
        var $row = $("#sellOrderListBody tr.is-selected").removeClass("is-selected");
        if ($row.length) {
            $row.trigger("focus");
        }
        selectedOrderId = null;
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
                MARIA.ui.showError((xhr.responseJSON && xhr.responseJSON.message) || "매도 상세 정보를 불러오지 못했습니다.");
            });
    }

    function selectRow($row) {
        selectedOrderId = Number($row.data("order-id"));
        $("#sellOrderListBody tr[data-order-id]").removeClass("is-selected");
        $row.addClass("is-selected");
        openDrawer($row.data("order-id"));
    }

    $("#sellOrderListBody").on("click", "tr[data-order-id]", function () {
        selectRow($(this));
    });
    $("#sellOrderListBody").on("keydown", "tr[data-order-id]", function (event) {
        if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            selectRow($(this));
        }
    });

    $("#sellorder-drawer-close, #sellorder-drawer-backdrop").on("click", closeDrawer);

    // 정산 상세 접기 (처음엔 접힘, 패널을 새로 열 때마다 다시 접는다)
    function setFold(open) {
        $("#sellorder-fold-toggle").attr("aria-expanded", open ? "true" : "false");
        $("#sellorder-fold-body").prop("hidden", !open);
    }
    $("#sellorder-fold-toggle").on("click", function () {
        setFold($(this).attr("aria-expanded") !== "true");
    });

    // ---------- 조회 조건 ----------
    function resetFilters() {
        $("#sellOrderFilterKeyword").val("");
        $("#sellOrderFilterStatus").val("");
        $("#sellOrderFilterStartDate").val("");
        $("#sellOrderFilterEndDate").val("");
        currentPage = 0;
        loadSellOrders();
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

    $("#sellOrderFilterReset").on("click", resetFilters);
    $("#sellOrderListBody").on("click", ".sellorder-empty-reset", resetFilters);
    $("#sellOrderListBody").on("click", ".sellorder-retry", function () {
        loadSellOrderSummary();
        loadSellOrders();
    });

    $("#sellOrderPageSize").on("click", "button", function () {
        var size = parseInt($(this).attr("data-size"), 10);
        if (!size || size === PAGE_SIZE) {
            return;
        }
        PAGE_SIZE = size;
        $("#sellOrderPageSize button").removeClass("is-on");
        $(this).addClass("is-on");
        currentPage = 0;
        loadSellOrders();
    });

    var deepLinkAccountNo = MARIA.deeplink.accountNoFromUrl();
    if (deepLinkAccountNo) {
        $("#sellOrderFilterKeyword").val(deepLinkAccountNo);
        currentPage = 0;
    }

    // ---------- 계좌 한도 조회 패널 ----------
    function sellAcctState(text) {
        $("#sellAcctBody").html('<tr class="is-state"><td colspan="7"><div class="sellacct-empty">' + escapeHtml(text) + "</div></td></tr>");
    }
    function searchAccountLimits() {
        var keyword = $("#sellAcctKeyword").val().trim();
        if (!keyword) {
            sellAcctState("계좌번호 또는 고객명을 입력해 조회하세요.");
            return;
        }
        // 숫자·하이픈만이면 계좌번호, 아니면 고객명으로 찾는다 (서버는 둘 중 하나 이상을 요구)
        var params = /^[0-9-]+$/.test(keyword) ? { accountNo: keyword.replace(/-/g, "") } : { customerName: keyword };
        sellAcctState("불러오는 중...");
        MARIA.auth.ajax({ url: "/api/admin/account/search", method: "GET", data: params })
            .done(function (res) {
                var rows = res.data || [];
                if (!rows.length) {
                    sellAcctState("검색 결과가 없습니다.");
                    return;
                }
                $("#sellAcctBody").html(rows.map(function (r, i) {
                    var limit = Number(r.limitAmount || 0), used = Number(r.usedAmount || 0);
                    return '<tr><td class="adm-col-no">' + (i + 1) + "</td>" +
                        "<td>" + escapeHtml(MARIA.fmt.hyphenateAccountNo(r.accountNo)) + "</td>" +
                        "<td>" + escapeHtml(r.customerName || "-") + "</td>" +
                        '<td class="is-c"><span class="adm-chip adm-chip-' + STATUS.accountStatusTone(r.status) + '">' + escapeHtml(STATUS.accountStatusLabel(r.status)) + "</span></td>" +
                        '<td class="is-r">' + formatAmount(limit) + '</td><td class="is-r">' + formatAmount(used) +
                        '</td><td class="is-r">' + formatAmount(Math.max(0, limit - used)) + "</td></tr>";
                }).join(""));
            })
            .fail(function (xhr) {
                if (xhr.status !== 401) {
                    sellAcctState((xhr.responseJSON && xhr.responseJSON.message) || "계좌를 조회하지 못했습니다.");
                }
            });
    }
    function openSellAcct() {
        $("#sellAcctScrim, #sellAcctPanel").addClass("is-open");
        $("#sellAcctPanel").attr("aria-hidden", "false");
        $("#sellAcctKeyword").trigger("focus");
    }
    function closeSellAcct() {
        $("#sellAcctScrim, #sellAcctPanel").removeClass("is-open");
        $("#sellAcctPanel").attr("aria-hidden", "true");
        $("#sellAcctOpen").trigger("focus");
    }
    $("#sellAcctOpen").on("click", openSellAcct);
    $("#sellAcctClose, #sellAcctScrim").on("click", closeSellAcct);
    $("#sellAcctSubmit").on("click", searchAccountLimits);
    $("#sellAcctKeyword").on("keydown", function (event) {
        if (event.key === "Enter") {
            searchAccountLimits();
        }
    });

    MARIA.tableSort.bind(".sellorder-table", function (key, dir) {
        sortSpec = { key: key, dir: dir };
        currentPage = 0;
        loadSellOrders();
    });

    loadSellOrderSummary();
    loadSellOrders();
});
