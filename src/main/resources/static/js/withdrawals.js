$(function () {
    var PAGE_SIZE = 12;
    var STATUS_LABEL = {
        REQUESTED: "처리 요청",
        COMPLETED: "처리 완료",
        FAILED: "실패"
    };
    var TYPE_LABEL = {
        EARNINGS_ONLY: "수익금",
        MATURED_PRINCIPAL_INCLUDED: "1년 경과 원금",
        IMMATURE_PRINCIPAL_INCLUDED: "1년 미경과 원금"
    };
    var DATE_TIME_FORMATTER = new Intl.DateTimeFormat("ko-KR", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit"
    });
    var withdrawalHistories = [];
    var accountMetadataByNo = {};
    var accounts = [];
    var selectedAccountNo = null;
    var selectedAccountBenefit = null;
    var selectedWithdrawalType = "ALL";
    var currentPage = 1;
    var accountAllocationEntries = [];
    var selectedAllocationIndex = null;
    var currentAllocationPage = 1;
    var allocationSortDirection = "DESC";

    function escapeHtml(value) {
        return $("<div>").text(value == null ? "" : value).html();
    }

    function formatAmount(value) {
        return "₩" + new Intl.NumberFormat("ko-KR").format(Number(value || 0));
    }

    function formatDateTime(value) {
        return value ? DATE_TIME_FORMATTER.format(new Date(value)) : "-";
    }

    function statusLabel(status) {
        return STATUS_LABEL[status] || status || "-";
    }

    function statusClass(status) {
        return (status || "").toLowerCase();
    }

    function allocationCountForView(withdrawal, viewType) {
        if (viewType === "NORMAL") {
            return Number(withdrawal.normalAllocationCount || 0);
        }
        if (viewType === "EARLY") {
            return Number(withdrawal.earlyAllocationCount || 0);
        }
        return Number(withdrawal.allocationCount || 0);
    }

    function allocatedAmountForView(withdrawal, viewType) {
        if (viewType === "NORMAL") {
            return Number(withdrawal.earningsAmount || 0) +
                Number(withdrawal.maturedPrincipalAmount || 0);
        }
        if (viewType === "EARLY") {
            return Number(withdrawal.immaturePrincipalAmount || 0);
        }
        if (viewType === "FAILED") {
            return Number(withdrawal.requestedAmount || 0);
        }
        return Number(withdrawal.earningsAmount || 0) +
            Number(withdrawal.maturedPrincipalAmount || 0) +
            Number(withdrawal.immaturePrincipalAmount || 0);
    }

    function groupWithdrawalsByAccount(withdrawals, viewType) {
        var accountMap = {};

        withdrawals.forEach(function (withdrawal) {
            var accountNo = String(withdrawal.riaAccountNo || "-");
            var accountMetadata = accountMetadataByNo[accountNo] || {};
            if (!accountMap[accountNo]) {
                accountMap[accountNo] = {
                    accountNo: accountNo,
                    customerName: withdrawal.customerName || "-",
                    benefit: accountMetadata.benefit || null,
                    openedAt: accountMetadata.openedAt || null,
                    currentAmount: accountMetadata.amount || 0,
                    withdrawals: [],
                    allocationCount: 0,
                    totalAmount: 0,
                    latestProcessedAt: null
                };
            }

            var account = accountMap[accountNo];
            account.withdrawals.push(withdrawal);
            account.allocationCount += allocationCountForView(withdrawal, viewType);
            account.totalAmount += allocatedAmountForView(withdrawal, viewType);
            if (!account.latestProcessedAt ||
                new Date(withdrawal.processedAt) > new Date(account.latestProcessedAt)) {
                account.latestProcessedAt = withdrawal.processedAt;
            }
        });

        return Object.keys(accountMap).map(function (accountNo) {
            return accountMap[accountNo];
        }).sort(function (left, right) {
            return new Date(right.latestProcessedAt) - new Date(left.latestProcessedAt);
        });
    }

    function renderPagination(totalPages) {
        var $pagination = $("#withdrawal-pagination").empty();
        if (totalPages <= 1) {
            $pagination.prop("hidden", true);
            return;
        }

        var blockSize = 10;
        var blockStart = Math.floor((currentPage - 1) / blockSize) * blockSize + 1;
        var blockEnd = Math.min(totalPages, blockStart + blockSize - 1);

        function appendPageButton(label, targetPage, disabled, active) {
            var $button = $("<button>", {
                type: "button",
                class: "page-btn" + (active ? " active" : ""),
                text: label
            }).prop("disabled", disabled || active);

            if (!disabled && !active) {
                $button.on("click", function () {
                    currentPage = targetPage;
                    selectFirstAccountOnCurrentPage();
                });
            }
            $pagination.append($button);
        }

        appendPageButton("이전", blockStart - 1, blockStart === 1, false);
        for (var page = blockStart; page <= blockEnd; page += 1) {
            appendPageButton(String(page), page, false, page === currentPage);
        }
        appendPageButton("다음", blockEnd + 1, blockEnd === totalPages, false);
        $pagination.prop("hidden", false);
    }

    function renderAccounts() {
        var $list = $("#withdrawal-list").empty();

        if (!accounts.length) {
            $list.append('<div class="withdrawal-empty">해당 조건의 인출 계좌가 없습니다.</div>');
            $("#withdrawal-pagination").prop("hidden", true);
            clearDetail();
            return;
        }

        var totalPages = Math.max(1, Math.ceil(accounts.length / PAGE_SIZE));
        currentPage = Math.min(currentPage, totalPages);
        var startIndex = (currentPage - 1) * PAGE_SIZE;

        accounts.slice(startIndex, startIndex + PAGE_SIZE).forEach(function (account) {
            var selectedClass = account.accountNo === selectedAccountNo ? " is-selected" : "";
            $list.append(
                '<button type="button" class="withdrawal-list-item' + selectedClass + '"' +
                ' data-account-no="' + escapeHtml(account.accountNo) + '">' +
                '<span class="withdrawal-account-cell"><strong>' +
                MARIA.fmt.accountNoHtml(account.accountNo) + '</strong><small>' +
                escapeHtml(account.customerName) + '</small></span>' +
                '<strong class="withdrawal-history-count">' + account.allocationCount + '건</strong>' +
                '<strong class="withdrawal-request-amount">' +
                escapeHtml(formatAmount(account.totalAmount)) + '</strong>' +
                '<span class="withdrawal-list-time">' +
                escapeHtml(formatDateTime(account.latestProcessedAt)) + '</span></button>'
            );
        });

        renderPagination(totalPages);
    }

    function clearDetail() {
        selectedAccountNo = null;
        selectedAccountBenefit = null;
        accountAllocationEntries = [];
        selectedAllocationIndex = null;
        $("#withdrawal-detail").addClass("is-empty");
        $("#withdrawal-detail-empty").show();
        $("#withdrawal-detail-content").prop("hidden", true);
        $("#withdrawal-account-benefit-warning").prop("hidden", true);
        closeDrawer();
    }

    function applyFilters() {
        var keyword = ($("#withdrawal-keyword").val() || "").trim().toLowerCase();
        var allAccounts = groupWithdrawalsByAccount(withdrawalHistories, "ALL");
        var normalAccounts = groupWithdrawalsByAccount(withdrawalHistories.filter(
            function (withdrawal) {
                return withdrawal.status === "COMPLETED" &&
                    Number(withdrawal.normalAllocationCount || 0) > 0;
            }
        ), "NORMAL");
        var earlyAccounts = groupWithdrawalsByAccount(withdrawalHistories.filter(
            function (withdrawal) {
                return withdrawal.status === "COMPLETED" &&
                    Number(withdrawal.earlyAllocationCount || 0) > 0;
            }
        ), "EARLY");
        var failedAccounts = groupWithdrawalsByAccount(withdrawalHistories.filter(function (withdrawal) {
            return withdrawal.status === "FAILED";
        }), "FAILED");

        $("#withdrawal-all-count").text(allAccounts.length);
        $("#withdrawal-normal-count").text(normalAccounts.length);
        $("#withdrawal-early-count").text(earlyAccounts.length);
        $("#withdrawal-failed-count").text(failedAccounts.length);

        var accountsForSelectedType = allAccounts;
        if (selectedWithdrawalType === "NORMAL") {
            accountsForSelectedType = normalAccounts;
        } else if (selectedWithdrawalType === "EARLY") {
            accountsForSelectedType = earlyAccounts;
        } else if (selectedWithdrawalType === "FAILED") {
            accountsForSelectedType = failedAccounts;
        }
        accounts = accountsForSelectedType.filter(function (account) {
            var matchesKeyword = !keyword ||
                account.customerName.toLowerCase().indexOf(keyword) >= 0 ||
                account.accountNo.toLowerCase().indexOf(keyword) >= 0;
            return matchesKeyword;
        });

        currentPage = 1;
        if (accounts.length) {
            selectAccount(accounts[0].accountNo);
        } else {
            renderAccounts();
        }
    }

    function calculateProgress(allocation) {
        if (!allocation.finalAt || !allocation.maturityAt) {
            return null;
        }
        var finalAt = new Date(allocation.finalAt).getTime();
        var maturityAt = new Date(allocation.maturityAt).getTime();
        var withdrawalAt = new Date(allocation.withdrawalAt).getTime();
        var total = maturityAt - finalAt;
        if (total <= 0 || Number.isNaN(withdrawalAt)) {
            return null;
        }
        return Math.round(Math.max(0, Math.min(1, (withdrawalAt - finalAt) / total)) * 100);
    }

    function allocationMarkup(entry, index) {
        var allocation = entry.allocation;
        var progress = calculateProgress(allocation);
        var isEarnings = allocation.type === "EARNINGS_ONLY";
        var allocationTitle = isEarnings
            ? "수익금 배분"
            : (allocation.productName
                ? allocation.productName + (allocation.ticker ? " (" + allocation.ticker + ")" : "")
                : "종목 정보 없음");
        var progressMarkup = isEarnings || progress == null
            ? '<div class="retention-not-applicable">' +
                (isEarnings ? "수익금은 의무유지기간 비대상" : "의무유지기간 정보 없음") +
                '</div>'
            : '<div class="retention-dates"><span>' + escapeHtml(formatDateTime(allocation.finalAt)) +
                '</span><span>1년 경과일 ' + escapeHtml(formatDateTime(allocation.maturityAt)) + '</span></div>' +
                '<div class="retention-progress"><span style="width:' + progress + '%"></span></div>' +
                '<div class="retention-progress-label">인출 시점 기준 ' + progress + '% 경과</div>';
        var selectedClass = index === selectedAllocationIndex ? " is-selected" : "";

        return '<button type="button" class="withdrawal-allocation-item' + selectedClass + ' ' +
            statusClass(allocation.type) + '" data-allocation-index="' + index + '">' +
            '<div class="allocation-item-header"><div><span>' + escapeHtml(allocationTitle) + '</span>' +
            '<strong>' + escapeHtml(TYPE_LABEL[allocation.type] || allocation.type) + '</strong></div>' +
            '<strong>' + escapeHtml(formatAmount(allocation.allocatedAmount)) + '</strong></div>' +
            '<div class="allocation-meta"><span>환전건 ' +
            escapeHtml(allocation.exchangeId == null ? "해당 없음" : "#" + allocation.exchangeId) +
            '</span><span>인출 ' + escapeHtml(formatDateTime(allocation.withdrawalAt)) + '</span></div>' +
            progressMarkup + '</button>';
    }

    function renderAccountAllocations() {
        var $container = $("#withdrawal-allocations").empty();
        if (!accountAllocationEntries.length) {
            $container.append('<div class="withdrawal-empty">저장된 배분 내역이 없습니다.</div>');
            $("#withdrawal-allocation-pagination").prop("hidden", true);
            return;
        }

        var groups = [];
        var groupByWithdrawalId = {};
        accountAllocationEntries.forEach(function (entry, index) {
            var withdrawalId = String(entry.withdrawal.withdrawalId);
            if (!groupByWithdrawalId[withdrawalId]) {
                groupByWithdrawalId[withdrawalId] = {
                    withdrawal: entry.withdrawal,
                    entries: []
                };
                groups.push(groupByWithdrawalId[withdrawalId]);
            }
            groupByWithdrawalId[withdrawalId].entries.push({
                entry: entry,
                index: index
            });
        });

        var totalPages = groups.length;
        currentAllocationPage = Math.min(currentAllocationPage, totalPages);
        groups.slice(currentAllocationPage - 1, currentAllocationPage).forEach(function (group) {
            var withdrawal = group.withdrawal;
            var $group = $('<section class="withdrawal-allocation-group"></section>');
            $group.append(
                '<div class="withdrawal-allocation-group-header">' +
                '<div><span>인출 일시 · 배분 ' + group.entries.length + '건</span><strong>' +
                escapeHtml(formatDateTime(withdrawal.processedAt)) + '</strong></div>' +
                '<div><span>요청금액</span><strong>' +
                escapeHtml(formatAmount(
                    withdrawal.displayedAmount == null
                        ? withdrawal.requestedAmount
                        : withdrawal.displayedAmount
                )) + '</strong></div>' +
                '<span class="withdrawal-status-badge ' + statusClass(withdrawal.status) + '">' +
                escapeHtml(statusLabel(withdrawal.status)) + '</span></div>'
            );
            var $items = $('<div class="withdrawal-allocation-group-items"></div>');
            group.entries.forEach(function (groupEntry) {
                if (groupEntry.entry.allocation) {
                    $items.append(allocationMarkup(groupEntry.entry, groupEntry.index));
                } else {
                    $items.append(
                        '<button type="button" class="withdrawal-unallocated-item ' +
                        statusClass(withdrawal.status) + '" data-allocation-index="' +
                        groupEntry.index + '"><strong>' +
                        '인출 실패</strong></button>'
                    );
                }
            });
            $group.append($items);
            $container.append($group);
        });

        renderAllocationPagination(totalPages);
    }

    function sortAccountAllocationEntries() {
        var direction = allocationSortDirection === "ASC" ? 1 : -1;
        accountAllocationEntries.sort(function (left, right) {
            var leftDatetime = left.allocation
                ? left.allocation.withdrawalAt
                : left.withdrawal.processedAt;
            var rightDatetime = right.allocation
                ? right.allocation.withdrawalAt
                : right.withdrawal.processedAt;
            return (new Date(leftDatetime) - new Date(rightDatetime)) * direction;
        });
    }

    function renderAllocationPagination(totalPages) {
        var $pagination = $("#withdrawal-allocation-pagination").empty();
        if (totalPages <= 1) {
            $pagination.prop("hidden", true);
            return;
        }

        function appendButton(label, targetPage, disabled, active) {
            var $button = $("<button>", {
                type: "button",
                class: "page-btn" + (active ? " active" : ""),
                text: label
            }).prop("disabled", disabled || active);

            if (!disabled && !active) {
                $button.on("click", function () {
                    currentAllocationPage = targetPage;
                    selectedAllocationIndex = null;
                    closeDrawer();
                    renderAccountAllocations();
                });
            }
            $pagination.append($button);
        }

        appendButton("이전", currentAllocationPage - 1, currentAllocationPage === 1, false);
        for (var page = 1; page <= totalPages; page += 1) {
            appendButton(String(page), page, false, page === currentAllocationPage);
        }
        appendButton("다음", currentAllocationPage + 1, currentAllocationPage === totalPages, false);
        $pagination.prop("hidden", false);
    }

    function renderWithdrawalDetail(withdrawal, allocation) {
        var allocationProgress = calculateProgress(allocation);
        var isEarnings = allocation.type === "EARNINGS_ONLY";
        var allocationTitle = isEarnings
            ? "수익금 배분"
            : (allocation.productName
                ? allocation.productName + (allocation.ticker ? " (" + allocation.ticker + ")" : "")
                : "종목 정보 없음");

        $("#selected-allocation-title").text(allocationTitle);
        $("#selected-allocation-type").text(TYPE_LABEL[allocation.type] || allocation.type || "-");
        $("#selected-allocation-amount").text(formatAmount(allocation.allocatedAmount));
        $("#selected-allocation-exchange").text(
            allocation.exchangeId == null ? "해당 없음" : "#" + allocation.exchangeId
        );
        $("#selected-allocation-final-at").text(formatDateTime(allocation.finalAt));
        $("#selected-allocation-maturity-at").text(formatDateTime(allocation.maturityAt));
        $("#selected-allocation-withdrawal-at").text(formatDateTime(allocation.withdrawalAt));
        $("#selected-allocation-progress").text(
            isEarnings
                ? "의무유지기간 비대상"
                : (allocationProgress == null ? "정보 없음" : allocationProgress + "% 경과")
        );
        $(".allocation-summary").removeClass("is-selected is-danger-selected");
        if (allocation.type === "EARNINGS_ONLY") {
            $(".allocation-summary.earnings").addClass("is-selected");
        } else if (allocation.type === "MATURED_PRINCIPAL_INCLUDED") {
            $(".allocation-summary.matured").addClass("is-selected");
        } else if (allocation.type === "IMMATURE_PRINCIPAL_INCLUDED") {
            $(".allocation-summary.immature").addClass("is-danger-selected");
        }
        $("#withdrawal-selected-allocation").prop("hidden", false);
        renderWithdrawalOverview(withdrawal);
    }

    function renderUnallocatedWithdrawalDetail(withdrawal) {
        $("#withdrawal-selected-allocation").prop("hidden", true);
        $(".allocation-summary").removeClass("is-selected is-danger-selected");
        renderWithdrawalOverview(withdrawal);
    }

    function renderWithdrawalOverview(withdrawal) {
        $("#withdrawal-detail-title").text((withdrawal.customerName || "-") + " 고객 인출");
        $("#withdrawal-customer-name").text(withdrawal.customerName || "-");
        $("#withdrawal-account-no").html(MARIA.fmt.accountNoHtml(withdrawal.riaAccountNo));
        $("#withdrawal-account-opened-at").text(formatDateTime(accountMetadataByNo[selectedAccountNo] &&
            accountMetadataByNo[selectedAccountNo].openedAt));
        $("#withdrawal-current-balance").text(formatAmount(
            accountMetadataByNo[selectedAccountNo] && accountMetadataByNo[selectedAccountNo].amount
        ));
        $("#withdrawal-requested-amount").text(formatAmount(withdrawal.requestedAmount));
        $("#withdrawal-destination-account").html(MARIA.fmt.accountNoHtml(withdrawal.destinationAccountNo));
        $("#withdrawal-processed-at").text(formatDateTime(withdrawal.processedAt));
        $("#withdrawal-early-result").text(withdrawal.earlyWithdrawal ? "발생" : "없음");
        $("#withdrawal-earnings-amount").text(formatAmount(withdrawal.earningsAmount));
        $("#withdrawal-matured-amount").text(formatAmount(withdrawal.maturedPrincipalAmount));
        $("#withdrawal-immature-amount").text(formatAmount(withdrawal.immaturePrincipalAmount));
        $("#withdrawal-detail-status")
            .attr("class", "withdrawal-status-badge " + statusClass(withdrawal.status))
            .text(statusLabel(withdrawal.status));
        $("#withdrawal-early-warning").prop("hidden", selectedAccountBenefit !== "IMPOSSIBLE");
        $("#withdrawal-detail-drawer").addClass("is-open").attr("aria-hidden", "false");
        $("#withdrawal-drawer-backdrop").prop("hidden", false);
    }

    function closeDrawer() {
        $("#withdrawal-detail-drawer").removeClass("is-open").attr("aria-hidden", "true");
        $("#withdrawal-drawer-backdrop").prop("hidden", true);
    }

    function loadWithdrawalDetail(withdrawalId) {
        return loadApiData("/api/admin/withdrawals/" + withdrawalId);
    }

    function loadApiData(url) {
        return new Promise(function (resolve, reject) {
            MARIA.auth.ajax({
                url: url,
                method: "GET"
            }).done(function (response) {
                resolve(response.data);
            }).fail(reject);
        });
    }

    function selectAccount(accountNo) {
        var account = accounts.find(function (item) {
            return item.accountNo === accountNo;
        });
        if (!account) {
            return;
        }

        selectedAccountNo = accountNo;
        selectedAccountBenefit = account.benefit;
        selectedAllocationIndex = null;
        currentAllocationPage = 1;
        accountAllocationEntries = [];
        renderAccounts();
        $("#withdrawal-detail").removeClass("is-empty");
        $("#withdrawal-detail-empty").hide();
        $("#withdrawal-detail-content").prop("hidden", false);
        $("#withdrawal-account-benefit-warning").prop(
            "hidden",
            selectedAccountBenefit !== "IMPOSSIBLE"
        );
        closeDrawer();
        $("#withdrawal-allocation-title").html(MARIA.fmt.accountNoHtml(account.accountNo) + " · " + escapeHtml(account.customerName));
        $("#withdrawal-allocations").html('<div class="withdrawal-loading">배분 내역을 불러오는 중...</div>');

        var requestedAccountNo = accountNo;
        Promise.all(account.withdrawals.map(function (withdrawal) {
            return loadWithdrawalDetail(withdrawal.withdrawalId);
        })).then(function (details) {
            if (selectedAccountNo !== requestedAccountNo) {
                return;
            }
            details.forEach(function (withdrawal) {
                var allocations = (withdrawal.allocations || []).filter(function (allocation) {
                    if (selectedWithdrawalType === "NORMAL") {
                        return allocation.type === "EARNINGS_ONLY" ||
                            allocation.type === "MATURED_PRINCIPAL_INCLUDED";
                    }
                    if (selectedWithdrawalType === "EARLY") {
                        return allocation.type === "IMMATURE_PRINCIPAL_INCLUDED";
                    }
                    return true;
                });
                var displayedAmount = allocations.reduce(function (sum, allocation) {
                    return sum + Number(allocation.allocatedAmount || 0);
                }, 0);
                var displayedWithdrawal = $.extend({}, withdrawal, {
                    displayedAmount: displayedAmount
                });
                allocations.forEach(function (allocation) {
                    accountAllocationEntries.push({
                        allocation: allocation,
                        withdrawal: displayedWithdrawal
                    });
                });
                if (!allocations.length && withdrawal.status === "FAILED") {
                    accountAllocationEntries.push({
                        allocation: null,
                        withdrawal: displayedWithdrawal
                    });
                }
            });
            sortAccountAllocationEntries();
            renderAccountAllocations();
        }).catch(function (xhr) {
            if (selectedAccountNo !== requestedAccountNo) {
                return;
            }
            accountAllocationEntries = [];
            renderAccountAllocations();
            if (!xhr || xhr.status !== 401) {
                MARIA.ui.showError(
                    (xhr && xhr.responseJSON && xhr.responseJSON.message) ||
                    "계좌의 인출 배분 내역을 불러오지 못했습니다."
                );
            }
        });
    }

    function loadWithdrawals() {
        $("#withdrawal-list").html('<div class="withdrawal-loading">불러오는 중...</div>');
        $("#withdrawal-search-button").prop("disabled", true);

        Promise.all([
            loadApiData("/api/admin/withdrawals"),
            loadApiData("/api/admin/account/list")
        ]).then(function (responses) {
            withdrawalHistories = (responses[0] || []).filter(function (withdrawal) {
                return withdrawal.status !== "CANCELLED";
            });
            accountMetadataByNo = {};
            (responses[1] || []).forEach(function (account) {
                accountMetadataByNo[String(account.accountNo || "-")] = account;
            });
            applyFilters();
        }).catch(function (xhr) {
            withdrawalHistories = [];
            accountMetadataByNo = {};
            accounts = [];
            renderAccounts();
            if (!xhr || xhr.status !== 401) {
                MARIA.ui.showError(
                    (xhr && xhr.responseJSON && xhr.responseJSON.message) ||
                    "인출 내역을 불러오지 못했습니다."
                );
            }
        }).finally(function () {
            $("#withdrawal-search-button").prop("disabled", false);
        });
    }

    function selectFirstAccountOnCurrentPage() {
        var account = accounts[(currentPage - 1) * PAGE_SIZE];
        if (!account) {
            renderAccounts();
            clearDetail();
            return;
        }
        selectAccount(account.accountNo);
    }

    function submitSearch() {
        if (!( $("#withdrawal-keyword").val() || "").trim()) {
            MARIA.ui.showError("고객명 또는 RIA 계좌번호를 입력해 주세요.");
            return;
        }
        applyFilters();
    }

    $("#withdrawal-search-button").on("click", submitSearch);
    $("#withdrawal-keyword").on("keydown", function (event) {
        if (event.key === "Enter") {
            submitSearch();
        }
    });
    $(".withdrawal-type-tab").on("click", function () {
        selectedWithdrawalType = $(this).data("withdrawal-type");
        $(".withdrawal-type-tab").removeClass("active");
        $(this).addClass("active");
        applyFilters();
    });
    $(document).on("click", ".withdrawal-list-item", function () {
        selectAccount(String($(this).attr("data-account-no")));
    });
    $(document).on("click", ".withdrawal-allocation-item", function () {
        selectedAllocationIndex = Number($(this).data("allocation-index"));
        var entry = accountAllocationEntries[selectedAllocationIndex];
        if (!entry) {
            return;
        }
        $(".withdrawal-allocation-item, .withdrawal-unallocated-item")
            .removeClass("is-selected");
        $(this).addClass("is-selected");
        renderWithdrawalDetail(entry.withdrawal, entry.allocation);
    });
    $(document).on("click", ".allocation-sort-option", function () {
        allocationSortDirection = String($(this).data("allocation-sort"));
        $(".allocation-sort-option").removeClass("active");
        $(this).addClass("active");
        currentAllocationPage = 1;
        selectedAllocationIndex = null;
        closeDrawer();
        sortAccountAllocationEntries();
        renderAccountAllocations();
    });
    $(document).on("click", ".withdrawal-unallocated-item", function () {
        selectedAllocationIndex = Number($(this).data("allocation-index"));
        var entry = accountAllocationEntries[selectedAllocationIndex];
        if (!entry) {
            return;
        }
        $(".withdrawal-allocation-item, .withdrawal-unallocated-item")
            .removeClass("is-selected");
        $(this).addClass("is-selected");
        renderUnallocatedWithdrawalDetail(entry.withdrawal);
    });
    $("#withdrawal-drawer-close, #withdrawal-drawer-backdrop").on("click", closeDrawer);
    $(document).on("keydown", function (event) {
        if (event.key === "Escape") {
            closeDrawer();
        }
    });

    var deepLinkAccountNo = MARIA.deeplink.accountNoFromUrl();
    if (deepLinkAccountNo) {
        $("#withdrawal-keyword").val(deepLinkAccountNo);
    }

    loadWithdrawals();
});
