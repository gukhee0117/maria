$(function () {
    var STATUS_CONFIG = [
        { value: "APPLIED", label: "심사대기", trendLabel: "신청", color: "#2563eb", summarySelector: "#appliedAccountCount" },
        { value: "OPENED", label: "개설", trendLabel: "승인", color: "#16a34a", summarySelector: "#openedAccountCount" },
        { value: "REJECTED", label: "반려", trendLabel: "반려", color: "#d69e2e", summarySelector: "#rejectedAccountCount" },
        { value: "CLOSURE_REQUESTED", label: "해지신청", trendLabel: "해지신청", color: "#d53f8c", summarySelector: "#closureRequestedAccountCount" },
        { value: "CLOSED", label: "해지", trendLabel: "해지", color: "#dc2626", summarySelector: "#closedAccountCount" }
    ];
    var KRW_FORMATTER = new Intl.NumberFormat("ko-KR");
    var DATE_TIME_FORMATTER = new Intl.DateTimeFormat("ko-KR", {
        year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"
    });
    var TIME_FORMATTER = new Intl.DateTimeFormat("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: true });
    var accounts = [];
    var accountStatus = "";
    var selectedAccountId = null;
    var selectedClosureRequestId = null;
    var currentPage = 1;
    var PAGE_SIZE = 10;
    var availableLimitRequestIds = {};
    var accountApplicationChart = null;
    var customerSearchTimer = null;
    var customerSearchRequest = null;
    var businessToday = null; // "YYYY-MM-DD" - system_clock 기준(실제 브라우저 시간 아님)

    function escapeHtml(value) {
        return $("<div>").text(value == null ? "" : value).html();
    }

    function loadBusinessToday() {
        return MARIA.auth.ajax({ url: "/api/admin/system-clock", method: "GET" })
            .done(function (res) {
                businessToday = res.data ? res.data.slice(0, 10) : null;
            });
    }

    function formatAmount(amount) {
        return "₩" + KRW_FORMATTER.format(amount || 0);
    }

    function parseLimitAmount(selector) {
        var value = $(selector).val().replace(/,/g, "");
        return /^\d+$/.test(value) ? Number(value) : null;
    }

    function isValidLimitAmount(value) {
        return Number.isInteger(value) && value >= 1 && value <= 50000000;
    }

    function setLimitAmount(selector, amount) {
        $(selector).val(amount == null || amount === "" ? "" : KRW_FORMATTER.format(Number(amount)));
    }

    function formatLimitInput(input) {
        var value = input.value.replace(/,/g, "");
        if (/^\d*$/.test(value)) {
            input.value = value ? KRW_FORMATTER.format(Number(value)) : "";
        }
    }

    function formatDateTime(value) {
        return value ? DATE_TIME_FORMATTER.format(new Date(value)) : "-";
    }

    function formatTableDateTime(value) {
        if (!value) {
            return "-";
        }
        var date = new Date(value);
        var dateText = date.getFullYear() + "." + String(date.getMonth() + 1).padStart(2, "0") + "." + String(date.getDate()).padStart(2, "0");
        return '<span class="account-table-date">' + dateText + "<br>" + TIME_FORMATTER.format(date) + "</span>";
    }

    function statusLabel(status) {
        var config = STATUS_CONFIG.find(function (item) { return item.value === status; });
        return config ? config.label : status || "-";
    }

    function usedAmountOf(account) {
        return Number(account.usedAmount || 0);
    }

    function remainingLimitOf(account) {
        return Number(account.limitAmount || 0) - usedAmountOf(account);
    }

    function showError(message) {
        MARIA.ui.showError(message);
    }

    function canProcessClosure() {
        var admin = MARIA.auth.currentAdmin();
        return !!admin && (admin.role === "ADMIN" || admin.role === "REVIEWER");
    }

    function errorMessage(xhr, fallback) {
        return (xhr.responseJSON && xhr.responseJSON.message) || fallback;
    }

    function handleRequestFailure(xhr, fallback, onFailure) {
        if (xhr.status === 401) {
            return;
        }
        if (onFailure) {
            onFailure();
        }
        showError(errorMessage(xhr, fallback));
    }

    function totalPagesOf(list) {
        return Math.max(1, Math.ceil(list.length / PAGE_SIZE));
    }

    function updateAccountCache(account) {
        var accountIndex = accounts.findIndex(function (item) { return item.accountId === account.accountId; });
        if (accountIndex !== -1) {
            accounts[accountIndex] = $.extend({}, accounts[accountIndex], account);
        }
        return accounts[accountIndex] || account;
    }

    function getSelectedAccount() {
        return accounts.find(function (account) { return account.accountId === selectedAccountId; });
    }

    function reloadSelectedAccount() {
        loadAccounts(function () { selectAccount(selectedAccountId); });
    }

    function updateAvailableLimit(customerId, displaySelector, messages) {
        var requestId = (availableLimitRequestIds[displaySelector] || 0) + 1;
        availableLimitRequestIds[displaySelector] = requestId;
        if (!customerId || Number(customerId) <= 0) {
            $(displaySelector).text(messages.initial);
            return;
        }

        $(displaySelector).text("조회 중...");
        MARIA.auth.ajax({ url: "/api/admin/account/available-limit", method: "GET", data: { customerId: customerId } })
            .done(function (res) {
                if (requestId === availableLimitRequestIds[displaySelector]) {
                    $(displaySelector).text(formatAmount(res.data));
                }
            })
            .fail(function (xhr) {
                if (requestId === availableLimitRequestIds[displaySelector] && xhr.status !== 401) {
                    $(displaySelector).text(messages.initial);
                }
                handleRequestFailure(xhr, messages.error);
            });
    }

    function closeCustomerSearch() {
        $("#customerSearchResults").prop("hidden", true).empty();
        $("#createCustomerName").attr("aria-expanded", "false");
    }

    function clearSelectedCustomer() {
        $("#createCustomerId").val("");
        $("#selectedCustomer").prop("hidden", true).empty();
        $("#availableLimit").text("고객을 선택하세요.");
    }

    function customerSummary(customer) {
        return customer.birthDate + " · " + (customer.maskedPhone || "-") + " · " + customer.investorType + " · ID " + customer.customerId;
    }

    function renderCustomerSearchResults(customers) {
        var $results = $("#customerSearchResults").empty().prop("hidden", false);
        $("#createCustomerName").attr("aria-expanded", "true");
        if (!customers.length) {
            $results.append('<div class="customer-search-message">계좌를 개설할 수 있는 고객이 없습니다.</div>');
            return;
        }
        customers.forEach(function (customer) {
            $("<button>", {
                type: "button",
                class: "customer-search-option",
                role: "option",
                html: "<strong>" + escapeHtml(customer.name) + "</strong><span>" + escapeHtml(customerSummary(customer)) + "</span>"
            }).data("customer", customer).appendTo($results);
        });
    }

    function searchCustomers(name) {
        if (customerSearchRequest) {
            customerSearchRequest.abort();
        }
        $("#customerSearchResults").prop("hidden", false).html('<div class="customer-search-message">검색 중...</div>');
        $("#createCustomerName").attr("aria-expanded", "true");
        customerSearchRequest = MARIA.auth.ajax({
            url: "/api/admin/customers/search",
            method: "GET",
            data: { name: name }
        }).done(function (res) {
            renderCustomerSearchResults(res.data || []);
        }).fail(function (xhr, status) {
            if (status !== "abort") {
                closeCustomerSearch();
                handleRequestFailure(xhr, "고객 검색에 실패했습니다.");
            }
        }).always(function () {
            customerSearchRequest = null;
        });
    }

    function renderSummary() {
        $("#totalAccountCount").text(accounts.length);
        function dateKey(value) {
            if (!value) return "";
            var date = new Date(value);
            return date.getFullYear() + "-" + (date.getMonth() + 1) + "-" + date.getDate();
        }
        var today = businessToday ? new Date(businessToday) : new Date();
        var yesterday = new Date(today);
        yesterday.setDate(today.getDate() - 1);
        var todayKey = dateKey(today);
        var yesterdayKey = dateKey(yesterday);
        function deltaText(status) {
            var scoped = status ? accounts.filter(function (account) { return account.status === status; }) : accounts;
            var todayCount = scoped.filter(function (account) { return dateKey(account.createdAt) === todayKey; }).length;
            var yesterdayCount = scoped.filter(function (account) { return dateKey(account.createdAt) === yesterdayKey; }).length;
            if (yesterdayCount === 0) {
                return { text: "—", cls: "" };
            }
            var rate = (todayCount - yesterdayCount) / yesterdayCount * 100;
            var arrow = rate > 0 ? "▲" : rate < 0 ? "▼" : "-";
            var cls = rate > 0 ? "up" : rate < 0 ? "down" : "";
            return { text: arrow + Math.abs(rate).toFixed(1) + "%", cls: cls };
        }
        function applyDelta(selector, status) {
            var delta = deltaText(status);
            $(selector).text(delta.text).attr("class", delta.cls);
        }
        applyDelta("#totalAccountDelta", "");
        STATUS_CONFIG.forEach(function (status) {
            $(status.summarySelector).text(accounts.filter(function (account) { return account.status === status.value; }).length);
            applyDelta(status.summarySelector.replace("Count", "Delta"), status.value);
        });
    }

    function renderApplicationTrend() {
        var selectedTrendStatus = $("#accountTrendStatusFilter").val();
        var latestCreatedAt = accounts.reduce(function (latest, account) {
            if (!account.createdAt) return latest;
            var createdAt = new Date(account.createdAt);
            return !latest || createdAt > latest ? createdAt : latest;
        }, null);
        var today = latestCreatedAt || new Date();
        today.setHours(0, 0, 0, 0);
        var dates = Array.from({ length: 7 }, function (_, index) {
            var date = new Date(today);
            date.setDate(today.getDate() - 6 + index);
            return date;
        });
        var trendGroups = selectedTrendStatus
            ? STATUS_CONFIG.filter(function (status) { return status.value === selectedTrendStatus; }).map(function (status) {
                return { label: status.trendLabel, statuses: [status.value], color: status.color };
            })
            : [
                { label: "신청·개설", statuses: ["APPLIED", "OPENED"], color: "#2563eb" },
                { label: "반려", statuses: ["REJECTED"], color: "#d69e2e" },
                { label: "해지신청·해지", statuses: ["CLOSURE_REQUESTED", "CLOSED"], color: "#d53f8c" }
            ];
        var datasets = trendGroups.map(function (group) {
            return {
                label: group.label,
                data: dates.map(function (date) {
                    return accounts.filter(function (account) {
                        if (!account.createdAt || group.statuses.indexOf(account.status) === -1) return false;
                        var createdAt = new Date(account.createdAt);
                        createdAt.setHours(0, 0, 0, 0);
                        return createdAt.getTime() === date.getTime();
                    }).length;
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
        $("#accountTrendTotal").text(datasets.reduce(function (total, dataset) { return total + dataset.data.reduce(function (sum, count) { return sum + count; }, 0); }, 0) + "건");
        if (accountApplicationChart) accountApplicationChart.destroy();
        accountApplicationChart = new Chart($("#accountApplicationTrend")[0], {
            type: "line",
            data: { labels: labels, datasets: datasets },
            options: {
                animation: false,
                maintainAspectRatio: false,
                plugins: { legend: { display: false }, tooltip: { displayColors: false, callbacks: { label: function (context) { return context.dataset.label + ": " + context.parsed.y + "건"; } } } },
                scales: { x: { grid: { display: false }, ticks: { color: "#64748b", font: { size: 10 } } }, y: { beginAtZero: true, ticks: { precision: 0, color: "#64748b", font: { size: 10 } }, grid: { color: "#e2e8f0" } } }
            }
        });
    }

    function filteredAccounts() {
        var customerId = ($("#accountCustomerIdSearch").val() || "").trim();
        var customerName = ($("#accountCustomerNameSearch").val() || "").trim().toLowerCase();
        var accountNo = ($("#accountNoSearch").val() || "").trim().toLowerCase();
        var status = accountStatus;
        return accounts.filter(function (account) {
            var matchesStatus = !status || account.status === status;
            var matchesCustomerId = !customerId || String(account.customerId || "") === customerId;
            var matchesCustomerName = !customerName
                || String(account.customerName || "").toLowerCase().includes(customerName);
            var matchesAccountNo = !accountNo
                || String(account.accountNo || "").toLowerCase().includes(accountNo);
            return matchesStatus && matchesCustomerId && matchesCustomerName && matchesAccountNo;
        });
    }

    function renderAccounts() {
        var $body = $("#accountListBody").empty();
        var list = filteredAccounts();
        var totalPages = totalPagesOf(list);
        currentPage = Math.min(currentPage, totalPages);
        var startIndex = (currentPage - 1) * PAGE_SIZE;
        var pageAccounts = list.slice(startIndex, startIndex + PAGE_SIZE);

        if (!list.length) {
            $body.append('<tr><td colspan="7" class="account-empty">조회된 계좌가 없습니다.</td></tr>');
            $("#accountPagination").hide();
            return;
        }

        pageAccounts.forEach(function (account) {
            var statusClass = (account.status || "").toLowerCase().replace(/_/g, "-");
            var selectedClass = account.accountId === selectedAccountId ? " is-selected" : "";
            $body.append(
                '<tr class="account-row' + selectedClass + '" data-account-id="' + account.accountId + '">' +
                '<td><div class="account-number">' + MARIA.fmt.hyphenateAccountNo(account.accountNo) + '</div>' +
                '<div class="account-customer-id">' + escapeHtml(account.customerName || "고객 ID " + account.customerId) + ' · 고객 ID ' + escapeHtml(account.customerId) + '</div></td>' +
                '<td><span class="account-status-badge ' + statusClass + '">' + escapeHtml(statusLabel(account.status)) + '</span></td>' +
                '<td class="account-amount">' + formatAmount(account.limitAmount) + '</td>' +
                '<td class="account-amount">' + formatAmount(account.amount) + '</td>' +
                '<td>' + escapeHtml(account.benefit || "-") + '</td>' +
                '<td>' + formatTableDateTime(account.createdAt) + '</td>' +
                '<td>' + formatTableDateTime(account.openedAt) + '</td>' +
                '</tr>'
            );
        });
        var $pagination = $("#accountPagination").empty();
        var blockStart = Math.floor((currentPage - 1) / 10) * 10 + 1;
        var blockEnd = Math.min(totalPages, blockStart + 9);
        function addPageButton(label, page, disabled, active) {
            $("<button>", {
                type: "button",
                class: "page-btn" + (active ? " active" : ""),
                text: label,
                disabled: disabled || active
            }).data("page", page).appendTo($pagination);
        }
        addPageButton("이전", Math.max(1, blockStart - 1), blockStart === 1, false);
        for (var page = blockStart; page <= blockEnd; page += 1) {
            addPageButton(String(page), page, false, page === currentPage);
        }
        addPageButton("다음", Math.min(totalPages, blockEnd + 1), blockEnd === totalPages, false);
        $pagination.css("display", "flex");
    }

    function renderDetail(account) {
        if (!account) {
            $("#accountDetail").hide();
            return;
        }
        $("#accountDetailModal").addClass("is-open").attr("aria-hidden", "false");
        $("#accountDetailBackdrop").prop("hidden", false);
        $("#accountDetail").show();
        $("body").addClass("account-modal-open");
        $("#detailAccountNo").html(MARIA.fmt.accountNoHtml(account.accountNo));
        $("#detailCustomerId").text(account.customerId || "-");
        $("#detailStatus").text(statusLabel(account.status));
        $("#detailLimitAmount").text(formatAmount(account.limitAmount));
        var usedAmount = usedAmountOf(account);
        $("#detailUsedAmount").text(formatAmount(usedAmount));
        $("#detailRemainingLimit").text(formatAmount(remainingLimitOf(account)));
        $("#detailAmount").text(formatAmount(account.amount));
        $("#detailBenefit").text(account.benefit || "-");
        $("#detailOpenedAt").text(formatDateTime(account.openedAt));
        $("#approveAccount, #rejectAccount").toggle(account.status === "APPLIED");
        $("#openLimitModal").toggle(account.status === "APPLIED" || account.status === "OPENED");
        $("#rejectionActions").toggle(account.status === "APPLIED");
        $("#openReapplyModal").toggle(account.status === "REJECTED");
        $("#overrideActions").toggle(account.status === "REJECTED");
    }

    function renderRelatedList(selector, items, renderer, emptyMessage) {
        var $list = $(selector).empty();
        if (!items || !items.length) {
            $list.append('<div class="account-related-empty">' + escapeHtml(emptyMessage) + '</div>');
            return;
        }
        items.forEach(function (item) { $list.append(renderer(item)); });
    }

    function renderClosureDetail(closure) {
        selectedClosureRequestId = closure.closureRequestId || null;
        var closureStatusLabels = {
            REQUESTED: "해지 신청",
            COMPLETED: "해지 완료",
            REJECTED: "반려"
        };
        var immatureLabel = closure.status === "COMPLETED" ? "실제 미경과 인출액" : "현재 미경과 원금";
        var immatureValue = closure.hasImmaturePrincipal
            ? formatAmount(closure.immaturePrincipalAmount) + (closure.status === "COMPLETED" ? "" : " 보유")
            : "없음";
        var taxImpactLabel = closure.status === "COMPLETED" ? "세제혜택 처리 결과" : "세제혜택 예상 영향";
        var taxImpactValue = "영향 없음";
        if (closure.status === "COMPLETED") {
            taxImpactValue = closure.taxBenefitCancellationOccurred ? "전체 취소 발생" : "취소 없음";
        } else if (closure.status === "REJECTED") {
            taxImpactValue = "취소 없음";
        } else if (closure.taxBenefitCancellationExpected) {
            taxImpactValue = "승인 시 전체 취소";
        }

        var rows =
            '<div class="account-related-item"><strong>처리 상태</strong><span>' + escapeHtml(closureStatusLabels[closure.status] || closure.status || "-") + '</span></div>' +
            '<div class="account-related-item"><strong>인출 목적지 일반계좌</strong><span>' + escapeHtml(closure.destinationGeneralAccountId || "-") + '</span></div>' +
            '<div class="account-related-item"><strong>신청 시각</strong><span>' + escapeHtml(formatDateTime(closure.requestedAt)) + '</span></div>' +
            '<div class="account-related-item"><strong>조기인출 동의</strong><span>' + (closure.earlyWithdrawalAgreed ? "동의함" : "동의하지 않음") + '</span></div>' +
            '<div class="account-related-item"><strong>' + immatureLabel + '</strong><span>' + escapeHtml(immatureValue) + '</span></div>' +
            '<div class="account-related-item"><strong>' + taxImpactLabel + '</strong><span>' + escapeHtml(taxImpactValue) + '</span></div>';
        if (closure.processedAt) {
            rows += '<div class="account-related-item"><strong>처리 시각</strong><span>' + escapeHtml(formatDateTime(closure.processedAt)) + '</span></div>';
        }
        if (closure.rejectionReason) {
            rows += '<div class="account-related-item"><strong>반려 사유</strong><span>' + escapeHtml(closure.rejectionReason) + '</span></div>';
        }
        $("#detailClosure").html(rows);
        $("#closureReviewActions").prop(
            "hidden",
            !(closure.status === "REQUESTED" && canProcessClosure())
        );
    }

    function loadClosureDetail(accountId, closureRequestId, closureSummary) {
        MARIA.auth.ajax({ url: "/api/admin/account-closures/" + closureRequestId, method: "GET" })
            .done(function (res) {
                if (selectedAccountId !== accountId) return;
                renderClosureDetail($.extend({}, closureSummary, res.data || {}));
            });
    }

    function loadManagementDetail(accountId) {
        selectedClosureRequestId = null;
        $("#closureReviewActions").prop("hidden", true);
        $("#closureRejectionReason").val("");
        $("#detailHoldings, #detailInbounds, #detailClosure, #detailWithdrawals").html('<div class="account-related-empty">불러오는 중...</div>');
        MARIA.auth.ajax({ url: "/api/admin/account/" + accountId + "/management-detail", method: "GET" })
            .done(function (res) {
                if (selectedAccountId !== accountId) return;
                var detail = res.data || {};
                renderRelatedList("#detailHoldings", detail.holdings, function (item) {
                    return '<div class="account-related-item"><strong>' + escapeHtml(item.ticker) + '</strong><span>' + escapeHtml(item.productName) + ' · ' + escapeHtml(item.currentQty) + '주</span></div>';
                }, "보유주식이 없습니다.");
                renderRelatedList("#detailInbounds", detail.inbounds, function (item) {
                    return '<div class="account-related-item"><strong>' + escapeHtml(item.ticker) + ' ' + escapeHtml(item.qty) + '주</strong><span>' + escapeHtml(item.sourceBroker || "-") + ' · ' + formatDateTime(item.recordedAt) + '</span></div>';
                }, "입고주식이 없습니다.");
                renderRelatedList("#detailClosure", detail.closure ? [detail.closure] : [], function (item) {
                    return '<div class="account-related-item"><strong>' + escapeHtml(item.status) + '</strong><span>신청 ' + formatDateTime(item.requestedAt) + (item.rejectionReason ? ' · ' + escapeHtml(item.rejectionReason) : '') + '</span></div>';
                }, "해지 신청 이력이 없습니다.");
                if (detail.closure && detail.closure.closureRequestId) {
                    loadClosureDetail(accountId, detail.closure.closureRequestId, detail.closure);
                }
                renderRelatedList("#detailWithdrawals", detail.withdrawals, function (item) {
                    return '<div class="account-related-item"><strong>' + formatAmount(item.requestedAmount) + '</strong><span>' + escapeHtml(item.status) + ' · ' + formatDateTime(item.processedAt) + '</span></div>';
                }, "인출 이력이 없습니다.");
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "계좌 관련 내역을 불러오지 못했습니다.");
            });
    }

    function closeAccountDetail() {
        $("#accountDetailModal").removeClass("is-open").attr("aria-hidden", "true");
        $("#accountDetailBackdrop").prop("hidden", true);
        $("body").removeClass("account-modal-open");
    }

    function openLimitModal() {
        var account = getSelectedAccount();
        if (!account || (account.status !== "APPLIED" && account.status !== "OPENED")) {
            return;
        }
        var usedAmount = usedAmountOf(account);
        $("#modalCurrentLimit").text(formatAmount(account.limitAmount));
        $("#modalUsedAmount").text(formatAmount(usedAmount));
        $("#modalRemainingLimit").text(formatAmount(remainingLimitOf(account)));
        setLimitAmount("#modalNewLimitAmount", account.limitAmount);
        $("#accountLimitModal").css("display", "flex");
        updateAvailableLimit(account.customerId, "#modalAvailableLimit", { initial: "조회 실패", error: "설정 가능 한도를 조회할 수 없습니다." });
    }

    function closeModal(modalSelector) {
        $(modalSelector).hide();
    }

    function openReapplyModal() {
        var account = getSelectedAccount();
        if (!account || account.status !== "REJECTED") {
            return;
        }
        $("#reapplyCurrentLimit").text(formatAmount(account.limitAmount));
        setLimitAmount("#modalReapplyLimitAmount", account.limitAmount);
        $("#accountReapplyModal").css("display", "flex");
        updateAvailableLimit(account.customerId, "#reapplyAvailableLimit", { initial: "조회 실패", error: "설정 가능 한도를 조회할 수 없습니다." });
    }

    function loadStatusLogs(accountId) {
        var $list = $("#accountHistoryList").empty().append('<li class="account-loading">불러오는 중...</li>');
        MARIA.auth.ajax({ url: "/api/admin/account/" + accountId + "/status-logs", method: "GET" })
            .done(function (res) {
                if (selectedAccountId !== accountId) {
                    return;
                }
                $list.empty();
                var logs = res.data || [];
                if (!logs.length) {
                    $list.append('<li class="account-empty">상태 이력이 없습니다.</li>');
                    return;
                }
                logs.forEach(function (log) {
                    $list.append(
                        '<li class="account-history-item">' +
                        '<span class="account-history-time">' + formatDateTime(log.changedAt) + '</span>' +
                        '<span class="account-history-reason">' + escapeHtml(log.reason || "-") + '</span>' +
                        '<span class="account-history-status">' + escapeHtml(statusLabel(log.prevStatus)) + ' → ' + escapeHtml(statusLabel(log.newStatus)) + '</span>' +
                        '</li>'
                    );
                });
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "상태 이력을 불러오지 못했습니다.", function () { $list.empty(); });
            });
    }

    function selectAccount(accountId) {
        var requestedAccountId = Number(accountId);
        selectedAccountId = requestedAccountId;
        renderAccounts();
        MARIA.auth.ajax({ url: "/api/admin/account/" + requestedAccountId, method: "GET" })
            .done(function (res) {
                if (selectedAccountId !== requestedAccountId) {
                    return;
                }
                var account = updateAccountCache(res.data);
                renderAccounts();
                renderDetail(account);
                loadStatusLogs(account.accountId);
                loadManagementDetail(account.accountId);
            })
            .fail(function (xhr) {
                if (selectedAccountId === requestedAccountId) {
                    handleRequestFailure(xhr, "계좌 정보를 불러오지 못했습니다.");
                }
            });
    }

    function loadAccounts(afterLoad) {
        $("#accountListBody").html('<tr><td colspan="7" class="account-loading">불러오는 중...</td></tr>');
        MARIA.auth.ajax({ url: "/api/admin/account/list", method: "GET" })
            .done(function (res) {
                accounts = res.data || [];
                renderSummary();
                renderApplicationTrend();
                renderAccounts();
                if (afterLoad) {
                    afterLoad();
                }
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "계좌 목록을 불러오지 못했습니다.", function () { $("#accountListBody").empty(); });
            });
    }

    function submitReview(action) {
        if (!selectedAccountId) {
            return;
        }
        var reason = $("#accountReason").val().trim();
        if (action === "reject" && !reason) {
            showError("사유를 입력해 주세요.");
            return;
        }

        var options = { url: "/api/admin/account/" + selectedAccountId + "/" + action, method: "POST" };
        if (action === "reject") {
            options.contentType = "application/json";
            options.data = JSON.stringify({ reason: reason });
        }
        MARIA.auth.ajax(options)
            .done(function () {
                $("#accountReason").val("");
                reloadSelectedAccount();
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "계좌 상태 변경에 실패했습니다.");
            });
    }

    function submitRecovery(action, payload) {
        if (!selectedAccountId) {
            return;
        }
        MARIA.auth.ajax({
            url: "/api/admin/account/" + selectedAccountId + "/" + action,
            method: "POST",
            contentType: "application/json",
            data: JSON.stringify(payload)
        })
            .done(function () {
                $("#overrideReason").val("");
                reloadSelectedAccount();
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "요청 처리에 실패했습니다.");
            });
    }

    function submitClosureReview(action) {
        if (!selectedClosureRequestId || !canProcessClosure()) return;

        var reason = $("#closureRejectionReason").val().trim();
        if (action === "reject" && !reason) {
            showError("반려 사유를 입력해 주세요.");
            return;
        }

        var options = {
            url: "/api/admin/account-closures/" + selectedClosureRequestId + "/" + action,
            method: "POST"
        };
        if (action === "reject") {
            options.contentType = "application/json";
            options.data = JSON.stringify({ reason: reason });
        }

        $("#approveClosure, #rejectClosure").prop("disabled", true);
        MARIA.auth.ajax(options)
            .done(function () {
                $("#closureRejectionReason").val("");
                reloadSelectedAccount();
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "계좌 해지 신청 처리에 실패했습니다.");
            })
            .always(function () {
                $("#approveClosure, #rejectClosure").prop("disabled", false);
            });
    }

    function submitForm($form, options) {
        if (!$form[0].checkValidity()) {
            showError("입력값을 확인해 주세요.");
            return;
        }
        MARIA.auth.ajax(options)
            .done(function () {
                $form[0].reset();
                if ($form.is("#accountCreateForm")) {
                    clearSelectedCustomer();
                    closeCustomerSearch();
                }
                loadAccounts();
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "요청 처리에 실패했습니다.");
            });
    }

    $(document).on("click", ".account-row", function () { selectAccount($(this).data("account-id")); });
    $(document).on("click", "#accountPagination .page-btn", function () {
        if (this.disabled) return;
        currentPage = Number($(this).data("page"));
        renderAccounts();
    });
    $(document).on("click", ".account-summary-card", function () {
        var status = $(this).data("status") || "";
        accountStatus = status;
        $(".account-summary-card").removeClass("is-active");
        $(this).addClass("is-active");
        currentPage = 1;
        renderAccounts();
    });
    $(document).on("click", ".account-detail-link-btn", function () {
        var account = getSelectedAccount();
        if (!account || !account.accountNo) return;
        window.location.href = $(this).data("target") + "?accountNo=" + encodeURIComponent(account.accountNo);
    });
    $(document).on("input", ".account-currency-input", function () { formatLimitInput(this); });
    $("#accountCustomerIdSearch, #accountCustomerNameSearch, #accountNoSearch").on("input", function () {
        currentPage = 1;
        renderAccounts();
    });
    $("#accountTrendStatusFilter").on("change", function () {
        renderApplicationTrend();
    });
    $("#closeAccountDetail, #accountDetailBackdrop").on("click", closeAccountDetail);
    $(document).on("keydown", function (event) {
        if (event.key === "Escape" && $("#accountDetailModal").hasClass("is-open")) {
            closeAccountDetail();
        }
    });
    $("#createCustomerName").on("input", function () {
        var name = $(this).val().trim();
        clearSelectedCustomer();
        clearTimeout(customerSearchTimer);
        if (!name) {
            closeCustomerSearch();
            return;
        }
        customerSearchTimer = setTimeout(function () { searchCustomers(name); }, 250);
    }).on("keydown", function (event) {
        if (event.key === "ArrowDown") {
            event.preventDefault();
            $("#customerSearchResults .customer-search-option").first().trigger("focus");
        } else if (event.key === "Escape") {
            closeCustomerSearch();
        }
    });
    $(document).on("click", ".customer-search-option", function () {
        var customer = $(this).data("customer");
        $("#createCustomerName").val(customer.name);
        $("#createCustomerId").val(customer.customerId);
        $("#selectedCustomer").prop("hidden", false).text(customer.name + " · " + customerSummary(customer));
        closeCustomerSearch();
        updateAvailableLimit(customer.customerId, "#availableLimit", {
            initial: "고객을 선택하세요.",
            error: "사용 가능한 한도를 조회할 수 없습니다."
        });
    });
    $(document).on("click", function (event) {
        if (!$(event.target).closest(".customer-search-field").length) {
            closeCustomerSearch();
        }
    });
    $("#approveAccount").on("click", function () { submitReview("approve"); });
    $("#rejectAccount").on("click", function () { submitReview("reject"); });
    $("#approveClosure").on("click", function () { submitClosureReview("approve"); });
    $("#rejectClosure").on("click", function () { submitClosureReview("reject"); });
    $("#openReapplyModal").on("click", openReapplyModal);
    $("#closeReapplyModal, #cancelReapplyModal, #accountReapplyModal .account-modal-backdrop").on("click", function () { closeModal("#accountReapplyModal"); });
    $("#accountReapplyModalForm").on("submit", function (event) {
        event.preventDefault();
        var limitAmount = parseLimitAmount("#modalReapplyLimitAmount");
        if (!this.checkValidity() || !isValidLimitAmount(limitAmount)) {
            showError("재신청 한도는 1원 이상 5천만원 이하의 정수여야 합니다.");
            return;
        }
        closeModal("#accountReapplyModal");
        submitRecovery("reapply", { limitAmount: limitAmount });
    });
    $("#overrideAccount").on("click", function () {
        var reason = $("#overrideReason").val().trim();
        if (!reason) {
            showError("오버라이드 사유를 입력해 주세요.");
            return;
        }
        submitRecovery("override", { reason: reason });
    });
    $("#openLimitModal").on("click", openLimitModal);
    $("#closeLimitModal, #cancelLimitModal, #accountLimitModal .account-modal-backdrop").on("click", function () { closeModal("#accountLimitModal"); });
    $("#accountLimitModalForm").on("submit", function (event) {
        event.preventDefault();
        var account = getSelectedAccount();
        var limitAmount = parseLimitAmount("#modalNewLimitAmount");
        if (!account || !this.checkValidity() || !isValidLimitAmount(limitAmount)) {
            showError("입력값을 확인해 주세요.");
            return;
        }
        MARIA.auth.ajax({
            url: "/api/admin/account/update/limit",
            method: "PUT",
            contentType: "application/json",
            data: JSON.stringify({ customerId: account.customerId, expectedCurrentLimit: Number(account.limitAmount), limitAmount: limitAmount })
        }).done(function () {
            closeModal("#accountLimitModal");
            reloadSelectedAccount();
        }).fail(function (xhr) {
            handleRequestFailure(xhr, "계좌 한도가 변경되었습니다. 다시 조회 후 시도해주세요.");
        });
    });
    $("#accountCreateForm").on("submit", function (event) {
        event.preventDefault();
        var $form = $(this);
        var limitAmount = parseLimitAmount("#createLimitAmount");
        var customerId = Number($("#createCustomerId").val());
        if (!customerId) {
            showError("검색 결과에서 고객을 선택해 주세요.");
            return;
        }
        if (!isValidLimitAmount(limitAmount)) {
            showError("계좌의 한도는 1원 이상 5천만원 이하의 정수여야 합니다.");
            return;
        }
        submitForm($form, {
            url: "/api/admin/account/applications",
            method: "POST",
            contentType: "application/json",
            data: JSON.stringify({
                customerId: customerId,
                limitAmount: limitAmount
            })
        });
    });

    loadBusinessToday().done(function () {
        renderSummary();
    });
    loadAccounts();
});
