$(function () {
    var BASIS_LABEL = {
        FINAL_REPORT: "확정신고",
        EARLY_WITHDRAWAL_CLAWBACK: "조기인출추징"
    };
    var BATCH_STATUS_LABEL = {
        COMPLETED: "완료",
        FAILED: "실패",
        STARTED: "실행 중",
        STARTING: "실행 중",
        STOPPED: "중단",
        UNKNOWN: "알 수 없음"
    };
    var BATCH_STATUS_TONE = {
        COMPLETED: "ok",
        FAILED: "fail",
        STARTED: "info",
        STARTING: "info",
        STOPPED: "off"
    };
    var AMOUNT_FORMATTER = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });

    var accountMap = {};
    var allSnapshots = [];
    var sortSpec = null; // 머리글로 고른 정렬 (받아 온 목록 전체를 정렬한다)
    var snapshotByAccountId = {};
    var confirmedByAccountId = {};
    var filteredSnapshots = [];
    var currentPage = 1;
    var pageSize = 20;
    var searchKeyword = "";
    var statusFilter = "";
    var selectedAccountId = null;
    var businessToday = null;
    var allBatchHistory = [];
    var batchHistoryPage = 1;
    var batchHistoryPageSize = 10;
    var actionSummary = null;
    var pendingAction = null;
    var detailCloseTimer = null;
    var previewRequestId = 0;

    function escapeHtml(value) {
        return $("<div>").text(value == null ? "" : value).html();
    }

    function formatAmount(value) {
        if (value === null || value === undefined || value === "") {
            return "-";
        }
        return AMOUNT_FORMATTER.format(Number(value));
    }

    function formatSignedAmount(value) {
        if (value === null || value === undefined || value === "") {
            return "-";
        }
        var amount = Number(value);
        var tone = amount > 0 ? "is-positive" : (amount < 0 ? "is-negative" : "");
        return '<span class="tax-signed-amount ' + tone + '">' + formatAmount(amount) + '</span>';
    }

    function formatRatio(value) {
        return value === null || value === undefined || value === ""
            ? "-"
            : Number(value).toFixed(4);
    }

    function formatWeight(value) {
        if (value === null || value === undefined || value === "") {
            return "-";
        }
        return (Number(value) * 100).toLocaleString("ko-KR", { maximumFractionDigits: 2 }) + "%";
    }

    function formatDate(value) {
        if (!value) {
            return "-";
        }
        return String(value).slice(0, 10);
    }

    function formatDateTime(value) {
        if (!value) {
            return "-";
        }
        var text = String(value).replace("T", " ");
        return text.length >= 16 ? text.slice(0, 16) : text;
    }

    function formatPeriodRange(period) {
        var from = formatDate(period.validFrom);
        var to = formatDate(period.validTo);
        if (from === "-" || to === "-") {
            return from + " ~ " + to;
        }
        var fromYear = from.slice(0, 4);
        var toYear = to.slice(0, 4);
        var fromMonth = Number(from.slice(5, 7));
        var toMonth = Number(to.slice(5, 7));
        if (fromYear === toYear) {
            return (fromMonth === toMonth ? fromMonth : fromMonth + "~" + toMonth) + "월";
        }
        return fromYear + "." + fromMonth + "월 ~ " + toYear + "." + toMonth + "월";
    }

    function dateOnly(value) {
        return value ? String(value).slice(0, 10) : null;
    }

    function accountFor(accountId) {
        return accountMap[accountId] || {};
    }

    function isConfirmed(accountId) {
        return confirmedByAccountId[accountId] === true;
    }

    function calculationStatus(accountId) {
        return isConfirmed(accountId) ? "CONFIRMED" : "COMPLETED";
    }

    function statusChip(status) {
        var confirmed = status === "CONFIRMED";
        var label = confirmed ? "확정" : "완료";
        var title = confirmed ? "" : "확정 여부는 상세 조회에서 확인합니다.";
        return '<span class="adm-chip adm-chip-' + (confirmed ? "ok" : "info") +
            ' tax-calculation-chip tax-state-inferred" title="' + title + '">' + label + "</span>";
    }

    function benefitChip(benefit) {
        return '<span class="adm-chip adm-chip-' + MARIA.status.benefitTone(benefit) +
            ' tax-benefit-chip">' + escapeHtml(MARIA.status.benefitLabel(benefit)) + "</span>";
    }

    function matchesSearch(snapshot) {
        if (!searchKeyword) {
            return true;
        }
        var account = accountFor(snapshot.accountId);
        var text = ((account.accountNo || "") + " " + (account.customerName || "")).toLowerCase();
        return text.indexOf(searchKeyword) !== -1;
    }

    function getFilteredSnapshots() {
        var rows = allSnapshots.filter(function (snapshot) {
            return matchesSearch(snapshot) &&
                (!statusFilter || calculationStatus(snapshot.accountId) === statusFilter);
        });
        if (!sortSpec) {
            return rows;
        }
        return MARIA.tableSort.sortList(rows, function (snapshot) {
            var account = accountFor(snapshot.accountId);
            switch (sortSpec.key) {
                case "accountNo": return account.accountNo;
                case "customerName": return account.customerName;
                case "benefit": return account.benefit;
                case "status": return calculationStatus(snapshot.accountId);
                case "action": return snapshot.needsClawback ? 2 : (snapshot.needsFinalReport ? 1 : 0);
                default: return snapshot[sortSpec.key];
            }
        }, sortSpec.dir);
    }

    function renderSummary() {
        var needs = allSnapshots.filter(function (snapshot) {
            return snapshot.needsFinalReport || snapshot.needsClawback;
        }).length;
        var deduction = allSnapshots.reduce(function (sum, snapshot) {
            return sum + Number(snapshot.finalDeduction || 0);
        }, 0);

        $("#kpiTotal").text(allSnapshots.length.toLocaleString("ko-KR"));
        $("#kpiUnconfirmed").text(needs.toLocaleString("ko-KR"));
        $("#kpiTaxSum").text(formatAmount(deduction));
    }

    function stateRow(colspan, title, description, button) {
        return '<tr class="is-state"><td colspan="' + colspan + '">' +
            '<div class="adm-state-empty"><div class="adm-state-empty-title">' + escapeHtml(title) + "</div>" +
            (description ? '<div class="adm-state-empty-desc">' + escapeHtml(description) + "</div>" : "") +
            (button || "") + "</div></td></tr>";
    }

    function retryButton(id, label) {
        return '<button class="adm-btn adm-btn-sm" type="button" id="' + id + '">' +
            escapeHtml(label) + "</button>";
    }

    function renderPageButtons(containerId, currentPage1Based, totalPages, onPageClick) {
        var $pagination = $(containerId).empty();
        if (totalPages <= 1) {
            return;
        }

        var current = currentPage1Based - 1;
        var blockSize = 10;
        var blockStart = Math.floor(current / blockSize) * blockSize;
        var blockEnd = Math.min(totalPages - 1, blockStart + blockSize - 1);

        function addButton(label, targetPage, disabled, active) {
            var $button = $('<button type="button" class="adm-page-btn' +
                (active ? " is-active" : "") + '">' + label + "</button>");
            $button.prop("disabled", disabled || active);
            if (!disabled && !active) {
                $button.on("click", function () {
                    onPageClick(targetPage + 1);
                });
            }
            $pagination.append($button);
        }

        addButton("이전", blockStart - 1, blockStart === 0, false);
        for (var index = blockStart; index <= blockEnd; index++) {
            addButton(String(index + 1), index, false, index === current);
        }
        addButton("다음", blockEnd + 1, blockEnd === totalPages - 1, false);
    }

    function renderListError(message) {
        $("#taxSnapshotBody").html(stateRow(
            12,
            "세액 계산 목록을 불러오지 못했습니다.",
            message || "잠시 후 다시 시도해 주세요.",
            retryButton("tax-list-retry", "다시 시도")
        ));
        $("#taxPagination").empty();
        $("#taxSnapshotCount").text("-");
    }

    function renderSnapshots() {
        filteredSnapshots = getFilteredSnapshots();
        var totalPages = Math.max(1, Math.ceil(filteredSnapshots.length / pageSize));
        currentPage = Math.min(currentPage, totalPages);
        $("#taxSnapshotCount").text(filteredSnapshots.length.toLocaleString("ko-KR"));

        var $body = $("#taxSnapshotBody").empty();
        if (filteredSnapshots.length === 0) {
            var hasFilter = !!searchKeyword || !!statusFilter;
            $body.html(stateRow(
                12,
                hasFilter ? "조건에 맞는 계좌가 없습니다." : "세액 계산 결과가 없습니다.",
                hasFilter ? "조회조건을 초기화하거나 다시 검색해 주세요." : "",
                hasFilter ? retryButton("tax-empty-reset", "조건 초기화") : ""
            ));
            $("#taxPagination").empty();
            return;
        }

        var startIndex = (currentPage - 1) * pageSize;
        filteredSnapshots.slice(startIndex, startIndex + pageSize).forEach(function (snapshot, index) {
            var account = accountFor(snapshot.accountId);
            var accountNo = MARIA.fmt.hyphenateAccountNo(account.accountNo || "-");
            var actionBadges = [];
            if (snapshot.needsFinalReport) {
                actionBadges.push('<span class="adm-chip adm-chip-warn tax-action-chip">확정신고 필요</span>');
            }
            if (snapshot.needsClawback) {
                actionBadges.push('<span class="adm-chip adm-chip-fail tax-action-chip">추징 필요</span>');
            }
            var selected = Number(snapshot.accountId) === Number(selectedAccountId);
            var row = '<tr class="tax-snapshot-row is-clickable' + (selected ? " is-selected" : "") +
                '" data-account-id="' + escapeHtml(snapshot.accountId) + '" tabindex="0" role="button" ' +
                'aria-label="' + escapeHtml((MARIA.fmt.maskName(account.customerName) || "고객") + " 세액 계산 상세") + '">' +
                '<td class="adm-col-no">' + (startIndex + index + 1) + "</td>" +
                '<td class="tax-account-no">' + escapeHtml(accountNo) + "</td>" +
                '<td class="tax-account-name">' + escapeHtml(MARIA.fmt.maskName(account.customerName) || "-") + "</td>" +
                '<td class="is-c">' + benefitChip(account.benefit) + "</td>" +
                '<td class="is-r">' + formatAmount(snapshot.weightedSell) + "</td>" +
                '<td class="is-r">' + formatAmount(snapshot.weightedGain) + "</td>" +
                '<td class="is-r">' + formatRatio(snapshot.adjustRatio) + "</td>" +
                '<td class="is-r is-strong">' + formatAmount(snapshot.finalDeduction) + "</td>" +
                '<td class="is-r">' + formatAmount(snapshot.finalTax) + "</td>" +
                "<td>" + formatDateTime(snapshot.calculatedAt) + "</td>" +
                '<td class="is-c">' + statusChip(calculationStatus(snapshot.accountId)) + "</td>" +
                '<td class="is-c"><span class="tax-action-cell">' +
                (actionBadges.length ? actionBadges.join("") : "-") + "</span></td></tr>";
            $body.append(row);
        });

        renderPageButtons("#taxPagination", currentPage, totalPages, function (targetPage) {
            currentPage = targetPage;
            renderSnapshots();
        });
    }

    function showPageError(message) {
        $("#taxLoading").prop("hidden", true);
        $("#taxBody").prop("hidden", true);
        $("#taxError")
            .text(message || "세액 계산 화면을 불러오지 못했습니다.")
            .append(" ")
            .append($(retryButton("tax-page-retry", "다시 시도")))
            .prop("hidden", false);
    }

    function showMainPage() {
        $("#taxLoading, #taxError").prop("hidden", true);
        $("#taxBody").prop("hidden", false);
    }

    function showCalculationError(message) {
        MARIA.ui.showError(message || "세액 계산 정보를 불러오지 못했습니다.");
    }

    function renderBreakdown(periodBreakdown) {
        var $body = $("#detailBreakdownBody").empty();
        if (!periodBreakdown || periodBreakdown.length === 0) {
            $body.html('<div class="adm-mini-row"><span>구간별 근거 데이터가 없습니다.</span><span></span><span></span><span></span><span></span></div>');
            return;
        }
        periodBreakdown.forEach(function (period) {
            var range = formatPeriodRange(period);
            $body.append(
                '<div class="adm-mini-row">' +
                "<span title=\"" + escapeHtml(range) + '">' + escapeHtml(range) + "</span>" +
                '<span class="is-r">' + formatWeight(period.weight) + "</span>" +
                '<span class="is-r">' + formatAmount(period.sellAmount) + "</span>" +
                '<span class="is-r">' + formatSignedAmount(period.gainAmount) + "</span>" +
                '<span class="is-r">' + formatSignedAmount(period.externalNetBuyAmount) + "</span>" +
                "</div>"
            );
        });
    }

    function renderLotDetails(sellLotDetails) {
        var $body = $("#detailLotBody").empty();
        if (!sellLotDetails || sellLotDetails.length === 0) {
            $body.append('<div class="adm-mini-row"><span>매도 내역이 없습니다.</span></div>');
            return;
        }
        sellLotDetails.forEach(function (lot) {
            $body.append(
                '<div class="adm-mini-row">' +
                "<span title=\"" + escapeHtml(lot.productLabel || "-") + '">' +
                escapeHtml(lot.productLabel || "-") + "</span>" +
                "<span>" + formatDate(lot.finalAt) + "</span>" +
                '<span class="is-r">' + formatAmount(lot.sellAmount) + "</span>" +
                '<span class="is-r">' + formatSignedAmount(lot.gainAmount) + "</span>" +
                "</div>"
            );
        });
    }

    function renderExternalTradeDetails(externalTradeDetails) {
        var $body = $("#detailExternalTradeBody").empty();
        if (!externalTradeDetails || externalTradeDetails.length === 0) {
            $body.append('<div class="adm-mini-row"><span>외부 순매수 내역이 없습니다.</span></div>');
            return;
        }
        externalTradeDetails.forEach(function (trade) {
            $body.append(
                '<div class="adm-mini-row">' +
                "<span title=\"" + escapeHtml(trade.productLabel || "-") + '">' +
                escapeHtml(trade.productLabel || "-") + "</span>" +
                "<span>" + formatDate(trade.tradeDate) + "</span>" +
                '<span class="is-r">' + formatSignedAmount(trade.netBuyAmount) + "</span>" +
                "</div>"
            );
        });
    }

    function setSheetValues(result) {
        $("#detailWeightedSell").text(formatAmount(result.weightedSell));
        $("#detailWeightedGain").text(formatAmount(result.weightedGain));
        $("#detailExternalAmount").text(formatAmount(result.weightedExternalAmount));
        $("#detailAdjustRatio").text(formatRatio(result.adjustRatio));
        $("#detailFinalDeduction").text(formatAmount(result.finalDeduction));
        $("#detailOriginalGain").text(formatAmount(result.originalGainAmount));
        $("#detailFinalTax").text(formatAmount(result.finalTax));
        $("#tax-sheet-period-total").text("-");
        $("#tax-sheet-final-total").text(formatAmount(result.finalTax));
        $("#tax-sheet-weighted-total").text(formatAmount(result.weightedSell));
    }

    function setCalculationState(accountId, data) {
        var saved = data.latestSavedCalculation;
        confirmedByAccountId[accountId] = !!saved && saved.basisType === "FINAL_REPORT";
        renderSnapshots();

        if (data.benefitChangeReason) {
            $("#detailBenefitReason")
                .text("세제혜택 변경 사유 (" + formatDateTime(data.benefitChangedAt) + "): " + data.benefitChangeReason)
                .prop("hidden", false);
        } else {
            $("#detailBenefitReason").prop("hidden", true).text("");
        }

        if (saved) {
            $("#detailSavedBadge")
                .text((BASIS_LABEL[saved.basisType] || saved.basisType) + " 저장됨 · " + formatDateTime(saved.calculatedAt))
                .prop("hidden", false);
        } else {
            $("#detailSavedBadge").prop("hidden", true).text("");
        }
        $("#detailConfirmBtn").prop("hidden", !MARIA.auth.hasRole("SETTLEMENT"));
    }

    function openDetailPanel() {
        clearTimeout(detailCloseTimer);
        $("#taxDetail, #detailPanelBackdrop").prop("hidden", false);
        $("#taxDetail").attr("aria-hidden", "false");
        window.requestAnimationFrame(function () {
            $("#taxDetail, #detailPanelBackdrop").addClass("is-open");
        });
    }

    function closeDetailPanel() {
        previewRequestId++;
        $("#taxDetail, #detailPanelBackdrop").removeClass("is-open");
        $("#taxDetail").attr("aria-hidden", "true");
        detailCloseTimer = setTimeout(function () {
            if (!$("#taxDetail").hasClass("is-open")) {
                $("#taxDetail, #detailPanelBackdrop").prop("hidden", true);
            }
        }, 260);
    }

    function openDetail(accountId) {
        var requestId = ++previewRequestId;
        selectedAccountId = Number(accountId);
        var account = accountFor(accountId);
        var snapshot = snapshotByAccountId[accountId] || {};
        $("#detailAccountNo").text((account.accountNo || "-") + " · " + (MARIA.fmt.maskName(account.customerName) || "-"));
        $("#detailDescription").text("세액 계산 상세");
        $("#detailStaleBadge, #detailSavedBadge, #detailBenefitReason").prop("hidden", true);
        $("#detailConfirmResult").text("");
        $("#detailBreakdownBody, #detailLotBody, #detailExternalTradeBody").empty();
        $("#detailWeightedSell, #detailWeightedGain, #detailExternalAmount, #detailAdjustRatio, " +
            "#detailFinalDeduction, #detailOriginalGain, #detailFinalTax").text("-");
        $("#detailConfirmBtn").prop("hidden", !MARIA.auth.hasRole("SETTLEMENT"));
        $("#detailReliefResult").prop("hidden", true);
        $("#reliefFinalAt, #reliefFinalTax").text("-");
        openDetailPanel();

        MARIA.auth.ajax({
            url: "/api/admin/tax/preview/" + encodeURIComponent(accountId),
            method: "GET"
        }).done(function (response) {
            if (requestId !== previewRequestId || selectedAccountId !== Number(accountId)) {
                return;
            }
            var data = response.data || {};
            var result = data.taxCalculationResultDTO || {};
            var breakdown = data.breakdown || {};
            setSheetValues(result);
            renderBreakdown(breakdown.periodBreakdown);
            renderLotDetails(breakdown.sellLotDetails);
            renderExternalTradeDetails(breakdown.externalTradeDetails);
            var stale = snapshot.finalTax != null && Number(snapshot.finalTax) !== Number(result.finalTax);
            $("#detailStaleBadge").prop("hidden", !stale);
            setCalculationState(accountId, data);
            $("#tax-relief-toggle").prop("hidden", false);
        }).fail(function (xhr) {
            if (requestId !== previewRequestId || selectedAccountId !== Number(accountId)) {
                return;
            }
            if (xhr.status === 401) {
                return;
            }
            showCalculationError(
                (xhr.responseJSON && xhr.responseJSON.message) || "세액 미리보기를 불러오지 못했습니다."
            );
        });
    }

    function renderBatchHistory(history) {
        allBatchHistory = history || [];
        var totalPages = Math.max(1, Math.ceil(allBatchHistory.length / batchHistoryPageSize));
        batchHistoryPage = Math.min(batchHistoryPage, totalPages);
        $("#batchHistoryCount").text(allBatchHistory.length.toLocaleString("ko-KR"));

        var $body = $("#batchHistoryBody").empty();
        if (allBatchHistory.length === 0) {
            $body.html('<tr class="is-state"><td colspan="6"><div class="adm-state-empty">' +
                '<div class="adm-state-empty-title">배치 실행 이력이 없습니다.</div></div></td></tr>');
            $("#batchHistoryPagination").empty();
            return;
        }

        var startIndex = (batchHistoryPage - 1) * batchHistoryPageSize;
        allBatchHistory.slice(startIndex, startIndex + batchHistoryPageSize).forEach(function (item) {
            var status = String(item.status || "UNKNOWN").toUpperCase();
            var label = BATCH_STATUS_LABEL[status] || status;
            var tone = BATCH_STATUS_TONE[status] || "off";
            $body.append(
                "<tr>" +
                '<td class="tax-mono">' + escapeHtml(item.runId || "-") + "</td>" +
                "<td>" + formatDateTime(item.startTime) + "</td>" +
                "<td>" + formatDateTime(item.endTime) + "</td>" +
                '<td class="is-c"><span class="adm-chip adm-chip-' + tone + '">' + escapeHtml(label) + "</span></td>" +
                '<td class="is-r">' + formatAmount(item.writeCount) + "</td>" +
                '<td class="is-r">' + formatAmount(item.skipCount) + "</td>" +
                "</tr>"
            );
        });

        renderPageButtons("#batchHistoryPagination", batchHistoryPage, totalPages, function (targetPage) {
            batchHistoryPage = targetPage;
            renderBatchHistory(allBatchHistory);
        });
    }

    function loadBatchHistory() {
        $("#batchHistoryBody").html('<tr class="is-state tax-history-loading"><td colspan="6">불러오는 중...</td></tr>');
        MARIA.auth.ajax({
            url: "/api/admin/tax/snapshots/jobs",
            method: "GET"
        }).done(function (response) {
            renderBatchHistory(response.data || []);
        }).fail(function (xhr) {
            if (xhr.status === 401) {
                return;
            }
            $("#batchHistoryBody").html(
                '<tr class="is-state"><td colspan="6"><div class="adm-state-error">' +
                '<div class="adm-state-error-text"><div class="adm-state-error-title">배치 이력을 불러오지 못했습니다.</div>' +
                '<div class="adm-state-error-desc">' +
                escapeHtml((xhr.responseJSON && xhr.responseJSON.message) || "잠시 후 다시 시도해 주세요.") +
                "</div></div>" + retryButton("batch-history-retry", "다시 시도") + "</div></td></tr>"
            );
        });
    }

    function loadActionSummary() {
        MARIA.auth.ajax({
            url: "/api/admin/tax/action-summary",
            method: "GET"
        }).done(function (response) {
            actionSummary = response.data || {};
            $("#kpiClawback").text(actionSummary.unprocessedClawbackCount);
            $("#kpiBenefitChanged").text(actionSummary.benefitChangedTodayCount);
        }).fail(function (xhr) {
            if (xhr.status !== 401) {
                MARIA.ui.showError(
                    (xhr.responseJSON && xhr.responseJSON.message) || "처리 필요 요약을 불러오지 못했습니다."
                );
            }
        });
    }

    function loadBusinessToday() {
        MARIA.auth.ajax({
            url: "/api/admin/system-clock",
            method: "GET"
        }).done(function (response) {
            businessToday = dateOnly(response.data);
        }).fail(function (xhr) {
            if (xhr.status !== 401) {
                MARIA.ui.showError(
                    (xhr.responseJSON && xhr.responseJSON.message) || "업무일자를 불러오지 못했습니다."
                );
            }
        });
    }

    function requestSnapshots(accountIds) {
        var chunks = [];
        for (var index = 0; index < accountIds.length; index += 200) {
            chunks.push(accountIds.slice(index, index + 200));
        }
        if (chunks.length === 0) {
            return $.Deferred().resolve([]).promise();
        }
        var requests = chunks.map(function (chunk) {
            return MARIA.auth.ajax({
                url: "/api/admin/tax/snapshots",
                method: "GET",
                data: { accountIds: chunk },
                traditional: true
            });
        });
        return $.when.apply($, requests).then(function () {
            var args = Array.prototype.slice.call(arguments);
            var responses = requests.length === 1 ? [args[0]] : args.map(function (result) {
                return result[0];
            });
            return responses.reduce(function (snapshots, response) {
                return snapshots.concat(response.data || []);
            }, []);
        });
    }

    function loadTax() {
        $("#taxLoading").prop("hidden", false).text("불러오는 중...");
        $("#taxError, #taxBody").prop("hidden", true);
        loadBusinessToday();
        loadActionSummary();

        MARIA.auth.ajax({
            url: "/api/admin/account/list",
            method: "GET"
        }).done(function (accountResponse) {
            accountMap = {};
            var accounts = accountResponse.data || [];
            accounts.forEach(function (account) {
                accountMap[account.accountId] = account;
            });

            requestSnapshots(accounts.map(function (account) {
                return account.accountId;
            })).done(function (snapshots) {
                allSnapshots = snapshots || [];
                snapshotByAccountId = {};
                allSnapshots.forEach(function (snapshot) {
                    snapshotByAccountId[snapshot.accountId] = snapshot;
                });
                renderSummary();
                showMainPage();
                renderSnapshots();
            }).fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                showPageError((xhr.responseJSON && xhr.responseJSON.message) || "세액 계산 목록을 불러오지 못했습니다.");
            });
        }).fail(function (xhr) {
            if (xhr.status === 401) {
                return;
            }
            showPageError((xhr.responseJSON && xhr.responseJSON.message) || "계좌 목록을 불러오지 못했습니다.");
        });
    }

    function openConfirm(action) {
        pendingAction = action;
        if (action.type === "save") {
            var snapshot = snapshotByAccountId[action.accountId] || {};
            $("#tax-confirm-title").text("확정신고 저장");
            $("#tax-confirm-description").text(
                "최종공제액 " + formatAmount(snapshot.finalDeduction) + "원, 최종세액 " +
                formatAmount(snapshot.finalTax) + "원으로 저장할까요?"
            );
        } else {
            $("#tax-confirm-title").text("세액 계산 배치 실행");
            $("#tax-confirm-description").text("전체 RIA 계좌의 공제액과 세액을 다시 산출할까요?");
        }
        $("#tax-confirm-modal").prop("hidden", false);
        $("#tax-confirm-accept").prop("disabled", false).trigger("focus");
    }

    function closeConfirm() {
        pendingAction = null;
        $("#tax-confirm-modal").prop("hidden", true);
        $("#tax-confirm-accept").prop("disabled", false);
    }

    function confirmAction() {
        if (!pendingAction) {
            return;
        }
        var action = pendingAction;
        var $accept = $("#tax-confirm-accept").prop("disabled", true);
        if (action.type === "save") {
            MARIA.auth.ajax({
                url: "/api/admin/tax/calculations/" + encodeURIComponent(action.accountId),
                method: "POST"
            }).done(function (response) {
                var data = response.data || {};
                $("#detailConfirmResult").text((BASIS_LABEL[data.basisType] || data.basisType || "확정신고") + " 저장 완료");
                MARIA.ui.showSuccess("확정신고로 저장했어요");
                if (data.basisType === "FINAL_REPORT") {
                    confirmedByAccountId[action.accountId] = true;
                }
                closeConfirm();
                loadTax();
                openDetail(action.accountId);
            }).fail(function (xhr) {
                if (xhr.status === 401) {
                    closeConfirm();
                    return;
                }
                $accept.prop("disabled", false);
                $("#detailConfirmResult").text((xhr.responseJSON && xhr.responseJSON.message) || "확정신고 저장에 실패했습니다.");
            });
            return;
        }

        MARIA.auth.ajax({
            url: "/api/admin/tax/snapshots/jobs",
            method: "POST"
        }).done(function (response) {
            var data = response.data || {};
            $("#batchTriggerResult")
                .text("요청됨 · " + (data.runId || "-") + " · " + (data.status || ""))
                .prop("hidden", false);
            MARIA.ui.showSuccess("세액 계산 배치를 요청했어요");
            closeConfirm();
            setTimeout(loadBatchHistory, 1500);
        }).fail(function (xhr) {
            if (xhr.status === 401) {
                closeConfirm();
                return;
            }
            $accept.prop("disabled", false);
            MARIA.ui.showError((xhr.responseJSON && xhr.responseJSON.message) || "배치 실행 요청에 실패했습니다.");
        });
    }

    function resetFilters() {
        $("#taxSearchInput").val("");
        $("#taxStatusFilter").val("");
        searchKeyword = "";
        statusFilter = "";
        currentPage = 1;
        renderSnapshots();
    }

    $("#taxSnapshotBody").on("click", "tr[data-account-id]", function () {
        var accountId = $(this).data("account-id");
        renderSnapshots();
        openDetail(accountId);
    }).on("keydown", "tr[data-account-id]", function (event) {
        if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            var accountId = $(this).data("account-id");
            renderSnapshots();
            openDetail(accountId);
        }
    }).on("click", "#tax-list-retry", loadTax)
        .on("click", "#tax-empty-reset", resetFilters);

    $("#taxSearchButton").on("click", function () {
        searchKeyword = $("#taxSearchInput").val().trim().toLowerCase();
        statusFilter = $("#taxStatusFilter").val();
        currentPage = 1;
        renderSnapshots();
    });
    $("#taxSearchInput").on("keydown", function (event) {
        if (event.key === "Enter") {
            event.preventDefault();
            $("#taxSearchButton").trigger("click");
        }
    });
    $("#taxSearchReset").on("click", resetFilters);
    $("#tax-page-size button").on("click", function () {
        pageSize = Number($(this).data("size"));
        $("#tax-page-size button").removeClass("is-on");
        $(this).addClass("is-on");
        currentPage = 1;
        renderSnapshots();
    });

    $("#detailCloseBtn, #detailPanelBackdrop").on("click", closeDetailPanel);
    $("#detailConfirmBtn").on("click", function () {
        if (!selectedAccountId || !MARIA.auth.hasRole("SETTLEMENT")) {
            return;
        }
        openConfirm({ type: "save", accountId: selectedAccountId });
    });
    $("#tax-confirm-cancel").on("click", closeConfirm);
    $("#tax-confirm-modal").on("click", function (event) {
        if (event.target === this) {
            closeConfirm();
        }
    });
    document.addEventListener("keydown", function (event) {
        if (event.key === "Escape" && !document.getElementById("tax-confirm-modal").hidden) {
            event.preventDefault();
            event.stopImmediatePropagation();
            closeConfirm();
        }
    }, true);
    $("#tax-confirm-accept").on("click", confirmAction);

    $("#tax-relief-toggle").on("click", function () {
        var expanded = $(this).attr("aria-expanded") === "true";
        $(this).attr("aria-expanded", String(!expanded));
        $("#tax-relief-body").prop("hidden", expanded);
    });
    $("#detailReliefBtn").on("click", function () {
        if (!selectedAccountId) {
            return;
        }
        var $button = $(this).prop("disabled", true).text("조회 중...");
        MARIA.auth.ajax({
            url: "/api/admin/tax/preview/" + encodeURIComponent(selectedAccountId) + "/expected-relief",
            method: "GET"
        }).done(function (response) {
            $("#reliefFinalAt").text(formatDate(response.data.expectedFinalAt));
            $("#reliefFinalTax").text(formatAmount(response.data.taxCalculationResultDTO.finalTax) + "원");
            $("#detailReliefResult").prop("hidden", false);
        }).fail(function (xhr) {
            if (xhr.status !== 401) {
                showCalculationError((xhr.responseJSON && xhr.responseJSON.message) || "예상 감면세액 조회에 실패했습니다.");
            }
        }).always(function () {
            $button.prop("disabled", false).text("F7 · 예상 감면세액 조회");
        });
    });

    $("#triggerBatch").on("click", function () {
        if (MARIA.auth.hasRole("SETTLEMENT")) {
            openConfirm({ type: "batch" });
        }
    });
    $("#batchHistoryBody").on("click", "#batch-history-retry", loadBatchHistory);
    $("#taxError").on("click", "#tax-page-retry", loadTax);

    MARIA.auth.requireAuth().done(function () {
        $("#triggerBatch").prop("hidden", !MARIA.auth.hasRole("SETTLEMENT"));
        $("#batchTriggerGroup").toggle(MARIA.auth.hasRole("SETTLEMENT"));
    });

    var deepLinkAccountNo = MARIA.deeplink.accountNoFromUrl();
    if (deepLinkAccountNo) {
        $("#taxSearchInput").val(deepLinkAccountNo);
        searchKeyword = deepLinkAccountNo.toLowerCase();
    }

    MARIA.tableSort.bind(".tax-snapshot-table", function (key, dir) {
        sortSpec = { key: key, dir: dir };
        currentPage = 1;
        renderSnapshots();
    });

    loadTax();
    loadBatchHistory();
});
