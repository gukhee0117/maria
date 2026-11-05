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
    var accounts = [];
    var accountStatus = "";
    var selectedAccountId = null;
    var selectedClosureRequestId = null;
    var pendingReviewAction = null;
    var currentPage = 1;
    var PAGE_SIZE = 20;
    var sortKey = "createdAt";
    var sortDirection = -1;
    var availableLimitRequestIds = {};
    var accountApplicationChart = null;
    var customerSearchTimer = null;
    var customerSearchRequest = null;
    var businessToday = null; // "YYYY-MM-DD" - system_clock 기준(실제 브라우저 시간 아님)
    var deepLinkHandled = false;

    function escapeHtml(value) {
        return $("<div>").text(value == null ? "" : value).html();
    }

    function loadBusinessToday() {
        return MARIA.auth.ajax({ url: "/api/admin/system-clock", method: "GET" })
            .done(function (res) {
                businessToday = res.data ? res.data.slice(0, 10) : null;
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "업무일자를 불러오지 못했습니다.");
            });
    }

    function loadPendingAccountCount() {
        return MARIA.auth.ajax({
            url: "/api/admin/account/requiring-action-count",
            method: "GET"
        }).done(function (res) {
            var count = res.data == null ? null : Number(res.data);
            $("#accountSummaryPending").text(count == null ? "-" : count);
            $("#accountReviewBadge").text(count == null ? "" : count).toggle(count != null && count > 0);
            if (MARIA.ui.refreshSideBadges) {
                MARIA.ui.refreshSideBadges();
            }
        }).fail(function (xhr) {
            $("#accountSummaryPending").text("-");
            $("#accountReviewBadge").text("").hide();
            handleRequestFailure(xhr, "처리 대기 건수를 불러오지 못했습니다.");
        });
    }

    function formatAmount(amount) {
        return "₩" + KRW_FORMATTER.format(amount || 0);
    }

    function formatDetailAmount(amount) {
        return amount == null ? "-" : KRW_FORMATTER.format(Number(amount)) + "원";
    }

    function formatQuantity(quantity) {
        if (quantity == null) return "-";
        var value = Number(quantity);
        return Number.isFinite(value)
            ? new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 8 }).format(value)
            : String(quantity);
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

    function formatListDateTime(value) {
        if (!value) return "-";
        var date = new Date(value);
        if (isNaN(date.getTime())) return "-";
        return date.getFullYear() + "-" + String(date.getMonth() + 1).padStart(2, "0") + "-" +
            String(date.getDate()).padStart(2, "0") + " " +
            String(date.getHours()).padStart(2, "0") + ":" + String(date.getMinutes()).padStart(2, "0");
    }

    function statusLabel(status) {
        return MARIA.status.accountStatusLabel(status);
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
        return MARIA.auth.hasRole("REVIEWER");
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
        function dateKey(value) { return value ? String(value).slice(0, 10) : ""; }
        var todayKey = businessToday || "";
        var today = todayKey ? new Date(todayKey + "T12:00:00") : null;
        var weekStart = today ? new Date(today) : null;
        if (weekStart) {
            weekStart.setDate(weekStart.getDate() - ((weekStart.getDay() + 6) % 7));
        }
        var weekStartKey = weekStart
            ? weekStart.getFullYear() + "-" + String(weekStart.getMonth() + 1).padStart(2, "0") + "-" + String(weekStart.getDate()).padStart(2, "0")
            : "";
        var todayNew = accounts.filter(function (account) {
            return todayKey && dateKey(account.createdAt) === todayKey;
        }).length;
        var opened = accounts.filter(function (account) { return account.status === "OPENED"; }).length;
        var rejectedThisWeek = accounts.filter(function (account) {
            var appliedDate = dateKey(account.createdAt);
            return account.status === "REJECTED" && weekStartKey && appliedDate >= weekStartKey && appliedDate <= todayKey;
        }).length;
        $("#accountSummaryTodayNew").text(todayKey ? todayNew : "-");
        $("#accountSummaryOpened").text(opened);
        $("#accountSummaryRejected").text(todayKey ? rejectedThisWeek : "-");
        function deltaText(status) {
            var scoped = status ? accounts.filter(function (account) { return account.status === status; }) : accounts;
            var yesterday = today ? new Date(today) : null;
            if (yesterday) yesterday.setDate(yesterday.getDate() - 1);
            var yesterdayKey = yesterday
                ? yesterday.getFullYear() + "-" + String(yesterday.getMonth() + 1).padStart(2, "0") + "-" + String(yesterday.getDate()).padStart(2, "0")
                : "";
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
        var keyword = ($("#accountCustomerNameSearch").val() || "").trim().toLowerCase();
        var keywordDigits = keyword.replace(/\D/g, "");
        var accountNoQuery = /^[\d\s-]+$/.test(keyword);
        var status = accountStatus || $("#accountFilterStatus").val() || "";
        var list = accounts.filter(function (account) {
            var matchesStatus = !status || account.status === status;
            var accountNo = String(account.accountNo || "").toLowerCase();
            var searchable = [
                account.customerName,
                account.customerId
            ].join(" ").toLowerCase();
            var matchesKeyword = !keyword || searchable.includes(keyword) || accountNo.includes(keyword)
                || (accountNoQuery && keywordDigits && accountNo.replace(/\D/g, "").includes(keywordDigits));
            return matchesStatus && matchesKeyword;
        });
        return list.sort(function (a, b) {
            var left = a[sortKey];
            var right = b[sortKey];
            if (sortKey === "createdAt" || sortKey === "openedAt") {
                left = left ? new Date(left).getTime() : 0;
                right = right ? new Date(right).getTime() : 0;
            } else if (sortKey === "limitAmount" || sortKey === "amount") {
                left = Number(left || 0);
                right = Number(right || 0);
            } else {
                left = String(left || "").toLowerCase();
                right = String(right || "").toLowerCase();
            }
            if (left < right) return -1 * sortDirection;
            if (left > right) return sortDirection;
            return 0;
        });
    }

    function loadingRowsHtml() {
        var html = "";
        for (var row = 0; row < 5; row += 1) {
            html += '<tr class="adm-skel-row">';
            for (var column = 0; column < 9; column += 1) {
                html += '<td class="adm-skel-cell"><span></span></td>';
            }
            html += "</tr>";
        }
        return html;
    }

    function renderAccounts() {
        var $body = $("#accountListBody").empty();
        var list = filteredAccounts();
        $("#accountListCount").text(list.length);
        var totalPages = totalPagesOf(list);
        currentPage = Math.min(currentPage, totalPages);
        var startIndex = (currentPage - 1) * PAGE_SIZE;
        var pageAccounts = list.slice(startIndex, startIndex + PAGE_SIZE);

        if (!list.length) {
            $body.append(
                '<tr class="is-state"><td colspan="9"><div class="adm-state-empty">' +
                '<strong class="adm-state-empty-title">조회된 계좌가 없습니다.</strong>' +
                '<span class="adm-state-empty-desc">검색 조건을 변경하거나 초기화해 주세요.</span>' +
                '<button type="button" class="adm-btn account-reset-empty">조건 초기화</button>' +
                '</div></td></tr>'
            );
            $("#accountPagination").empty();
            return;
        }

        pageAccounts.forEach(function (account, index) {
            var selectedClass = account.accountId === selectedAccountId ? " is-selected" : "";
            var statusTone = MARIA.status.accountStatusTone(account.status);
            $body.append(
                '<tr class="is-clickable account-row' + selectedClass + '" data-account-id="' + account.accountId + '">' +
                '<td class="adm-col-no">' + (startIndex + index + 1) + '</td>' +
                '<td><span>' + escapeHtml(MARIA.fmt.hyphenateAccountNo(account.accountNo)) + '</span></td>' +
                '<td><span>' + escapeHtml(account.customerName || "고객 ID " + account.customerId) + '</span></td>' +
                '<td class="is-c"><span class="adm-chip-' + statusTone + '">' + escapeHtml(statusLabel(account.status)) + '</span></td>' +
                '<td class="is-r">' + KRW_FORMATTER.format(Number(account.limitAmount || 0)) + '</td>' +
                '<td class="is-r">' + KRW_FORMATTER.format(Number(account.amount || 0)) + '</td>' +
                '<td class="is-c"><span class="adm-chip-off">' + escapeHtml(MARIA.status.benefitLabel(account.benefit)) + '</span></td>' +
                '<td>' + escapeHtml(formatListDateTime(account.createdAt)) + '</td>' +
                '<td>' + escapeHtml(formatListDateTime(account.openedAt)) + '</td>' +
                '</tr>'
            );
        });
        var $pagination = $("#accountPagination").empty();
        var blockStart = Math.floor((currentPage - 1) / 10) * 10 + 1;
        var blockEnd = Math.min(totalPages, blockStart + 9);
        function addPageButton(label, page, disabled, active) {
            $("<button>", {
                type: "button",
                class: "adm-page-btn page-btn" + (active ? " is-active active" : "")
                    + ((label === "‹" || label === "›") ? " is-nav" : ""),
                text: label,
                disabled: disabled || active
            }).data("page", page).appendTo($pagination);
        }
        addPageButton("‹", Math.max(1, currentPage - 1), currentPage === 1, false);
        for (var page = blockStart; page <= blockEnd; page += 1) {
            addPageButton(String(page), page, false, page === currentPage);
        }
        addPageButton("›", Math.min(totalPages, currentPage + 1), currentPage === totalPages, false);
        $(".account-sort").each(function () {
            var key = $(this).data("sort");
            $(this).attr("aria-sort", key === sortKey ? (sortDirection > 0 ? "ascending" : "descending") : "none");
            $(this).toggleClass("is-sorted", key === sortKey)
                .attr("data-direction", key === sortKey ? (sortDirection > 0 ? "asc" : "desc") : "");
        });
    }

    function renderDetail(account) {
        if (!account) {
            $("#accountDetail").css("display", "");
            return;
        }
        $("#accountDetailModal").addClass("is-open").attr("aria-hidden", "false");
        $("#accountDetailBackdrop").prop("hidden", false).addClass("is-open");
        $("#accountDetail").css("display", "");
        $("body").addClass("account-modal-open");
        $("#detailCustomerName").text("[1101] " + (account.customerName ? account.customerName + " 님 계좌" : "계좌 상세"));
        $("#detailAccountNo").html(MARIA.fmt.accountNoHtml(account.accountNo));
        $("#detailCustomerId").text(account.customerId || "-");
        var statusTone = MARIA.status.accountStatusTone(account.status);
        $("#detailStatus").text(statusLabel(account.status))
            .removeClass("adm-chip-ok adm-chip-info adm-chip-warn adm-chip-fail adm-chip-off")
            .addClass("adm-chip-" + statusTone);
        $("#detailBenefit").text(MARIA.status.benefitLabel(account.benefit));
        $("#detailCreatedAt").text(formatListDateTime(account.createdAt));
        $("#detailOpenedAt").text(formatDateTime(account.openedAt));
        $("#detailAmount").text(formatDetailAmount(account.amount));
        $("#detailLimitAmount").text(formatDetailAmount(account.limitAmount));
        var usedAmount = usedAmountOf(account);
        var limitAmount = Number(account.limitAmount || 0);
        var usageRatio = limitAmount > 0 ? Math.max(0, Math.min(1, usedAmount / limitAmount)) : 0;
        $("#detailUsedAmount").text(formatDetailAmount(usedAmount));
        $("#detailRemainingLimit").text("잔여 " + formatDetailAmount(remainingLimitOf(account)));
        $("#detailLimitGaugeTotal").text(formatDetailAmount(account.limitAmount));
        $("#detailLimitGaugeFill").css("width", (usageRatio * 100) + "%");
        var canReview = MARIA.auth.hasRole("REVIEWER");
        var reviewableAccount = account.status === "APPLIED" || account.status === "OPENED" || account.status === "REJECTED";
        $("#accountReviewControls").prop("hidden", !canReview || !reviewableAccount);
        $("#accountDetailModal .account-detail-foot").toggle(canReview && reviewableAccount);
        $("#approveAccount, #rejectAccount").prop("hidden", !canReview || account.status !== "APPLIED");
        $("#openLimitModal").prop("hidden", !canReview || (account.status !== "APPLIED" && account.status !== "OPENED"));
        $("#rejectionActions").prop("hidden", !canReview || account.status !== "APPLIED");
        $("#openReapplyModal").prop("hidden", !canReview || account.status !== "REJECTED");
        $("#overrideActions").prop("hidden", !canReview || account.status !== "REJECTED");
        $("#overrideAccount").prop("hidden", !canReview || account.status !== "REJECTED");
        selectDetailTab("holdings");
    }

    function selectDetailTab(tab) {
        $("[data-account-tab]").each(function () {
            var active = $(this).data("account-tab") === tab;
            $(this).toggleClass("is-on", active).attr("aria-selected", String(active));
        });
        $(".account-tab-panel").prop("hidden", true);
        $("#accountTab" + tab.charAt(0).toUpperCase() + tab.slice(1)).prop("hidden", false);
    }

    function openAccountActionModal(action) {
        var account = getSelectedAccount();
        if (!account || !MARIA.auth.hasRole("REVIEWER")) return;
        if (action === "approve" && account.status !== "APPLIED") return;
        if (action === "reject" && account.status !== "APPLIED") return;
        if (action === "override" && account.status !== "REJECTED") return;
        pendingReviewAction = action;
        var titles = {
            approve: ["계좌 개설 승인", "계좌 개설을 승인할까요?"],
            reject: ["계좌 신청 반려", "계좌 신청을 반려할까요?"],
            override: ["오버라이드 승인", "심사 결과와 관계없이 개설을 승인할까요? 사유는 감사 로그에 남아요."]
        };
        $("#accountActionModalTitle").text(titles[action][0]);
        $("#accountActionDescription").text(titles[action][1]);
        $("#rejectionActions").prop("hidden", action !== "reject");
        $("#overrideActions").prop("hidden", action !== "override");
        $("#accountActionModal").prop("hidden", false);
        $("#accountActionModal").find(action === "reject" ? "#accountReason" :
            action === "override" ? "#overrideReason" : "#accountActionConfirmButton").trigger("focus");
    }

    function closeAccountActionModal(clearReasons) {
        $("#accountActionModal").prop("hidden", true);
        pendingReviewAction = null;
        if (clearReasons) {
            $("#accountReason, #overrideReason").val("");
        }
    }

    function confirmAccountAction() {
        if (pendingReviewAction === "override") {
            var reason = $("#overrideReason").val().trim();
            if (!reason) {
                showError("오버라이드 사유를 입력해 주세요.");
                return;
            }
            submitRecovery("override", { reason: reason });
            return;
        }
        if (pendingReviewAction) {
            submitReview(pendingReviewAction);
        }
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
        var statusTone = MARIA.status.accountClosureStatusTone(closure.status);
        var rows = '<div class="account-tab-row"><div><span class="account-tab-row-label">처리 상태</span><strong class="adm-chip adm-chip-' +
            statusTone + '">' + escapeHtml(MARIA.status.accountClosureStatusLabel(closure.status)) + '</strong></div>' +
            '<div><span class="account-tab-row-label">신청 일시</span><strong>' + escapeHtml(formatDateTime(closure.requestedAt)) + '</strong></div>';
        if (closure.processedAt) {
            rows += '<div><span class="account-tab-row-label">처리 일시</span><strong>' + escapeHtml(formatDateTime(closure.processedAt)) + '</strong></div>';
        }
        if (closure.destinationGeneralAccountId) {
            rows += '<div><span class="account-tab-row-label">인출 목적지 일반계좌</span><strong>' +
                escapeHtml(closure.destinationGeneralAccountId) + '</strong></div>';
        }
        if (closure.earlyWithdrawalAgreed != null) {
            rows += '<div><span class="account-tab-row-label">조기인출 동의</span><strong>' +
                (closure.earlyWithdrawalAgreed ? "동의함" : "동의하지 않음") + '</strong></div>';
        }
        if (closure.hasImmaturePrincipal != null) {
            rows += '<div><span class="account-tab-row-label">' +
                (closure.status === "COMPLETED" ? "실제 미경과 인출액" : "현재 미경과 원금") +
                '</span><strong>' + (closure.hasImmaturePrincipal
                    ? escapeHtml(formatDetailAmount(closure.immaturePrincipalAmount)) +
                        (closure.status === "COMPLETED" ? "" : " 보유")
                    : "없음") + '</strong></div>';
        }
        if (closure.taxBenefitCancellationExpected != null || closure.taxBenefitCancellationOccurred != null) {
            var taxImpact = "영향 없음";
            if (closure.status === "COMPLETED") {
                taxImpact = closure.taxBenefitCancellationOccurred ? "전체 취소 발생" : "취소 없음";
            } else if (closure.status === "REJECTED") {
                taxImpact = "취소 없음";
            } else if (closure.taxBenefitCancellationExpected) {
                taxImpact = "승인 시 전체 취소";
            }
            rows += '<div><span class="account-tab-row-label">' +
                (closure.status === "COMPLETED" ? "세제혜택 처리 결과" : "세제혜택 예상 영향") +
                '</span><strong>' + escapeHtml(taxImpact) + '</strong></div>';
        }
        if (closure.rejectionReason) {
            rows += '<div class="account-tab-row-wide"><span class="account-tab-row-label">반려 사유</span><strong>' +
                escapeHtml(closure.rejectionReason) + '</strong></div>';
        }
        $("#detailClosure").html(rows + "</div>");
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
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "계좌 해지 내역을 불러오지 못했습니다.");
            });
    }

    function loadManagementDetail(accountId) {
        selectedClosureRequestId = null;
        $("#closureReviewActions").prop("hidden", true);
        $("#closureRejectionReason").val("");
        $("#detailHoldings, #detailInbounds, #detailClosure, #detailWithdrawals, #detailSales")
            .html('<div class="account-related-empty">불러오는 중...</div>');
        MARIA.auth.ajax({ url: "/api/admin/account/" + accountId + "/management-detail", method: "GET" })
            .done(function (res) {
                if (selectedAccountId !== accountId) return;
                var detail = res.data || {};
                renderRelatedList("#detailHoldings", detail.holdings, function (item) {
                    return '<div class="account-tab-row"><div><strong>' + escapeHtml(item.productName || item.ticker || "-") +
                        '</strong><span>' + escapeHtml(item.ticker || "-") + '</span></div><div><span class="account-tab-row-label">보유 수량</span><strong>' +
                        escapeHtml(formatQuantity(item.currentQty)) + '주</strong></div><small>' +
                        escapeHtml([item.market, item.currency].filter(Boolean).join(" · ") || "-") + '</small></div>';
                }, "보유주식이 없습니다.");
                renderRelatedList("#detailInbounds", detail.inbounds, function (item) {
                    return '<div class="account-tab-row"><div><strong>' + escapeHtml(item.productName || item.ticker || "-") +
                        '</strong><span>' + escapeHtml(item.ticker || "-") + '</span></div><div><span class="account-tab-row-label">신청 수량</span><strong>' +
                        escapeHtml(formatQuantity(item.qty)) + '주</strong></div><div><span class="account-tab-row-label">현재 보유</span><strong>' +
                        escapeHtml(formatQuantity(item.currentQty)) + '주</strong></div><small>' +
                        escapeHtml(item.sourceBroker || "-") + ' · 매수 ' + escapeHtml(item.purchaseDate || "-") +
                        ' · 처리 ' + escapeHtml(formatDateTime(item.recordedAt)) + '</small></div>';
                }, "입고주식이 없습니다.");
                renderRelatedList("#detailClosure", detail.closure ? [detail.closure] : [], function (item) {
                    return '<div class="account-tab-row"><div><span class="account-tab-row-label">처리 상태</span><strong class="adm-chip adm-chip-' +
                        MARIA.status.accountClosureStatusTone(item.status) + '">' +
                        escapeHtml(MARIA.status.accountClosureStatusLabel(item.status)) + '</strong></div><div><span class="account-tab-row-label">신청 일시</span><strong>' +
                        escapeHtml(formatDateTime(item.requestedAt)) + '</strong></div>' +
                        (item.processedAt ? '<div><span class="account-tab-row-label">처리 일시</span><strong>' + escapeHtml(formatDateTime(item.processedAt)) + '</strong></div>' : "") +
                        (item.rejectionReason ? '<div class="account-tab-row-wide"><span class="account-tab-row-label">반려 사유</span><strong>' + escapeHtml(item.rejectionReason) + '</strong></div>' : "") +
                        '</div>';
                }, "해지 신청 이력이 없습니다.");
                if (detail.closure && detail.closure.closureRequestId) {
                    loadClosureDetail(accountId, detail.closure.closureRequestId, detail.closure);
                }
                renderRelatedList("#detailWithdrawals", detail.withdrawals, function (item) {
                    return '<div class="account-tab-row"><div><span class="account-tab-row-label">인출 금액</span><strong>' +
                        escapeHtml(formatDetailAmount(item.requestedAmount)) + '</strong></div><div><span class="account-tab-row-label">처리 상태</span><strong class="adm-chip adm-chip-' +
                        MARIA.status.withdrawalStatusTone(item.status) + '">' + escapeHtml(MARIA.status.withdrawalStatusLabel(item.status)) +
                        '</strong></div><div><span class="account-tab-row-label">처리 일시</span><strong>' +
                        escapeHtml(formatDateTime(item.processedAt)) + '</strong></div><small>목적지 ' +
                        escapeHtml(item.destinationAccountNo || "-") + '</small></div>';
                }, "인출 이력이 없습니다.");
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "계좌 관련 내역을 불러오지 못했습니다.");
            });
        loadSellOrders(accountId);
    }

    function loadSellOrders(accountId) {
        MARIA.auth.ajax({
            url: "/api/admin/sell-orders",
            method: "GET",
            data: { accountId: accountId }
        }).done(function (res) {
            if (selectedAccountId !== accountId) return;
            renderRelatedList("#detailSales", res.data, function (item) {
                var stage = MARIA.status.sellStage(item.status, null, null);
                var tone = MARIA.status.sellStageTone(stage);
                return '<div class="account-tab-row"><div><span class="account-tab-row-label">주문 번호</span><strong>' +
                    escapeHtml(item.orderId) + '</strong></div><div><span class="account-tab-row-label">매도 수량</span><strong>' +
                    escapeHtml(formatQuantity(item.sellQty)) + '주</strong></div><div><span class="account-tab-row-label">처리 상태</span><strong class="adm-chip adm-chip-' +
                    tone + '">' + escapeHtml(MARIA.status.sellStageLabel(stage)) + '</strong></div><div><span class="account-tab-row-label">기준 가격</span><strong>' +
                    escapeHtml(formatQuantity(item.basePrice)) + '</strong></div><small>처리 ' +
                    escapeHtml(formatDateTime(item.processedAt)) + '</small></div>';
            }, "매도 내역이 없습니다.");
        }).fail(function (xhr) {
            if (selectedAccountId === accountId) {
                $("#detailSales").html('<div class="account-related-empty">매도 내역을 불러오지 못했습니다.</div>');
                handleRequestFailure(xhr, "매도 내역을 불러오지 못했습니다.");
            }
        });
    }

    function closeAccountDetail() {
        $("#accountDetailModal").removeClass("is-open").attr("aria-hidden", "true");
        $("#accountDetailBackdrop").prop("hidden", true).removeClass("is-open");
        $("body").removeClass("account-modal-open");
    }

    function openLimitModal() {
        var account = getSelectedAccount();
        if (!MARIA.auth.hasRole("REVIEWER") || !account ||
            (account.status !== "APPLIED" && account.status !== "OPENED")) {
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

    function openAccountCreateModal() {
        if (!MARIA.auth.hasRole("REVIEWER")) {
            return;
        }
        $("#accountCreateModal").css("display", "flex");
        $("#createCustomerName").trigger("focus");
    }

    function openReapplyModal() {
        var account = getSelectedAccount();
        if (!MARIA.auth.hasRole("REVIEWER") || !account || account.status !== "REJECTED") {
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
        $("#accountListBody").html(loadingRowsHtml());
        MARIA.auth.ajax({ url: "/api/admin/account/list", method: "GET" })
            .done(function (res) {
                accounts = res.data || [];
                renderSummary();
                renderApplicationTrend();
                renderAccounts();
                loadPendingAccountCount();
                if (!deepLinkHandled) {
                    deepLinkHandled = true;
                    var deepLinkAccountNo = MARIA.deeplink.accountNoFromUrl();
                    if (deepLinkAccountNo) {
                        var normalizedAccountNo = deepLinkAccountNo.replace(/\D/g, "");
                        var linkedAccount = accounts.find(function (account) {
                            return String(account.accountNo || "").replace(/\D/g, "") === normalizedAccountNo;
                        });
                        if (linkedAccount) {
                            accountStatus = "";
                            $("#accountFilterStatus").val("");
                            $("#accountCustomerNameSearch").val(normalizedAccountNo);
                            currentPage = 1;
                            renderAccounts();
                            selectAccount(linkedAccount.accountId);
                        } else {
                            showError("요청한 계좌번호를 목록에서 찾을 수 없습니다.");
                        }
                    }
                }
                if (afterLoad) {
                    afterLoad();
                }
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "계좌 목록을 불러오지 못했습니다.", function () {
                    $("#accountListBody").html(
                        '<tr class="is-state"><td colspan="9"><div class="adm-state-error">' +
                        '<strong>계좌 목록을 불러오지 못했습니다.</strong>' +
                        '<button type="button" id="accountListRetry" class="adm-btn">다시 시도</button>' +
                        '</div></td></tr>'
                    );
                });
            });
    }

    function submitReview(action) {
        if (!selectedAccountId || !MARIA.auth.hasRole("REVIEWER")) {
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
                closeAccountActionModal(false);
                reloadSelectedAccount();
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "계좌 상태 변경에 실패했습니다.");
            });
    }

    function submitRecovery(action, payload) {
        if (!selectedAccountId || !MARIA.auth.hasRole("REVIEWER")) {
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
                if (action === "override") {
                    closeAccountActionModal(false);
                }
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
        if (!MARIA.auth.hasRole("REVIEWER")) {
            return;
        }
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
                    closeModal("#accountCreateModal");
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
    $(document).on("click", ".account-sort", function () {
        var nextKey = $(this).data("sort");
        if (sortKey === nextKey) {
            sortDirection *= -1;
        } else {
            sortKey = nextKey;
            sortDirection = -1;
        }
        currentPage = 1;
        renderAccounts();
    });
    $(document).on("click", "#accountListRetry", function () { loadAccounts(); });
    $(document).on("click", ".account-reset-empty", function () { $("#accountFilterReset").trigger("click"); });
    $(document).on("click", "#accountPageSize button", function () {
        PAGE_SIZE = Number($(this).data("size"));
        $("#accountPageSize button").removeClass("is-on");
        $(this).addClass("is-on");
        currentPage = 1;
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
    $(document).on("click", "[data-account-tab]", function () {
        selectDetailTab($(this).data("account-tab"));
    });
    $(document).on("input", ".account-currency-input", function () { formatLimitInput(this); });
    $("#accountFilterSearch").on("click", function () {
        accountStatus = $("#accountFilterStatus").val() || "";
        currentPage = 1;
        renderAccounts();
    });
    $("#accountFilterReset").on("click", function () {
        accountStatus = "";
        $("#accountFilterStatus").val("");
        $("#accountCustomerNameSearch").val("");
        currentPage = 1;
        renderAccounts();
    });
    $("#accountCustomerNameSearch").on("keydown", function (event) {
        if (event.key === "Enter") {
            event.preventDefault();
            $("#accountFilterSearch").trigger("click");
        }
    });
    $("#accountTrendStatusFilter").on("change", function () {
        renderApplicationTrend();
    });
    $("#closeAccountDetail, #accountDetailBackdrop").on("click", closeAccountDetail);
    $(document).on("keydown", function (event) {
        if (event.key === "Escape" && event.defaultPrevented) {
            return;
        }
        if (event.key === "Escape" && $("#accountActionModal").is(":visible")) {
            closeAccountActionModal(true);
            return;
        }
        if (event.key === "Escape" && $("#accountCreateModal").is(":visible")) {
            closeModal("#accountCreateModal");
            return;
        }
        if (event.key === "Escape" && !event.defaultPrevented &&
            $("#accountDetailModal").hasClass("is-open") &&
            !$("#accountCreateModal, #accountLimitModal, #accountReapplyModal, #accountActionModal").is(":visible")) {
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
        } else if (event.key === "Escape" && !$("#customerSearchResults").prop("hidden")) {
            event.preventDefault();
            event.stopPropagation();
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
    $("#approveAccount").on("click", function () { openAccountActionModal("approve"); });
    $("#rejectAccount").on("click", function () { openAccountActionModal("reject"); });
    $("#accountActionCancelButton").on("click", function () { closeAccountActionModal(true); });
    $("#accountActionConfirmButton").on("click", confirmAccountAction);
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
        openAccountActionModal("override");
    });
    $("#openLimitModal").on("click", openLimitModal);
    $("#openAccountCreateModal").on("click", openAccountCreateModal);
    $("#closeAccountCreateModal, #cancelAccountCreateModal, #accountCreateModal .account-modal-backdrop")
        .on("click", function () { closeModal("#accountCreateModal"); });
    $("#closeLimitModal, #cancelLimitModal, #accountLimitModal .account-modal-backdrop").on("click", function () { closeModal("#accountLimitModal"); });
    $("#accountLimitModalForm").on("submit", function (event) {
        event.preventDefault();
        var account = getSelectedAccount();
        var limitAmount = parseLimitAmount("#modalNewLimitAmount");
        if (!MARIA.auth.hasRole("REVIEWER") || !account || !this.checkValidity() || !isValidLimitAmount(limitAmount)) {
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
        if (!MARIA.auth.hasRole("REVIEWER")) {
            return;
        }
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

    // 인증 정보가 준비된 뒤 보호 데이터를 조회하고 역할별 UI를 렌더링한다.
    MARIA.auth.requireAuth().done(function () {
        $("#openAccountCreateModal").prop("hidden", !MARIA.auth.hasRole("REVIEWER"));
        loadBusinessToday().done(function () {
            renderSummary();
        });
        loadAccounts();
    });
});
