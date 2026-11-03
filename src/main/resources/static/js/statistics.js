$(function () {
    var KRW_FORMATTER = new Intl.NumberFormat("ko-KR");
    var PRODUCT_PIE_TOP_N = 5;

    // 차트 색은 tokens.css 의 --adm- 변수 (라이트/다크는 CSS 가 바꿈)
    var STATUS_COLOR_VARS = ["--adm-chart-1", "--adm-chart-4", "--adm-chart-5", "--adm-chart-gray"]; // 원본 상태 도넛 순서
    var STOCK_COLOR_VARS = ["--adm-chart-1", "--adm-chart-2", "--adm-chart-3", "--adm-chart-4", "--adm-chart-5"]; // 원본 종목 막대 순서
    var STOCK_OTHER_VAR = "--adm-bd2"; // "기타" 막대
    var benefitLabel = MARIA.status.benefitLabel; // 공통 매핑 (js/admin/status-labels.js)

    function formatAmount(amount) {
        return "₩" + KRW_FORMATTER.format(amount || 0);
    }

    function showError(message) {
        $("#dashStatError").text(message).prop("hidden", false);
    }

    function hideError() {
        $("#dashStatError").prop("hidden", true);
    }

    function setEmpty(key, isEmpty) {
        // 데이터가 없으면 카드 안 차트·표를 감추고 안내 문구를 보인다
        $("#" + key + "Empty").prop("hidden", !isEmpty);
        $("#" + key + "Wrap, #" + key + "Chart, #" + key + "Names, #" + key + "TableWrap").toggle(!isEmpty);
    }

    function percent(part, total) {
        return total > 0 ? part / total * 100 : 0;
    }

    function tableRow(cells) {
        var $tr = $("<tr>");
        cells.forEach(function (cell) {
            $("<td>").toggleClass("is-r", !!cell.right).append($("<span>").text(cell.text)).appendTo($tr);
        });
        return $tr;
    }

    // 막대는 만든 직후 0 에서 시작해 두 프레임 뒤 목표 값으로 커진다 (원본의 transition)
    function growBars($bars, prop) {
        window.requestAnimationFrame(function () {
            window.requestAnimationFrame(function () {
                $bars.each(function () { this.style.transform = prop + "(" + $(this).data("scale") + ")"; });
            });
        });
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

    // 세제혜택 상태 도넛: conic-gradient (원본과 같은 방식)
    function renderBenefit(rows) {
        var total = rows.reduce(function (sum, r) { return sum + r.accountCount; }, 0);
        setEmpty("accountBenefit", !rows.length);
        $("#statBenefitTotal").text(KRW_FORMATTER.format(total));
        $("#accountBenefitTable").empty();
        if (!rows.length) {
            $("#statBenefitRatio").text("-");
            return;
        }
        var acc = 0;
        var stops = rows.map(function (r, i) {
            var from = acc / total * 360;
            acc += r.accountCount;
            return "var(" + STATUS_COLOR_VARS[i % STATUS_COLOR_VARS.length] + ") " + from + "deg " + (acc / total * 360) + "deg";
        });
        $("#accountBenefitChart").css("background", "conic-gradient(" + stops.join(",") + ")");
        var applied = rows.filter(function (r) { return r.benefit === "POSSIBLE"; })[0];
        $("#statBenefitRatio").text(applied ? Math.round(percent(applied.accountCount, total)) + "%" : "-");
        $("#accountBenefitTable").append(rows.map(function (r) {
            return tableRow([
                { text: benefitLabel(r.benefit) },
                { text: KRW_FORMATTER.format(r.accountCount), right: true },
                { text: percent(r.accountCount, total).toFixed(1) + "%", right: true }
            ]);
        }));
    }

    // 나이대별 투자 비중: 세로 막대 (가장 큰 값만 gold)
    function renderAgeInvestment(rows) {
        var total = rows.reduce(function (sum, r) { return sum + r.purchaseAmount; }, 0);
        var max = Math.max.apply(null, rows.map(function (r) { return r.purchaseAmount; }));
        setEmpty("ageInvestment", !rows.length);
        var $bars = $("#ageInvestmentChart").empty();
        $("#ageInvestmentNames").empty();
        $("#ageInvestmentTable").empty();
        if (!rows.length) {
            return;
        }
        rows.forEach(function (r) {
            var pct = Math.round(percent(r.purchaseAmount, total)) + "%";
            var $fill = $("<div>").addClass("adm-age-fill").toggleClass("is-top", r.purchaseAmount === max)
                .data("scale", r.purchaseAmount / max);
            $("<div>").addClass("adm-age-col").attr("title", r.ageGroup + ": " + formatAmount(r.purchaseAmount))
                .append($("<span>").addClass("adm-age-val").text(pct))
                .append($("<div>").addClass("adm-age-track").append($fill))
                .appendTo($bars);
            $("<span>").text(r.ageGroup).appendTo("#ageInvestmentNames");
            $("#ageInvestmentTable").append(tableRow([
                { text: r.ageGroup },
                { text: pct, right: true },
                { text: (r.purchaseAmount / 100000000).toFixed(1), right: true }
            ]));
        });
        growBars($bars.find(".adm-age-fill"), "scaleY");
    }

    // 종목별 매수 비중: 가로 막대 (상위 N개 + 기타)
    function renderProductPurchase(labels, data) {
        var total = data.reduce(function (sum, v) { return sum + v; }, 0);
        var max = Math.max.apply(null, data);
        setEmpty("productPurchase", !labels.length);
        var $bars = $("#productPurchaseChart").empty();
        $("#productPurchaseTable").empty();
        if (!labels.length) {
            return;
        }
        labels.forEach(function (label, i) {
            var pct = Math.round(percent(data[i], total)) + "%";
            var colorVar = label === "기타" ? STOCK_OTHER_VAR : STOCK_COLOR_VARS[Math.min(i, STOCK_COLOR_VARS.length - 1)];
            var $fill = $("<div>").addClass("adm-stock-fill").css("background", "var(" + colorVar + ")")
                .data("scale", data[i] / max);
            $("<div>").addClass("adm-stock-row").attr("title", label + ": " + formatAmount(data[i]))
                .append($("<span>").addClass("adm-stock-name").text(label))
                .append($("<div>").addClass("adm-stock-track").append($fill))
                .append($("<span>").addClass("adm-stock-val").text(pct))
                .appendTo($bars);
            $("#productPurchaseTable").append(tableRow([{ text: label }, { text: pct, right: true }]));
        });
        growBars($bars.find(".adm-stock-fill"), "scaleX");
    }

    function loadAccountBenefit() {
        return MARIA.auth.ajax({
            url: "/api/admin/statistics/account-benefit",
            method: "GET",
            data: buildFilterParams()
        }).done(function (res) {
            var rows = (res.data || []).filter(function (r) { return r.accountCount > 0; });
            renderBenefit(rows);
            var applied = rows.filter(function (r) { return r.benefit === "POSSIBLE"; })[0];
            $("#statSummaryApplied").text(applied ? KRW_FORMATTER.format(applied.accountCount) : (rows.length ? "0" : "-"));
        });
    }

    function loadAgeInvestment() {
        return MARIA.auth.ajax({
            url: "/api/admin/statistics/age-investment",
            method: "GET",
            data: buildFilterParams()
        }).done(function (res) {
            var rows = (res.data || []).filter(function (r) { return r.purchaseAmount > 0; });
            renderAgeInvestment(rows);
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
            renderProductPurchase(labels, data);
        });
    }

    function loadAll() {
        hideError();
        $(".statistics").addClass("is-loading");
        $.when(
            loadAccountBenefit(),
            loadAgeInvestment(),
            loadProductPurchase()
        ).fail(function (xhr) {
            if (xhr && xhr.status === 401) {
                return;
            }
            showError((xhr && xhr.responseJSON && xhr.responseJSON.message) || "통계 데이터를 불러오지 못했습니다.");
        }).always(function () {
            $(".statistics").removeClass("is-loading");
        });
    }

    // 화면 크기 ÷ 글자 배율에 따라 카드 간격·최소 높이를 바꾼다 (원본과 같은 기준: 높이 800, 너비 900)
    function applyViewport() {
        var zoom = parseFloat($("#admShell").css("zoom")) || 1;
        $(".statistics").toggleClass("is-short", window.innerHeight / zoom < 800)
            .toggleClass("is-narrow", window.innerWidth / zoom < 900);
    }

    applyViewport();
    $(window).on("resize", applyViewport);
    if (window.MutationObserver && $("#admShell").length) {
        // 글자 크기를 바꾸면 layout.js 가 #admShell 의 --font-scale 을 바꾼다
        new MutationObserver(applyViewport).observe($("#admShell")[0], { attributes: true, attributeFilter: ["style"] });
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
        loadAll();
    });

    loadAll();
});
