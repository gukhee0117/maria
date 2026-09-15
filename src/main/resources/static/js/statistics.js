$(function () {
    var KRW_FORMATTER = new Intl.NumberFormat("ko-KR");
    // 상태/유형 뱃지에 쓰는 것과 같은 계열(accent/success/warning/danger/info/teal)
    var LIGHT_PALETTE = ["#2a78d6", "#1f8a4c", "#c2650c", "#c53030", "#6d3fc9", "#0f8b8d"];
    var DARK_PALETTE = ["#5b9bf0", "#4cc785", "#e0972f", "#e2685f", "#a78bfa", "#4fd1c5"];
    var FILL_ALPHA = "cc"; // 채우기는 반투명하게, 뱃지 느낌
    var BENEFIT_LABEL = {
        POSSIBLE: "가능",
        REDUCED: "축소",
        IMPOSSIBLE: "불가능",
        UNCLASSIFIED: "미분류"
    };
    var PRODUCT_PIE_TOP_N = 5;

    var CHART_FONT = "'Noto Sans KR', sans-serif";
    Chart.defaults.font.family = CHART_FONT;

    var gridColor, tickColor, legendColor, titleColor, cardBg, CHART_COLORS, CHART_FILL_COLORS;

    function refreshThemeColors() {
        var isDark = document.documentElement.getAttribute("data-theme") === "dark";
        gridColor = isDark ? "rgba(255,255,255,0.15)" : "rgba(0,0,0,0.08)";
        tickColor = isDark ? "#e8eaed" : "#64748b";
        legendColor = isDark ? "#e8eaed" : "#45484d";
        titleColor = isDark ? "#e8eaed" : "#1a1a1a";
        cardBg = isDark ? "#1f2430" : "#ffffff";
        CHART_COLORS = isDark ? DARK_PALETTE : LIGHT_PALETTE;
        CHART_FILL_COLORS = CHART_COLORS.map(function (c) { return c + FILL_ALPHA; });
        Chart.defaults.color = legendColor;
    }

    refreshThemeColors();

    var charts = {};

    function formatAmount(amount) {
        return "₩" + KRW_FORMATTER.format(amount || 0);
    }

    function showError(message) {
        $("#dashStatError").text(message).show();
    }

    function hideError() {
        $("#dashStatError").hide();
    }

    function buildFilterParams(extra) {
        var params = {};
        var keyword = $("#statFilterKeyword").val().trim();
        var productName = $("#statFilterProductName").val().trim();
        var startDate = $("#statFilterStartDate").val();
        var endDate = $("#statFilterEndDate").val();
        if (keyword) params.keyword = keyword;
        if (productName) params.productName = productName;
        if (startDate) params.startDate = startDate;
        if (endDate) params.endDate = endDate;
        return $.extend(params, extra);
    }

    function destroyChart(key) {
        if (charts[key]) {
            charts[key].destroy();
            charts[key] = null;
        }
    }

    function tooltipStyle() {
        return {
            backgroundColor: cardBg,
            borderColor: gridColor,
            borderWidth: 1,
            titleColor: titleColor,
            bodyColor: legendColor,
            padding: 10,
            boxPadding: 4,
            displayColors: true,
            titleFont: { size: 12, weight: "600" },
            bodyFont: { size: 12 }
        };
    }

    function renderPieChart(key, canvasId, emptyId, labels, data, tooltipFormatter) {
        destroyChart(key);
        $("#" + emptyId).toggle(!labels.length);
        if (!labels.length) {
            return;
        }
        charts[key] = new Chart($("#" + canvasId)[0], {
            type: "doughnut",
            data: {
                labels: labels,
                datasets: [{
                    data: data,
                    backgroundColor: labels.map(function (_, i) { return CHART_FILL_COLORS[i % CHART_FILL_COLORS.length]; }),
                    hoverBackgroundColor: labels.map(function (_, i) { return CHART_COLORS[i % CHART_COLORS.length]; }),
                    borderWidth: 0,
                    hoverOffset: 6
                }]
            },
            options: {
                maintainAspectRatio: false,
                cutout: "62%",
                plugins: {
                    legend: {
                        position: "bottom",
                        labels: { boxWidth: 8, boxHeight: 8, padding: 12, font: { size: 11 }, usePointStyle: true, pointStyle: "circle" }
                    },
                    tooltip: $.extend({ callbacks: { label: tooltipFormatter } }, tooltipStyle())
                }
            }
        });
    }

    function renderBarChart(key, canvasId, emptyId, labels, data, label, tooltipFormatter) {
        destroyChart(key);
        $("#" + emptyId).toggle(!labels.length);
        if (!labels.length) {
            return;
        }
        charts[key] = new Chart($("#" + canvasId)[0], {
            type: "bar",
            data: {
                labels: labels,
                datasets: [{
                    label: label,
                    data: data,
                    backgroundColor: CHART_FILL_COLORS[0],
                    hoverBackgroundColor: CHART_COLORS[0],
                    borderRadius: 6,
                    borderSkipped: false,
                    maxBarThickness: 56
                }]
            },
            options: {
                maintainAspectRatio: false,
                plugins: {
                    legend: { display: false },
                    tooltip: $.extend({ callbacks: { label: tooltipFormatter } }, tooltipStyle())
                },
                scales: {
                    x: { grid: { display: false }, ticks: { color: tickColor, font: { size: 11 } } },
                    y: {
                        beginAtZero: true,
                        grid: { color: gridColor },
                        border: { display: false },
                        ticks: { color: tickColor, font: { size: 10 }, callback: function (v) { return formatAmount(v); } }
                    }
                }
            }
        });
    }

    function renderLineChart(key, canvasId, emptyId, labels, series) {
        destroyChart(key);
        $("#" + emptyId).toggle(!labels.length);
        if (!labels.length) {
            return;
        }
        charts[key] = new Chart($("#" + canvasId)[0], {
            type: "line",
            data: {
                labels: labels,
                datasets: series.map(function (s, i) {
                    var color = CHART_COLORS[i % CHART_COLORS.length];
                    return {
                        label: s.label,
                        data: s.data,
                        borderColor: color,
                        backgroundColor: color + "26",
                        pointBackgroundColor: color,
                        pointBorderColor: cardBg,
                        fill: true,
                        tension: 0.35,
                        borderWidth: 2,
                        pointRadius: 3,
                        pointBorderWidth: 1.5,
                        pointHoverRadius: 5
                    };
                })
            },
            options: {
                maintainAspectRatio: false,
                interaction: { mode: "index", intersect: false },
                plugins: {
                    legend: {
                        position: "top",
                        align: "end",
                        labels: { boxWidth: 8, boxHeight: 8, padding: 12, font: { size: 11 }, usePointStyle: true, pointStyle: "circle" }
                    },
                    tooltip: $.extend(
                        { callbacks: { label: function (ctx) { return ctx.dataset.label + ": " + formatAmount(ctx.parsed.y); } } },
                        tooltipStyle()
                    )
                },
                scales: {
                    x: { grid: { display: false }, ticks: { color: tickColor, font: { size: 11 } } },
                    y: {
                        beginAtZero: true,
                        grid: { color: gridColor },
                        border: { display: false },
                        ticks: { color: tickColor, font: { size: 10 }, callback: function (v) { return formatAmount(v); } }
                    }
                }
            }
        });
    }

    function loadAccountBenefit() {
        return MARIA.auth.ajax({
            url: "/api/admin/statistics/account-benefit",
            method: "GET",
            data: buildFilterParams()
        }).done(function (res) {
            var rows = (res.data || []).filter(function (r) { return r.accountCount > 0; });
            renderPieChart(
                "accountBenefit", "accountBenefitChart", "accountBenefitEmpty",
                rows.map(function (r) { return BENEFIT_LABEL[r.benefit] || r.benefit; }),
                rows.map(function (r) { return r.accountCount; }),
                function (ctx) { return ctx.label + ": " + ctx.parsed + "건"; }
            );
        });
    }

    function loadAgeInvestment() {
        return MARIA.auth.ajax({
            url: "/api/admin/statistics/age-investment",
            method: "GET",
            data: buildFilterParams()
        }).done(function (res) {
            var rows = (res.data || []).filter(function (r) { return r.purchaseAmount > 0; });
            renderPieChart(
                "ageInvestment", "ageInvestmentChart", "ageInvestmentEmpty",
                rows.map(function (r) { return r.ageGroup; }),
                rows.map(function (r) { return r.purchaseAmount; }),
                function (ctx) { return ctx.label + ": " + formatAmount(ctx.parsed); }
            );
        });
    }

    function loadProductPurchase() {
        return MARIA.auth.ajax({
            url: "/api/admin/statistics/product-purchase",
            method: "GET",
            data: buildFilterParams()
        }).done(function (res) {
            var rows = (res.data || []).filter(function (r) { return r.purchaseAmount > 0; });
            var top = rows.slice(0, PRODUCT_PIE_TOP_N);
            var rest = rows.slice(PRODUCT_PIE_TOP_N);
            var restSum = rest.reduce(function (sum, r) { return sum + r.purchaseAmount; }, 0);
            var labels = top.map(function (r) { return r.name; });
            var data = top.map(function (r) { return r.purchaseAmount; });
            if (restSum > 0) {
                labels.push("기타");
                data.push(restSum);
            }
            renderPieChart(
                "productPurchase", "productPurchaseChart", "productPurchaseEmpty",
                labels, data,
                function (ctx) { return ctx.label + ": " + formatAmount(ctx.parsed); }
            );
        });
    }

    function loadReliefRate() {
        return MARIA.auth.ajax({
            url: "/api/admin/statistics/relief-rate",
            method: "GET",
            data: buildFilterParams()
        }).done(function (res) {
            var rows = res.data || [];
            renderBarChart(
                "reliefRate", "reliefRateChart", "reliefRateEmpty",
                rows.map(function (r) { return r.periodLabel; }),
                rows.map(function (r) { return r.sellAmount; }),
                "매도금액",
                function (ctx) { return "매도금액: " + formatAmount(ctx.parsed.y); }
            );
        });
    }

    function loadFxExchange() {
        return MARIA.auth.ajax({
            url: "/api/admin/statistics/fx-exchange",
            method: "GET",
            data: buildFilterParams()
        }).done(function (res) {
            var rows = res.data || [];
            var labels = rows.map(function (r) {
                var d = new Date(r.statDate);
                return (d.getMonth() + 1) + "/" + d.getDate();
            });
            renderLineChart("fxExchange", "fxExchangeChart", "fxExchangeEmpty", labels, [
                { label: "가환전액", data: rows.map(function (r) { return r.provisionalAmount || 0; }) },
                { label: "확정환전액", data: rows.map(function (r) { return r.finalAmount || 0; }) }
            ]);
        });
    }

    function loadAll() {
        hideError();
        $.when(
            loadAccountBenefit(),
            loadAgeInvestment(),
            loadProductPurchase(),
            loadReliefRate(),
            loadFxExchange()
        ).fail(function (xhr) {
            if (xhr && xhr.status === 401) {
                return;
            }
            showError((xhr && xhr.responseJSON && xhr.responseJSON.message) || "통계 데이터를 불러오지 못했습니다.");
        });
    }

    $("#statFilterSearch").on("click", loadAll);

    $("#statFilterReset").on("click", function () {
        $("#statFilterKeyword").val("");
        $("#statFilterProductName").val("");
        $("#statFilterStartDate").val("");
        $("#statFilterEndDate").val("");
        loadAll();
    });

    $(document).on("maria:themeChange", function () {
        refreshThemeColors();
        loadAll();
    });

    loadAll();
});
