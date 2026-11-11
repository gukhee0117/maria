$(function () {
    var STATUS_CONFIG = [
        { value: "APPLIED", label: "심사 대기", summarySelector: "#appliedAccountCount" },
        { value: "OPENED", label: "개설 완료", summarySelector: "#openedAccountCount" },
        { value: "REJECTED", label: "반려", summarySelector: "#rejectedAccountCount" },
        { value: "CLOSURE_REQUESTED", label: "해지 접수", summarySelector: "#closureRequestedAccountCount" },
        { value: "CLOSED", label: "해지", summarySelector: "#closedAccountCount" }
    ];
    var KRW_FORMATTER = new Intl.NumberFormat("ko-KR");
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
        return KRW_FORMATTER.format(Number(amount || 0)) + "원";
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
        return formatListDateTime(value);
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
                '<td class="account-col-name"><span>' + escapeHtml(MARIA.fmt.maskName(account.customerName) || "고객 ID " + account.customerId) + '</span></td>' +
                '<td class="is-c"><span class="adm-chip adm-chip-' + statusTone + '">' + escapeHtml(statusLabel(account.status)) + '</span></td>' +
                '<td class="is-r">' + KRW_FORMATTER.format(Number(account.limitAmount || 0)) + '</td>' +
                '<td class="is-r">' + KRW_FORMATTER.format(Number(account.amount || 0)) + '</td>' +
                '<td class="is-c"><span class="adm-chip adm-chip-' + MARIA.status.benefitTone(account.benefit) + '">' + escapeHtml(MARIA.status.benefitLabel(account.benefit)) + '</span></td>' +
                '<td>' + escapeHtml(formatListDateTime(account.createdAt)) + '</td>' +
                '<td>' + escapeHtml(formatListDateTime(account.openedAt)) + '</td>' +
                '</tr>'
            );
        });
        var $pagination = $("#accountPagination").empty();
        if (totalPages > 1) {
            var blockStart = Math.floor((currentPage - 1) / 10) * 10 + 1;
            var blockEnd = Math.min(totalPages, blockStart + 9);
            var addPageButton = function (label, page, disabled, active) {
                $("<button>", {
                    type: "button",
                    class: "adm-page-btn" + (active ? " is-active" : "") + (/^[0-9]+$/.test(label) ? "" : " is-nav"),
                    text: label,
                    disabled: disabled || active
                }).data("page", page).appendTo($pagination);
            };
            if (blockStart > 1) {
                addPageButton("이전", blockStart - 1, false, false);
            }
            for (var page = blockStart; page <= blockEnd; page += 1) {
                addPageButton(String(page), page, false, page === currentPage);
            }
            addPageButton("다음", Math.min(totalPages, blockEnd + 1), blockEnd === totalPages, false);
        }
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
        $("#detailCustomerName").text(account.customerName ? MARIA.fmt.maskName(account.customerName) + " 님 계좌" : "계좌 상세");
        $("#detailAccountNo").html(MARIA.fmt.accountNoHtml(account.accountNo));
        $("#detailCustomerId").text(account.customerId || "-");
        var statusTone = MARIA.status.accountStatusTone(account.status);
        $("#detailStatus").text(statusLabel(account.status))
            .removeClass("adm-chip-ok adm-chip-info adm-chip-warn adm-chip-fail adm-chip-off")
            .addClass("adm-chip-" + statusTone);
        $("#detailBenefit").text(MARIA.status.benefitLabel(account.benefit))
            .removeClass("adm-chip-ok adm-chip-info adm-chip-warn adm-chip-fail adm-chip-off")
            .addClass("adm-chip-" + MARIA.status.benefitTone(account.benefit));
        $("#detailCreatedAt").text(formatListDateTime(account.createdAt));
        $("#detailOpenedAt").text(formatDateTime(account.openedAt));
        $("#detailAmount").text(formatDetailAmount(account.amount));
        $("#detailLimitAmount").text(formatDetailAmount(account.limitAmount));
        var usedAmount = usedAmountOf(account);
        var limitAmount = Number(account.limitAmount || 0);
        var usageRatio = limitAmount > 0 ? Math.max(0, Math.min(1, usedAmount / limitAmount)) : 0;
        $("#detailUsedAmount").text(KRW_FORMATTER.format(usedAmount));
        $("#detailLimitGaugeText").text("/ " + KRW_FORMATTER.format(limitAmount));
        $("#detailRemainingLimit").text("한도 대비 " + Math.round(usageRatio * 100) + "% 사용");
        $("#detailLimitGaugeTotal").text(KRW_FORMATTER.format(limitAmount));
        $("#detailLimitGaugeFill").css("width", (usageRatio * 100) + "%");
        var canReview = MARIA.auth.hasRole("REVIEWER");
        // 처리 버튼 5개는 원본처럼 항상 보인다(REVIEWER 만). 지금 계좌 상태에서 못 쓰는 버튼은 disabled + 이유 툴팁.
        $("#accountReviewControls").prop("hidden", !canReview);
        $("#accountDetailModal .account-detail-foot").toggle(canReview);
        var status = account.status;
        var statusName = statusLabel(status);
        function setAction(selector, usable, reason) {
            $(selector).prop("hidden", !canReview).prop("disabled", !usable)
                .attr("title", usable ? "" : reason + " (현재 상태: " + statusName + ")");
        }
        setAction("#approveAccount", status === "APPLIED", "심사 대기 계좌만 승인할 수 있어요.");
        setAction("#rejectAccount", status === "APPLIED", "심사 대기 계좌만 반려할 수 있어요.");
        setAction("#openLimitModal", status === "APPLIED" || status === "OPENED", "심사 대기 또는 개설 완료 계좌만 한도를 바꿀 수 있어요.");
        setAction("#openReapplyModal", status === "REJECTED", "반려된 계좌만 재신청할 수 있어요.");
        setAction("#overrideAccount", status === "REJECTED", "반려된 계좌만 오버라이드 승인할 수 있어요.");
        $("#rejectionActions").prop("hidden", !canReview || status !== "APPLIED");
        $("#overrideActions").prop("hidden", !canReview || status !== "REJECTED");
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

    // 상세 탭의 내용 칸: 표(tbody)면 한 줄짜리 안내 행으로, 아니면(해지 탭) 안내 문구 한 줄로 채운다.
    function relatedMessageHtml(selector, message) {
        var $target = $(selector);
        if ($target.is("tbody")) {
            var columns = $target.closest("table").find("thead th").length || 1;
            return '<tr class="is-state"><td colspan="' + columns + '" class="account-related-empty">' + escapeHtml(message) + "</td></tr>";
        }
        return '<div class="account-related-empty">' + escapeHtml(message) + "</div>";
    }

    function renderRelatedList(selector, items, renderer, emptyMessage) {
        var $list = $(selector).empty();
        if (!items || !items.length) {
            $list.append(relatedMessageHtml(selector, emptyMessage));
            return;
        }
        items.forEach(function (item, index) { $list.append(renderer(item, index)); });
    }

    function chipHtml(tone, label) {
        return '<span class="adm-chip adm-chip-' + tone + '">' + escapeHtml(label) + "</span>";
    }

    function noCell(index) {
        return '<td class="adm-col-no">' + (index + 1) + "</td>";
    }

    function renderClosureDetail(closure) {
        selectedClosureRequestId = closure.closureRequestId || null;
        var statusTone = MARIA.status.accountClosureStatusTone(closure.status);
        function row(label, valueHtml) {
            return '<div class="adm-kv-k">' + escapeHtml(label) + '</div><div class="adm-kv-v">' + valueHtml + "</div>";
        }
        var rows = row("처리 상태", chipHtml(statusTone, MARIA.status.accountClosureStatusLabel(closure.status))) +
            row("신청 일시", escapeHtml(formatDateTime(closure.requestedAt)));
        if (closure.processedAt) {
            rows += row("처리 일시", escapeHtml(formatDateTime(closure.processedAt)));
        }
        if (closure.destinationGeneralAccountId) {
            rows += row("인출 목적지 일반계좌", escapeHtml(closure.destinationGeneralAccountId));
        }
        if (closure.earlyWithdrawalAgreed != null) {
            rows += row("조기인출 동의", closure.earlyWithdrawalAgreed ? "동의함" : "동의하지 않음");
        }
        if (closure.hasImmaturePrincipal != null) {
            rows += row(closure.status === "COMPLETED" ? "실제 미경과 인출액" : "현재 미경과 원금",
                closure.hasImmaturePrincipal
                    ? escapeHtml(formatDetailAmount(closure.immaturePrincipalAmount)) +
                        (closure.status === "COMPLETED" ? "" : " 보유")
                    : "없음");
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
            rows += row(closure.status === "COMPLETED" ? "세제혜택 처리 결과" : "세제혜택 예상 영향", escapeHtml(taxImpact));
        }
        if (closure.rejectionReason) {
            rows += row("반려 사유", escapeHtml(closure.rejectionReason));
        }
        $("#detailClosure").html('<div class="adm-kv">' + rows + "</div>");
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
        ["#detailHoldings", "#detailInbounds", "#detailClosure", "#detailWithdrawals", "#detailSales"].forEach(function (selector) {
            $(selector).html(relatedMessageHtml(selector, "불러오는 중..."));
        });
        MARIA.auth.ajax({ url: "/api/admin/account/" + accountId + "/management-detail", method: "GET" })
            .done(function (res) {
                if (selectedAccountId !== accountId) return;
                var detail = res.data || {};
                renderRelatedList("#detailHoldings", detail.holdings, function (item, index) {
                    return "<tr>" + noCell(index) +
                        "<td><span>" + escapeHtml(item.productName || item.ticker || "-") + " " + escapeHtml(item.ticker || "") + "</span></td>" +
                        "<td>" + escapeHtml([item.market, item.currency].filter(Boolean).join(" · ") || "-") + "</td>" +
                        '<td class="is-r">' + escapeHtml(formatQuantity(item.currentQty)) + "</td></tr>";
                }, "보유주식이 없습니다.");
                renderRelatedList("#detailInbounds", detail.inbounds, function (item, index) {
                    return "<tr>" + noCell(index) +
                        "<td><span>" + escapeHtml(item.productName || item.ticker || "-") + " " + escapeHtml(item.ticker || "") + "</span></td>" +
                        "<td>" + escapeHtml(item.sourceBroker || "-") + " · " + escapeHtml(item.purchaseDate || "-") + "</td>" +
                        '<td class="is-r">' + escapeHtml(formatQuantity(item.qty)) + "</td>" +
                        '<td class="is-r">' + escapeHtml(formatQuantity(item.currentQty)) + "</td>" +
                        "<td>" + escapeHtml(formatDateTime(item.recordedAt)) + "</td></tr>";
                }, "입고주식이 없습니다.");
                if (detail.closure) {
                    renderClosureDetail(detail.closure);
                } else {
                    $("#detailClosure").html(relatedMessageHtml("#detailClosure", "해지 신청 이력이 없습니다."));
                }
                if (detail.closure && detail.closure.closureRequestId) {
                    loadClosureDetail(accountId, detail.closure.closureRequestId, detail.closure);
                }
                renderRelatedList("#detailWithdrawals", detail.withdrawals, function (item, index) {
                    return "<tr>" + noCell(index) +
                        '<td class="is-r">' + escapeHtml(KRW_FORMATTER.format(Number(item.requestedAmount || 0))) + "</td>" +
                        '<td class="is-c">' + chipHtml(MARIA.status.withdrawalStatusTone(item.status), MARIA.status.withdrawalStatusLabel(item.status)) + "</td>" +
                        "<td>" + escapeHtml(formatDateTime(item.processedAt)) + "</td>" +
                        "<td>" + escapeHtml(item.destinationAccountNo || "-") + "</td></tr>";
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
            renderRelatedList("#detailSales", res.data, function (item, index) {
                var stage = MARIA.status.sellStage(item.status, null, null);
                var tone = MARIA.status.sellStageTone(stage);
                return "<tr>" + noCell(index) +
                    "<td>" + escapeHtml(item.orderId) + "</td>" +
                    '<td class="is-r">' + escapeHtml(formatQuantity(item.sellQty)) + "</td>" +
                    '<td class="is-c">' + chipHtml(tone, MARIA.status.sellStageLabel(stage)) + "</td>" +
                    '<td class="is-r">' + escapeHtml(formatQuantity(item.basePrice)) + "</td>" +
                    "<td>" + escapeHtml(formatDateTime(item.processedAt)) + "</td></tr>";
            }, "매도 내역이 없습니다.");
        }).fail(function (xhr) {
            if (selectedAccountId === accountId) {
                $("#detailSales").html(relatedMessageHtml("#detailSales", "매도 내역을 불러오지 못했습니다."));
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
        $("#accountLimitModalDesc").text(accountLabelOf(account));
        $("#modalCurrentLimit").text(formatAmount(account.limitAmount));
        $("#modalUsedAmount").text(formatAmount(usedAmount));
        $("#modalRemainingLimit").text(formatAmount(remainingLimitOf(account)));
        setLimitAmount("#modalNewLimitAmount", account.limitAmount);
        $("#accountLimitModal").css("display", "grid");
        $("#modalNewLimitAmount").trigger("focus");
        updateAvailableLimit(account.customerId, "#modalAvailableLimit", { initial: "조회 실패", error: "설정 가능 한도를 조회할 수 없습니다." });
    }

    function accountLabelOf(account) {
        return (MARIA.fmt.maskName(account.customerName) || "고객 ID " + account.customerId) + " · " + MARIA.fmt.hyphenateAccountNo(account.accountNo);
    }

    function closeModal(modalSelector) {
        $(modalSelector).hide();
    }

    function openAccountCreateModal() {
        if (!MARIA.auth.hasRole("REVIEWER")) {
            return;
        }
        $("#accountCreateModal").css("display", "grid");
        $("#createCustomerName").trigger("focus");
    }

    function openReapplyModal() {
        var account = getSelectedAccount();
        if (!MARIA.auth.hasRole("REVIEWER") || !account || account.status !== "REJECTED") {
            return;
        }
        $("#accountReapplyModalDesc").text(accountLabelOf(account));
        $("#reapplyCurrentLimit").text(formatAmount(account.limitAmount));
        setLimitAmount("#modalReapplyLimitAmount", account.limitAmount);
        $("#accountReapplyModal").css("display", "grid");
        $("#modalReapplyLimitAmount").trigger("focus");
        updateAvailableLimit(account.customerId, "#reapplyAvailableLimit", { initial: "조회 실패", error: "설정 가능 한도를 조회할 수 없습니다." });
    }

    function loadStatusLogs(accountId) {
        var $list = $("#accountHistoryList").html(relatedMessageHtml("#accountHistoryList", "불러오는 중..."));
        MARIA.auth.ajax({ url: "/api/admin/account/" + accountId + "/status-logs", method: "GET" })
            .done(function (res) {
                if (selectedAccountId !== accountId) {
                    return;
                }
                $list.empty();
                var logs = res.data || [];
                if (!logs.length) {
                    $list.append(relatedMessageHtml("#accountHistoryList", "상태 이력이 없습니다."));
                    return;
                }
                logs.forEach(function (log, index) {
                    $list.append(
                        "<tr>" + noCell(index) +
                        "<td>" + escapeHtml(formatDateTime(log.changedAt)) + "</td>" +
                        "<td>" + escapeHtml(statusLabel(log.prevStatus)) + " → " + escapeHtml(statusLabel(log.newStatus)) + "</td>" +
                        "<td>" + escapeHtml(log.reason || "-") + "</td></tr>"
                    );
                });
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "상태 이력을 불러오지 못했습니다.", function () { $list.empty(); });
            });
    }

    function canIssueDocument() {
        return MARIA.auth.hasRole("ADMIN") || MARIA.auth.hasRole("SETTLEMENT");
    }

    function loadDocuments(accountId) {
        var canDownload = canIssueDocument();
        $(".issue-document").prop("hidden", !canDownload);
        $("#detailDocuments").html(relatedMessageHtml("#detailDocuments", "불러오는 중..."));
        MARIA.auth.ajax({ url: "/api/admin/documents/accounts/" + accountId, method: "GET" })
            .done(function (res) {
                if (selectedAccountId !== accountId) return;
                renderRelatedList("#detailDocuments", res.data, function (item, index) {
                    var revoked = item.status === "REVOKED";
                    var link = canDownload && !revoked
                        ? '<a class="adm-btn" href="/api/admin/documents/' + encodeURIComponent(item.documentIssueId) + '/download" target="_blank" rel="noopener">PDF</a>'
                        : "-";
                    return "<tr>" + noCell(index) +
                        "<td>" + (item.docType === "NET_PURCHASE_STATEMENT" ? "순매수 내역서" : "과세특례 증빙서") + "</td>" +
                        "<td>" + escapeHtml(item.issueNo) + "</td>" +
                        '<td class="is-c">' + chipHtml(revoked ? "off" : "ok", revoked ? "취소됨" : "발급됨") + "</td>" +
                        "<td>" + (item.issuedByType === "ADMIN" ? "관리자" : "고객") + "</td>" +
                        "<td>" + escapeHtml(formatDateTime(item.issuedAt)) + "</td>" +
                        '<td class="is-c">' + link + "</td></tr>";
                }, "발급된 확인서가 없습니다.");
            })
            .fail(function (xhr) {
                if (selectedAccountId === accountId) {
                    $("#detailDocuments").html(relatedMessageHtml("#detailDocuments", "확인서 목록을 불러오지 못했습니다."));
                    handleRequestFailure(xhr, "확인서 목록을 불러오지 못했습니다.");
                }
            });
    }

    function issueDocument() {
        var accountId = selectedAccountId;
        if (!accountId || !canIssueDocument()) return;
        $(".issue-document").prop("disabled", true);
        MARIA.auth.ajax({
            url: "/api/admin/documents/accounts/" + accountId + "?docType=" + encodeURIComponent($(this).data("docType")),
            method: "POST"
        })
            .done(function () { loadDocuments(accountId); })
            .fail(function (xhr) { handleRequestFailure(xhr, "확인서를 발급하지 못했습니다."); })
            .always(function () { $(".issue-document").prop("disabled", false); });
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
                loadDocuments(account.accountId);
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
    $(document).on("click", "#accountPagination .adm-page-btn", function () {
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
    $(document).on("click", ".issue-document", issueDocument);
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
    $("#closeAccountDetail, #accountDetailBackdrop").on("click", closeAccountDetail);
    $(document).on("keydown", function (event) {
        if (event.key === "Escape" && event.defaultPrevented) {
            return;
        }
        if (event.key !== "Escape") {
            return;
        }
        // 모달이 열려 있으면 모달만 닫는다. layout.js 의 Esc 처리가 이어서 뒤의 상세 패널까지 닫지 않도록 전파를 멈춘다.
        if ($("#accountActionModal").is(":visible")) {
            closeAccountActionModal(true);
            event.stopImmediatePropagation();
            return;
        }
        var openModal = ["#accountCreateModal", "#accountLimitModal", "#accountReapplyModal"].filter(function (selector) {
            return $(selector).is(":visible");
        })[0];
        if (openModal) {
            closeModal(openModal);
            event.stopImmediatePropagation();
            return;
        }
        if ($("#accountDetailModal").hasClass("is-open")) {
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
    $("#cancelReapplyModal").on("click", function () { closeModal("#accountReapplyModal"); });
    $("#accountReapplyModal, #accountLimitModal, #accountCreateModal").on("mousedown", function (event) {
        if (event.target === this) { closeModal("#" + this.id); }
    });
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
    $("#closeAccountCreateModal, #cancelAccountCreateModal").on("click", function () { closeModal("#accountCreateModal"); });
    $("#cancelLimitModal").on("click", function () { closeModal("#accountLimitModal"); });
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
