$(function () {
    var BENEFIT_LABEL = {
        possible: "가능",
        reduced: "일부감액",
        impossible: "배제"
    };

    var BASIS_LABEL = {
        FINAL_REPORT: "확정신고",
        EARLY_WITHDRAWAL_CLAWBACK: "조기인출추징"
    };

    var BATCH_STATUS_LABEL = {
        COMPLETED: "완료",
        FAILED: "실패",
        STARTED: "실행중",
        STARTING: "실행중",
        STOPPED: "중단",
        UNKNOWN: "알수없음"
    };

    var KRW_FORMATTER = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });
    var DATETIME_FORMATTER = new Intl.DateTimeFormat("ko-KR", {
        year: "numeric", month: "2-digit", day: "2-digit",
        hour: "2-digit", minute: "2-digit"
    });
    var DATE_FORMATTER = new Intl.DateTimeFormat("ko-KR", {
        year: "numeric", month: "2-digit", day: "2-digit"
    });

    var accountMap = {};
    var snapshotByAccountId = {};
    var allSnapshots = [];
    var currentPage = 1;
    var PAGE_SIZE = 10;
    var searchKeyword = "";
    var benefitFilter = "";
    var selectedAccountId = null;
    var kpiFilter = null; // null | "taxable" | "reducedOrExcluded" - 상단 KPI 카드 클릭으로 설정됨
    var sortMode = "finalTaxDesc";
    var allBatchHistory = [];
    var batchHistoryPage = 1;
    var BATCH_HISTORY_PAGE_SIZE = 10;
    var businessToday = null; // "YYYY-MM-DD" - 오늘 이전 스냅샷은 배치가 안 돈 오래된 값

    function escapeHtml(value) {
        return $("<div>").text(value == null ? "" : value).html();
    }

    function formatAmount(amount) {
        return "₩" + KRW_FORMATTER.format(amount || 0);
    }

    // 손익처럼 부호 자체가 의미 있는 값에서만 쓴다(양수=빨강/음수=파랑, 증권사 관례).
    // 최종세액/최종공제액처럼 구조적으로 항상 0 이상인 값까지 칠하면 전부 빨개져서
    // 오히려 신호가 죽으므로 일부러 안 씌운다.
    function formatSignedAmount(amount) {
        var value = Number(amount || 0);
        var cls = value > 0 ? "tax-amount-positive" : (value < 0 ? "tax-amount-negative" : "");
        return '<span class="' + cls + '">' + formatAmount(value) + '</span>';
    }

    function formatDateTime(isoString) {
        return isoString ? DATETIME_FORMATTER.format(new Date(isoString)) : "-";
    }

    function formatDate(isoString) {
        return isoString ? DATE_FORMATTER.format(new Date(isoString)) : "-";
    }

    function formatRatio(ratio) {
        return ratio == null ? "-" : (Number(ratio) * 100).toFixed(1) + "%";
    }

    function dateOnly(isoString) {
        return isoString ? isoString.slice(0, 10) : null;
    }

    function isStaleSnapshot(snap) {
        return !!businessToday && dateOnly(snap.calculatedAt) !== businessToday;
    }

    function snapshotStatus(snap) {
        if (!businessToday) {
            return { label: "확인 중", className: "tax-row-pending" };
        }
        return isStaleSnapshot(snap)
            ? { label: "오래된 값", className: "tax-row-flag" }
            : { label: "최신값", className: "tax-row-current" };
    }

    function showError(message) {
        $("#taxLoading").hide();
        $("#taxError").text(message).show();
    }

    function canTriggerBatch() {
        var admin = MARIA.auth.currentAdmin();
        return !!admin && (admin.role === "ADMIN" || admin.role === "SETTLEMENT");
    }

    function totalPagesOf(list) {
        return Math.max(1, Math.ceil(list.length / PAGE_SIZE));
    }

    function matchesSearch(snap) {
        if (!searchKeyword) {
            return true;
        }
        var account = accountMap[snap.accountId] || {};
        var haystack = ((account.accountNo || "") + " " + (account.customerName || "")).toLowerCase();
        return haystack.indexOf(searchKeyword) !== -1;
    }

    function matchesBenefitFilter(snap) {
        if (!benefitFilter) {
            return true;
        }
        var benefit = ((accountMap[snap.accountId] || {}).benefit || "").toLowerCase();
        return benefit === benefitFilter;
    }

    function matchesKpiFilter(snap) {
        if (kpiFilter === "taxable") {
            return Number(snap.finalTax || 0) > 0;
        }
        if (kpiFilter === "reducedOrExcluded") {
            var benefit = ((accountMap[snap.accountId] || {}).benefit || "").toLowerCase();
            return benefit === "reduced" || benefit === "impossible";
        }
        return true;
    }

    function filteredSnapshots() {
        var visible = allSnapshots.filter(matchesSearch).filter(matchesBenefitFilter).filter(matchesKpiFilter);
        if (sortMode === "adjustRatioAsc") {
            visible = visible.slice().sort(function (a, b) {
                return Number(a.adjustRatio || 0) - Number(b.adjustRatio || 0);
            });
        } else {
            visible = visible.slice().sort(function (a, b) {
                return Number(b.finalTax || 0) - Number(a.finalTax || 0);
            });
        }
        return visible;
    }

    function renderKpi() {
        var taxable = allSnapshots.filter(function (s) { return (s.finalTax || 0) > 0; });
        var reduced = allSnapshots.filter(function (s) {
            var benefit = ((accountMap[s.accountId] || {}).benefit || "").toLowerCase();
            return benefit === "reduced" || benefit === "impossible";
        });
        var taxSum = taxable.reduce(function (sum, s) { return sum + Number(s.finalTax || 0); }, 0);

        $("#kpiTotal").text(allSnapshots.length + "건");
        $("#kpiTaxable").text(taxable.length + "건");
        $("#kpiReduced").text(reduced.length + "건");
        $("#kpiTaxSum").text(formatAmount(taxSum));
        renderKpiActiveState();
    }

    function renderKpiActiveState() {
        $("#kpiCardAll").toggleClass("is-active", kpiFilter === null);
        $("#kpiCardTaxable").toggleClass("is-active", kpiFilter === "taxable");
        $("#kpiCardReduced").toggleClass("is-active", kpiFilter === "reducedOrExcluded");
    }

    function renderSnapshots(snapshots) {
        allSnapshots = snapshots || [];
        snapshotByAccountId = {};
        allSnapshots.forEach(function (s) { snapshotByAccountId[s.accountId] = s; });
        renderKpi();
        currentPage = Math.min(currentPage, totalPagesOf(filteredSnapshots()));
        renderPage();
    }

    function renderPage() {
        var $body = $("#taxSnapshotBody").empty();
        var visible = filteredSnapshots();
        $("#taxSnapshotCount").text(visible.length + "건" + (visible.length !== allSnapshots.length ? " (전체 " + allSnapshots.length + "건 중)" : ""));

        if (visible.length === 0) {
            $body.append('<tr><td colspan="9" class="tax-empty">' + (searchKeyword || benefitFilter ? "조건에 맞는 계좌가 없습니다." : "세액 계산 결과가 없습니다.") + '</td></tr>');
            $("#taxPagination").empty();
            return;
        }

        var totalPages = totalPagesOf(visible);
        var startIndex = (currentPage - 1) * PAGE_SIZE;
        var pageSnapshots = visible.slice(startIndex, startIndex + PAGE_SIZE);

        pageSnapshots.forEach(function (snap) {
            var account = accountMap[snap.accountId] || {};
            var benefitKey = (account.benefit || "").toLowerCase();
            var calculationStatus = snapshotStatus(snap);
            var selectedClass = Number(snap.accountId) === Number(selectedAccountId) ? " is-selected" : "";
            var row =
                "<tr class=\"tax-snapshot-row" + selectedClass + "\" data-account-id=\"" + snap.accountId + "\">" +
                "<td><div class=\"account-no\">" + MARIA.fmt.hyphenateAccountNo(account.accountNo) + "</div>" +
                "<div class=\"account-name\">" + escapeHtml(account.customerName || "") + "</div></td>" +
                "<td><span class=\"status-badge " + escapeHtml(benefitKey) + "\">" +
                (BENEFIT_LABEL[benefitKey] || "-") + "</span></td>" +
                "<td>" + formatAmount(snap.weightedSell) + "</td>" +
                "<td>" + formatAmount(snap.weightedGain) + "</td>" +
                "<td>" + formatRatio(snap.adjustRatio) + "</td>" +
                "<td>" + formatAmount(snap.finalDeduction) + "</td>" +
                "<td>" + formatAmount(snap.finalTax) + "</td>" +
                "<td>" + formatDateTime(snap.calculatedAt) + "</td>" +
                "<td><span class=\"" + calculationStatus.className + "\">" + calculationStatus.label + "</span></td>" +
                "</tr>";
            $body.append(row);
        });

        renderPagination(totalPages);
    }

    // 입고 관리 페이지네이션과 동일한 방식(0-based, 10개씩 블록). 세액 목록/배치 이력 둘 다 이걸 공유한다.
    function renderPageButtons(containerId, currentPage1Based, totalPages, onPageClick) {
        var $pagination = $(containerId).empty();
        if (totalPages <= 1) {
            return;
        }

        var current = currentPage1Based - 1;
        var BLOCK_SIZE = 10;
        var blockStart = Math.floor(current / BLOCK_SIZE) * BLOCK_SIZE;
        var blockEnd = Math.min(totalPages - 1, blockStart + BLOCK_SIZE - 1);

        function addButton(label, targetPage, isDisabled, isActive) {
            var classes = "page-btn" + (isActive ? " active" : "");
            var $btn = $('<button type="button" class="' + classes + '">' + label + "</button>");
            $btn.prop("disabled", isDisabled || isActive);
            if (!isDisabled && !isActive) {
                $btn.on("click", function () {
                    onPageClick(targetPage + 1);
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

    function renderPagination(totalPages) {
        renderPageButtons("#taxPagination", currentPage, totalPages, function (targetPage) {
            currentPage = targetPage;
            renderPage();
        });
    }

    function renderBreakdown(periodBreakdown) {
        var $body = $("#detailBreakdownBody").empty();
        if (!periodBreakdown || periodBreakdown.length === 0) {
            $body.append('<tr><td colspan="5" class="tax-empty">근거 데이터가 없습니다.</td></tr>');
            return;
        }
        periodBreakdown.forEach(function (period) {
            var row =
                "<tr>" +
                "<td>" + formatDate(period.validFrom) + " ~ " + formatDate(period.validTo) + "</td>" +
                "<td>" + formatRatio(period.weight) + "</td>" +
                "<td>" + formatAmount(period.sellAmount) + "</td>" +
                "<td>" + formatSignedAmount(period.gainAmount) + "</td>" +
                "<td>" + formatSignedAmount(period.externalNetBuyAmount) + "</td>" +
                "</tr>";
            $body.append(row);
        });
    }

    function renderLotDetails(sellLotDetails) {
        var $body = $("#detailLotBody").empty();
        if (!sellLotDetails || sellLotDetails.length === 0) {
            $body.append('<tr><td colspan="4" class="tax-empty">매도 내역이 없습니다.</td></tr>');
            return;
        }
        sellLotDetails.forEach(function (lot) {
            var row =
                "<tr>" +
                "<td>" + escapeHtml(lot.productLabel || "-") + "</td>" +
                "<td>" + formatDate(lot.sellAt) + "</td>" +
                "<td>" + formatAmount(lot.sellAmount) + "</td>" +
                "<td>" + formatSignedAmount(lot.gainAmount) + "</td>" +
                "</tr>";
            $body.append(row);
        });
    }

    function renderExternalTradeDetails(externalTradeDetails) {
        var $body = $("#detailExternalTradeBody").empty();
        if (!externalTradeDetails || externalTradeDetails.length === 0) {
            $body.append('<tr><td colspan="3" class="tax-empty">외부 순매수 내역이 없습니다.</td></tr>');
            return;
        }
        externalTradeDetails.forEach(function (trade) {
            var row =
                "<tr>" +
                "<td>" + escapeHtml(trade.productLabel || "-") + "</td>" +
                "<td>" + formatDate(trade.tradeDate) + "</td>" +
                "<td>" + formatSignedAmount(trade.netBuyAmount) + "</td>" +
                "</tr>";
            $body.append(row);
        });
    }

    function openDetailPanel() {
        $("#taxDetail").prop("hidden", false);
        $("#detailPanelBackdrop").prop("hidden", false);
        // hidden 해제와 is-open 추가를 같은 틱에 하면 브라우저가 시작 상태(hidden)를 그릴 틈이 없어
        // transition이 통째로 씹힌다 — 한 프레임 뒤로 미뤄야 슬라이드/페이드가 실제로 보인다.
        requestAnimationFrame(function () {
            $("#taxDetail").addClass("is-open");
            $("#detailPanelBackdrop").addClass("is-open");
        });
    }

    function closeDetailPanel() {
        $("#taxDetail").removeClass("is-open");
        $("#detailPanelBackdrop").removeClass("is-open");
        setTimeout(function () {
            $("#taxDetail").prop("hidden", true);
            $("#detailPanelBackdrop").prop("hidden", true);
        }, 180);
    }

    function openDetail(accountId) {
        var account = accountMap[accountId] || {};
        $("#detailAccountNo").text("불러오는 중...");
        openDetailPanel();

        MARIA.auth.ajax({
            url: "/api/admin/tax/preview/" + accountId,
            method: "GET"
        }).done(function (res) {
            var data = res.data;
            var result = data.taxCalculationResultDTO;
            $("#detailAccountNo").html(MARIA.fmt.accountNoHtml(account.accountNo) + " · " + escapeHtml(account.customerName || ""));
            $("#detailDescription").text("기준시각 " + ($("#clockValue").text() || "-"));
            $("#detailWeightedSell").text(formatAmount(result.weightedSell));
            $("#detailWeightedGain").html(formatSignedAmount(result.weightedGain));
            $("#detailExternalAmount").text(formatAmount(result.weightedExternalAmount));
            $("#detailAdjustRatio").text(formatRatio(result.adjustRatio));
            $("#detailFinalDeduction").text(formatAmount(result.finalDeduction));
            $("#detailOriginalGain").html(formatSignedAmount(result.originalGainAmount));
            $("#detailFinalTax").text(formatAmount(result.finalTax));
            renderBreakdown(result.periodBreakdown);
            renderLotDetails(result.sellLotDetails);
            renderExternalTradeDetails(result.externalTradeDetails);

            var snapshot = snapshotByAccountId[accountId];
            var isStale = snapshot && Number(snapshot.finalTax || 0) !== Number(result.finalTax || 0);
            $("#detailStaleBadge").toggle(!!isStale);

            if (data.benefitChangeReason) {
                $("#detailBenefitReason")
                    .text("세제혜택 변경 사유 (" + formatDateTime(data.benefitChangedAt) + "): " + data.benefitChangeReason)
                    .show();
            } else {
                $("#detailBenefitReason").hide();
            }

            var saved = data.latestSavedCalculation;
            if (saved) {
                $("#detailSavedBadge")
                    .text((BASIS_LABEL[saved.basisType] || saved.basisType) + " 저장됨 · " + formatDateTime(saved.calculatedAt))
                    .show();
            } else {
                $("#detailSavedBadge").hide();
            }
        }).fail(function (xhr) {
            if (xhr.status === 401) return;
            var message = (xhr.responseJSON && xhr.responseJSON.message) || "세액 미리보기를 불러오지 못했습니다.";
            MARIA.ui.showError(message);
        });
    }

    function batchStatusClass(status) {
        var statusKey = (status || "").toLowerCase();
        return statusKey === "completed" ? "possible" : (statusKey === "failed" ? "impossible" : "reduced");
    }

    function renderBatchHistory(history) {
        allBatchHistory = history || [];
        batchHistoryPage = Math.min(batchHistoryPage, Math.max(1, Math.ceil(allBatchHistory.length / BATCH_HISTORY_PAGE_SIZE)));

        var latest = allBatchHistory[0];
        if (latest) {
            $("#batchHistoryLatest").text(formatDateTime(latest.startTime) + " · 처리 " + latest.writeCount + "/" + latest.readCount + (latest.skipCount > 0 ? " · 스킵 " + latest.skipCount : ""));
            $("#batchHistoryLatestBadge").attr("class", "status-badge " + batchStatusClass(latest.status)).text(BATCH_STATUS_LABEL[latest.status] || latest.status);
        } else {
            $("#batchHistoryLatest").text("실행 이력이 없습니다.");
            $("#batchHistoryLatestBadge").attr("class", "status-badge").text("-");
        }

        renderBatchHistoryPage();
    }

    function renderBatchHistoryPage() {
        var $body = $("#batchHistoryBody").empty();
        $("#batchHistoryCount").text(allBatchHistory.length + "건");
        if (allBatchHistory.length === 0) {
            $body.append('<tr><td colspan="6" class="tax-empty">실행 이력이 없습니다.</td></tr>');
            $("#batchHistoryPagination").empty();
            return;
        }

        var totalPages = Math.max(1, Math.ceil(allBatchHistory.length / BATCH_HISTORY_PAGE_SIZE));
        var startIndex = (batchHistoryPage - 1) * BATCH_HISTORY_PAGE_SIZE;
        var pageItems = allBatchHistory.slice(startIndex, startIndex + BATCH_HISTORY_PAGE_SIZE);

        pageItems.forEach(function (item) {
            var statusClass = batchStatusClass(item.status);
            var row =
                "<tr>" +
                "<td>" + escapeHtml(item.runId || "-") + "</td>" +
                "<td>" + formatDateTime(item.startTime) + "</td>" +
                "<td>" + formatDateTime(item.endTime) + "</td>" +
                "<td><span class=\"status-badge " + statusClass + "\">" + (BATCH_STATUS_LABEL[item.status] || item.status) + "</span></td>" +
                "<td>" + item.writeCount + " / " + item.readCount + "</td>" +
                "<td>" + (item.skipCount > 0 ? "<span class=\"tax-amount-positive\">" + item.skipCount + "</span>" : item.skipCount) + "</td>" +
                "</tr>";
            $body.append(row);
        });

        renderPageButtons("#batchHistoryPagination", batchHistoryPage, totalPages, function (targetPage) {
            batchHistoryPage = targetPage;
            renderBatchHistoryPage();
        });
    }

    function loadBatchHistory() {
        MARIA.auth.ajax({ url: "/api/admin/tax/snapshots/jobs", method: "GET" })
            .done(function (res) {
                renderBatchHistory(res.data);
            })
            .fail(function (xhr) {
                if (xhr.status === 401) return;
                $("#batchHistoryBody").html('<tr><td colspan="6" class="tax-empty">배치 이력을 불러오지 못했습니다.</td></tr>');
            });
    }

    function loadBusinessToday() {
        return MARIA.auth.ajax({ url: "/api/admin/system-clock", method: "GET" })
            .done(function (res) {
                businessToday = dateOnly(res.data);
                if (allSnapshots.length) {
                    renderPage();
                }
            });
    }

    function loadTax() {
        loadBusinessToday();
        MARIA.auth.ajax({ url: "/api/admin/account/list", method: "GET" })
            .done(function (accountRes) {
                var accounts = accountRes.data || [];
                var accountIds = [];
                accounts.forEach(function (account) {
                    accountMap[account.accountId] = account;
                    accountIds.push(account.accountId);
                });

                if (accountIds.length === 0) {
                    renderSnapshots([]);
                    $("#taxLoading").hide();
                    $("#taxBody").css("display", "flex");
                    return;
                }

                // ponytail: URL 쿼리스트링 길이 한도(414/400) 때문에 청크 분할. accountIds가
                // 계속 커지면 백엔드에 POST 방식 조회 API를 새로 만드는 쪽으로 바꿀 것.
                var CHUNK_SIZE = 200;
                var chunks = [];
                for (var i = 0; i < accountIds.length; i += CHUNK_SIZE) {
                    chunks.push(accountIds.slice(i, i + CHUNK_SIZE));
                }

                $.when.apply($, chunks.map(function (chunk) {
                    return MARIA.auth.ajax({
                        url: "/api/admin/tax/snapshots",
                        method: "GET",
                        data: { accountIds: chunk },
                        traditional: true
                    });
                })).done(function () {
                    var results = chunks.length === 1 ? [arguments] : arguments;
                    var snapshots = [];
                    $.each(results, function (_, result) {
                        snapshots = snapshots.concat(result[0].data || []);
                    });
                    renderSnapshots(snapshots);
                    $("#taxLoading").hide();
                    $("#taxBody").css("display", "flex");
                }).fail(function (xhr) {
                    if (xhr && xhr.status === 401) return;
                    showError((xhr && xhr.responseJSON && xhr.responseJSON.message) || "세액 계산 목록을 불러오지 못했습니다.");
                });
            })
            .fail(function (xhr) {
                if (xhr.status === 401) return;
                showError((xhr.responseJSON && xhr.responseJSON.message) || "계좌 목록을 불러오지 못했습니다.");
            });
    }

    $("#taxSnapshotBody").on("click", "tr[data-account-id]", function () {
        selectedAccountId = Number($(this).data("account-id"));
        renderPage();
        openDetail($(this).data("account-id"));
    });


    $("#detailCloseBtn, #detailPanelBackdrop").on("click", closeDetailPanel);

    $("#batchHistoryToggle").on("click", function () {
        var isOpen = $("#batchHistoryDetail").is(":visible");
        $("#batchHistoryDetail").toggle(!isOpen);
        $(this).text(isOpen ? "전체 이력 보기" : "접기");
    });

    $("#taxSearchInput").on("input", function () {
        searchKeyword = $(this).val().trim().toLowerCase();
        currentPage = 1;
        renderPage();
    });

    $("#taxBenefitFilter").on("change", function () {
        benefitFilter = $(this).val();
        currentPage = 1;
        renderPage();
    });

    $("#taxSortSelect").on("change", function () {
        sortMode = $(this).val();
        currentPage = 1;
        renderPage();
    });

    function setKpiFilter(next) {
        kpiFilter = next;
        benefitFilter = "";
        $("#taxBenefitFilter").val("");
        searchKeyword = "";
        $("#taxSearchInput").val("");
        currentPage = 1;
        renderKpiActiveState();
        renderPage();
    }

    $("#kpiCardAll").on("click", function () { setKpiFilter(null); });
    $("#kpiCardTaxable").on("click", function () { setKpiFilter("taxable"); });
    $("#kpiCardReduced").on("click", function () { setKpiFilter("reducedOrExcluded"); });

    $("#batchTriggerGroup").toggle(canTriggerBatch());
    $("#triggerBatch").on("click", function () {
        if (!canTriggerBatch()) return;
        MARIA.auth.ajax({ url: "/api/admin/tax/snapshots/jobs", method: "POST" })
            .done(function (res) {
                $("#batchTriggerResult").text("요청됨 · runId " + res.data.runId + " · " + res.data.status);
                setTimeout(loadBatchHistory, 1500);
            })
            .fail(function (xhr) {
                if (xhr.status === 401) return;
                MARIA.ui.showError((xhr.responseJSON && xhr.responseJSON.message) || "배치 실행 요청에 실패했습니다.");
            });
    });

    var deepLinkAccountNo = MARIA.deeplink.accountNoFromUrl();
    if (deepLinkAccountNo) {
        $("#taxSearchInput").val(deepLinkAccountNo);
        searchKeyword = deepLinkAccountNo.toLowerCase();
        currentPage = 1;
    }

    loadTax();
    loadBatchHistory();
});
