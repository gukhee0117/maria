$(function () {
    var STATUS = MARIA.status; // 매도 상태·계좌 종류·0주 승인 사유 표기는 js/admin/status-labels.js 의 공통 매핑

    var QTY_FORMATTER = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 4 });
    var INT_FORMATTER = new Intl.NumberFormat("ko-KR");

    // 왼쪽 계좌 목록은 서버 쪽 이동(옛 화면과 같은 size=10), 오른쪽 입고 내역은 서버 쪽 이동에 페이지 크기만 20/50/100 으로 고른다.
    var ACCOUNT_PAGE_SIZE = 10;
    var inboundPageSize = 20;
    var SKELETON_ROWS = 8;
    var SKELETON_WIDTHS = ["62%", "70%", "48%", "54%", "66%", "78%", "56%", "40%", "60%"];
    var COLUMN_COUNT = 8;

    var accounts = [];
    var accountKeyword = "";
    var accountPage = 0;
    var selectedAccountId = null;
    var inbounds = [];
    var inboundPage = 0;
    var inboundSort = null; // 머리글로 고른 정렬(서버가 전체 데이터에서 정렬)
    var selectedInboundId = null;
    var accountRequestSeq = 0;   // 계좌를 빠르게 바꿀 때 늦게 온 옛 응답을 버리기 위한 번호
    var inboundRequestSeq = 0;

    // ---------- 서식 ----------
    function escapeHtml(value) {
        return $("<div>").text(value == null ? "" : value).html();
    }

    function pad(n) {
        return String(n).padStart(2, "0");
    }

    function parseDate(value) {
        var date = value ? new Date(value) : null;
        return !date || isNaN(date.getTime()) ? null : date;
    }

    function formatDate(value) {
        var date = parseDate(value);
        return !date ? "-" : date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate());
    }

    function formatDateTime(value) {
        var date = parseDate(value);
        return !date ? "-" : formatDate(value) + " " + pad(date.getHours()) + ":" + pad(date.getMinutes());
    }

    // 수량은 숫자만 (단위는 열 이름·요약 단위가 말해 준다). 값이 없으면 "-".
    function formatQty(qty) {
        return qty == null ? "-" : QTY_FORMATTER.format(Number(qty));
    }

    function formatQtyUnit(qty) {
        return qty == null ? "-" : QTY_FORMATTER.format(Number(qty)) + "주";
    }

    function formatPrice(price, currency) {
        if (price == null) {
            return "-";
        }
        return QTY_FORMATTER.format(Number(price)) + (currency ? " " + currency : "");
    }

    function chip(tone, label, title) {
        return '<span class="adm-chip adm-chip-' + tone + '"' + (title ? ' title="' + escapeHtml(title) + '"' : "") + ">" +
            escapeHtml(label) + "</span>";
    }

    function productLabel(item) {
        return [item.productName, item.ticker].filter(Boolean).join(" ") || "-";
    }

    function sourceLabel(item) {
        return item.sourceBroker ? item.sourceBroker : "당사";
    }

    // 승인수량 0주 = 반려 (요약의 "오늘 반려 건수"와 같은 뜻)
    function approvedCell(item) {
        if (item.approvedQty === 0) {
            return chip("fail", "0주 승인", STATUS.inboundZeroReasonLabel(STATUS.inboundZeroReason(item)));
        }
        return "<span>" + formatQty(item.approvedQty) + "</span>";
    }

    function stateRow(className, innerHtml, fill) {
        return '<tr class="is-state ' + (fill ? "is-state-fill " : "") + className + '"><td colspan="' + COLUMN_COUNT + '">' +
            innerHtml + "</td></tr>";
    }

    // 표 머리 쪽 이동 (이전/숫자/다음, 10쪽 묶음)
    function renderPager($pager, current, totalPages, onGo, blockSize) {
        $pager.empty();
        if (!totalPages || totalPages <= 1) {
            return;
        }
        var BLOCK_SIZE = blockSize || 10;
        var blockStart = Math.floor(current / BLOCK_SIZE) * BLOCK_SIZE;
        var blockEnd = Math.min(totalPages - 1, blockStart + BLOCK_SIZE - 1);

        function addButton(label, targetPage, isDisabled, isActive) {
            var classes = "adm-page-btn" + (isActive ? " is-active" : "") + (/^[0-9]+$/.test(label) ? "" : " is-nav");
            var $btn = $('<button type="button" class="' + classes + '">' + label + "</button>");
            $btn.prop("disabled", isDisabled || isActive);
            if (!isDisabled && !isActive) {
                $btn.on("click", function () {
                    onGo(targetPage);
                });
            }
            $pager.append($btn);
        }

        if (blockStart > 0) {
            addButton("이전", blockStart - 1, false, false);
        }
        for (var i = blockStart; i <= blockEnd; i++) {
            addButton(String(i + 1), i, false, i === current);
        }
        addButton("다음", blockEnd + 1, blockEnd === totalPages - 1, false);
    }

    function errorMessage(xhr, fallback) {
        return (xhr.responseJSON && xhr.responseJSON.message) || fallback;
    }

    // ---------- 요약 ----------
    function loadSummary() {
        MARIA.auth.ajax({ url: "/api/admin/inbounds/summary", method: "GET" })
            .done(function (res) {
                var s = res.data || {};
                $("#inbound-summary-processed").text(INT_FORMATTER.format(s.todayProcessedCount || 0));
                $("#inbound-summary-approved-qty").text(formatQty(s.todayApprovedQtySum || 0));
                $("#inbound-summary-rejected").text(INT_FORMATTER.format(s.todayRejectedCount || 0));
                $("#inbound-summary-reduced").text(INT_FORMATTER.format(s.todayReducedCount || 0));
            });
    }

    // ---------- 왼쪽: 계좌 목록 ----------
    function renderAccountLoading() {
        var html = "";
        for (var r = 0; r < SKELETON_ROWS; r++) {
            html += '<div class="adm-master-skel" aria-label="불러오는 중"><span style="width:' +
                SKELETON_WIDTHS[r % SKELETON_WIDTHS.length] + '"></span><span style="width:' +
                SKELETON_WIDTHS[(r + 3) % SKELETON_WIDTHS.length] + '"></span></div>';
        }
        $("#inbound-account-list").html(html);
    }

    function renderAccountList() {
        var $list = $("#inbound-account-list").empty();
        if (!accounts.length) {
            $list.html('<div class="adm-master-state">' +
                (accountKeyword ? "조건에 맞는 계좌가 없습니다." : "입고 이력이 있는 계좌가 없습니다.") +
                (accountKeyword ? '<br><button type="button" class="adm-btn inbound-account-empty-reset">조건 초기화</button>' : "") +
                "</div>");
            return;
        }
        accounts.forEach(function (account) {
            var selected = Number(account.accountId) === Number(selectedAccountId);
            var recent = formatDateTime(account.lastProcessedAt);
            $list.append(
                '<button type="button" class="adm-master-row' + (selected ? " is-selected" : "") +
                '" data-account-id="' + account.accountId + '"' + (selected ? ' aria-current="true"' : "") + ">" +
                '<span class="adm-master-main">' +
                '<span class="adm-master-no">' + escapeHtml(MARIA.fmt.hyphenateAccountNo(account.accountNo)) + "</span>" +
                '<span class="adm-master-sub" title="최근 처리 ' + escapeHtml(recent) + '">' + escapeHtml(account.customerName || "") +
                (account.lastProcessedAt ? " · 최근 " + escapeHtml(recent.substring(5)) : "") + "</span>" +
                "</span>" +
                '<span class="adm-master-n">' + INT_FORMATTER.format(account.inboundCount || 0) + "건</span>" +
                "</button>"
            );
        });
    }

    function selectedAccount() {
        return accounts.filter(function (account) {
            return Number(account.accountId) === Number(selectedAccountId);
        })[0] || null;
    }

    function loadAccounts(page) {
        var targetPage = page || 0;
        var requestData = { page: targetPage, size: ACCOUNT_PAGE_SIZE };
        if (accountKeyword) {
            requestData.keyword = accountKeyword;
        }
        var seq = ++accountRequestSeq;
        closeDrawer();
        renderAccountLoading();

        MARIA.auth.ajax({
            url: "/api/admin/inbounds/accounts",
            method: "GET",
            data: requestData
        })
            .done(function (res) {
                if (seq !== accountRequestSeq) {
                    return;
                }
                var data = res.data || {};
                accounts = data.content || [];
                accountPage = data.page || 0;
                var totalPages = Math.ceil((data.totalCount || 0) / ACCOUNT_PAGE_SIZE);
                $("#inbound-account-count").text(INT_FORMATTER.format(data.totalCount || 0));

                // 옛 화면과 같이 목록을 받으면 첫 계좌를 고르고 그 입고 내역을 불러온다.
                selectedAccountId = accounts.length ? accounts[0].accountId : null;
                renderAccountList();
                renderPager($("#inbound-account-pagination"), accountPage, totalPages, loadAccounts, 5);

                if (selectedAccountId != null) {
                    loadAccountInbounds(0);
                } else {
                    inbounds = [];
                    renderInboundState("none");
                }
            })
            .fail(function (xhr) {
                if (seq !== accountRequestSeq || xhr.status === 401) {
                    return;
                }
                accounts = [];
                selectedAccountId = null;
                inbounds = [];
                $("#inbound-account-count").text("-");
                $("#inbound-account-pagination").empty();
                $("#inbound-account-list").html('<div class="adm-master-state">' +
                    escapeHtml(errorMessage(xhr, "계좌 목록을 불러오지 못했습니다.")) +
                    '<br><button type="button" class="adm-btn inbound-account-retry">다시 시도</button></div>');
                renderInboundState("none");
            });
    }

    // ---------- 오른쪽: 입고 내역 ----------
    function renderInboundLoading() {
        var html = "";
        for (var r = 0; r < SKELETON_ROWS; r++) {
            html += '<tr class="is-state inbound-loading" aria-label="불러오는 중"><td colspan="' + COLUMN_COUNT +
                '"><div class="adm-skel-row"><div class="adm-skel-cell"></div>';
            for (var c = 0; c < COLUMN_COUNT - 1; c++) {
                html += '<div class="adm-skel-cell"><span style="width:' + SKELETON_WIDTHS[(c + r) % SKELETON_WIDTHS.length] +
                    ';animation-delay:' + ((c + r) % 5) * 0.06 + 's"></span></div>';
            }
            html += "</div></td></tr>";
        }
        $("#inbound-list").html(html);
    }

    // kind: none(고른 계좌 없음) / empty(입고 내역 없음) / error
    function renderInboundState(kind, message) {
        var html;
        $("#inbound-count").text(kind === "error" || kind === "none" ? "-" : "0");
        $("#inbound-pagination").empty();
        $("#inbound-selected-account").text("");
        if (kind === "error") {
            html = '<div class="adm-state-error"><div class="adm-state-error-text">' +
                '<div class="adm-state-error-title">' + escapeHtml(message) + "</div>" +
                '<div class="adm-state-error-desc">잠시 후 다시 시도해 주세요.</div></div>' +
                '<button type="button" class="adm-btn inbound-retry">다시 시도</button></div>';
        } else {
            html = '<div class="adm-state-empty"><img src="/images/admin/char-front.png" alt="">' +
                '<div class="adm-state-empty-title">' +
                (kind === "none" ? "왼쪽에서 계좌를 선택하세요." : "이 계좌의 입고 내역이 없습니다.") + "</div>" +
                (kind === "none" ? "" : '<div class="adm-state-empty-desc">다른 계좌를 선택해 주세요.</div>') + "</div>";
        }
        $("#inbound-list").html(stateRow("inbound-" + (kind === "error" ? "error" : "empty"), html, true));
    }

    function renderInbounds(pageData) {
        var $body = $("#inbound-list").empty();
        var account = selectedAccount();
        var start = (pageData.page || 0) * (pageData.size || inboundPageSize);

        $("#inbound-count").text(INT_FORMATTER.format(pageData.totalElements || 0));
        $("#inbound-selected-account").text(account
            ? (account.customerName || "") + " · " + MARIA.fmt.hyphenateAccountNo(account.accountNo) : "");
        renderPager($("#inbound-pagination"), pageData.page || 0, pageData.totalPages || 0, function (targetPage) {
            closeDrawer();
            loadAccountInbounds(targetPage);
        });

        if (!inbounds.length) {
            renderInboundState("empty");
            $("#inbound-count").text(INT_FORMATTER.format(pageData.totalElements || 0));
            $("#inbound-selected-account").text(account
                ? (account.customerName || "") + " · " + MARIA.fmt.hyphenateAccountNo(account.accountNo) : "");
            return;
        }

        inbounds.forEach(function (item, index) {
            var selectedClass = Number(item.inboundId) === Number(selectedInboundId) ? " is-selected" : "";
            $body.append(
                '<tr class="is-clickable' + selectedClass + '" tabindex="0" data-inbound-id="' + item.inboundId + '">' +
                '<td class="adm-col-no">' + (start + index + 1) + "</td>" +
                "<td><span>" + escapeHtml(productLabel(item)) + "</span></td>" +
                '<td class="is-r inbound-col-req"><span>' + formatQty(item.requestedQty) + "</span></td>" +
                '<td class="is-r"><span>' + formatQty(item.snapshotQty) + "</span></td>" +
                '<td class="is-r"><span>' + formatQty(item.currentHoldingAtRequest) + "</span></td>" +
                '<td class="is-r is-strong">' + approvedCell(item) + "</td>" +
                "<td><span>" + escapeHtml(sourceLabel(item)) + "</span></td>" +
                "<td><span>" + formatDateTime(item.processedAt) + "</span></td>" +
                "</tr>"
            );
        });
    }

    // ---------- 보유 종목 보기: GET /api/admin/inbounds/holdings?accountId= ----------
    var inboundView = "inbounds";
    var holdingsSeq = 0;

    function holdingsState(text) {
        $("#inbound-holdings-list").html('<tr class="is-state"><td colspan="6">' + escapeHtml(text) + "</td></tr>");
    }
    function loadHoldings() {
        if (selectedAccountId == null) {
            holdingsState("계좌를 선택하세요.");
            return;
        }
        var seq = ++holdingsSeq;
        holdingsState("불러오는 중...");
        MARIA.auth.ajax({ url: "/api/admin/inbounds/holdings", method: "GET", data: { accountId: selectedAccountId } })
            .done(function (res) {
                if (seq !== holdingsSeq) {
                    return;
                }
                var rows = res.data || [];
                $("#inbound-count").text(rows.length);
                if (!rows.length) {
                    holdingsState("보유 종목이 없습니다.");
                    return;
                }
                $("#inbound-holdings-list").html(rows.map(function (r, i) {
                    return '<tr><td class="adm-col-no">' + (i + 1) + "</td>" +
                        "<td>" + escapeHtml(r.name || "-") + "</td>" +
                        "<td>" + escapeHtml(r.ticker || "-") + "</td>" +
                        "<td>" + escapeHtml((r.market || "-") + " · " + (r.currency || "-")) + "</td>" +
                        '<td class="is-c">' + escapeHtml(r.type ? STATUS.stockTypeLabel(r.type) : "-") + "</td>" +
                        '<td class="is-r">' + escapeHtml(formatQty(r.currentQty)) + "</td></tr>";
                }).join(""));
            })
            .fail(function (xhr) {
                if (seq !== holdingsSeq || xhr.status === 401) {
                    return;
                }
                holdingsState("보유 종목을 불러오지 못했습니다.");
            });
    }
    function setInboundView(view) {
        inboundView = view;
        $("#inbound-view button").removeClass("is-on").filter(function () { return $(this).attr("data-view") === view; }).addClass("is-on");
        var holdings = view === "holdings";
        $("#inbound-holdings-body").prop("hidden", !holdings);
        $("#inbound-history-body").prop("hidden", holdings);
        $("#inbound-pagination").prop("hidden", holdings);
        $("#inbound-page-size").closest(".adm-per").prop("hidden", holdings);
        if (holdings) {
            loadHoldings();
        } else {
            loadAccountInbounds(inboundPage || 0);
        }
    }
    MARIA.tableSort.bind(".inbound-table", function (key, dir) {
        inboundSort = { key: key, dir: dir };
        loadAccountInbounds(0);
    });

    $("#inbound-view").on("click", "button", function () {
        var view = $(this).attr("data-view");
        if (view !== inboundView) {
            setInboundView(view);
        }
    });

    function loadAccountInbounds(page) {
        if (selectedAccountId == null) {
            return;
        }
        if (inboundView === "holdings") {
            loadHoldings();
        }
        var targetPage = page || 0;
        var seq = ++inboundRequestSeq;
        inboundPage = targetPage;
        renderInboundLoading();

        MARIA.auth.ajax({
            url: "/api/admin/inbounds/by-account/" + selectedAccountId,
            method: "GET",
            data: $.extend({ page: targetPage, size: inboundPageSize }, inboundSort ? { sort: inboundSort.key, direction: inboundSort.dir } : {})
        })
            .done(function (res) {
                if (seq !== inboundRequestSeq) {
                    return;
                }
                var data = res.data || {};
                inbounds = data.content || [];
                renderInbounds(data);
            })
            .fail(function (xhr) {
                if (seq !== inboundRequestSeq || xhr.status === 401) {
                    return;
                }
                inbounds = [];
                renderInboundState("error", errorMessage(xhr, "입고 내역을 불러오지 못했습니다."));
            });
    }

    // ---------- 상세 패널 ----------
    function round4(value) {
        return Math.round(Number(value) * 10000) / 10000;
    }

    // 세 값(신청 · 12.23 기준 · 요청 시점 보유) 중 가장 작은 값에 "최소" 표시. 같은 값이 여럿이면 모두 표시한다.
    function renderCompare(item) {
        var values = [
            { id: "inbound-cmp-requested", value: item.requestedQty },
            { id: "inbound-cmp-snapshot", value: item.snapshotQty },
            { id: "inbound-cmp-holding", value: item.currentHoldingAtRequest }
        ];
        var numbers = values.filter(function (v) { return v.value != null; }).map(function (v) { return round4(v.value); });
        var min = numbers.length ? Math.min.apply(null, numbers) : null;

        values.forEach(function (v) {
            var isMin = v.value != null && min != null && round4(v.value) === min;
            $("#" + v.id).toggleClass("is-min", isMin).find("b").text(formatQty(v.value));
        });
        $("#inbound-cmp-result").text(formatQty(item.approvedQty));
        return min;
    }

    function renderPriorApprovals(rows) {
        if (!rows.length) {
            return '<div class="inbound-lots-empty">기존 승인 내역이 없습니다.</div>';
        }
        var html = '<div class="adm-mini"><div class="adm-mini-row is-head"><span>처리일시</span><span class="is-r">신청수량</span><span class="is-r">승인수량</span></div>';
        rows.forEach(function (row) {
            html += '<div class="adm-mini-row"><span>' + formatDateTime(row.processedAt) + '</span><span class="is-r">' +
                formatQty(row.requestedQty) + '</span><span class="is-r">' + formatQty(row.approvedQty) + "</span></div>";
        });
        return html + "</div>";
    }

    function loadPriorApprovals(inboundId) {
        var $box = $("#inbound-prior-approvals").html('<div class="inbound-lots-empty">불러오는 중...</div>');
        MARIA.auth.ajax({ url: "/api/admin/inbounds/" + inboundId + "/prior-approvals", method: "GET" })
            .done(function (res) {
                if (Number(selectedInboundId) !== Number(inboundId)) {
                    return;
                }
                $box.html(renderPriorApprovals(res.data || []));
            })
            .fail(function (xhr) {
                if (Number(selectedInboundId) !== Number(inboundId) || xhr.status === 401) {
                    return;
                }
                $box.html('<div class="inbound-lots-empty">기존 승인 내역을 불러오지 못했습니다.</div>');
            });
    }

    // 취득 정보: 입고 lot 별 남은 수량 바 (채움 = 현재 잔량 ÷ 입고 수량), 아래에 소진·기록일·매도 이력
    function renderLots(item) {
        var lots = item.lots || [];
        var $box = $("#inbound-lots").empty();
        $("#inbound-lot-count").text(lots.length ? "· lot " + lots.length + "건" : "");
        if (!lots.length) {
            $box.html('<div class="inbound-lots-empty">취득 lot 정보가 없습니다.</div>');
            return;
        }
        var html = "";
        lots.forEach(function (lot, index) {
            var qty = Number(lot.qty) || 0;
            var currentQty = Number(lot.currentQty) || 0;
            var ratio = qty > 0 ? Math.max(0, Math.min(1, currentQty / qty)) : 0;
            var soldQty = qty - currentQty;
            var source = lot.sourceBroker || STATUS.generalAccountTypeLabel(lot.accountType);
            if (source === "-") {
                source = "당사";
            }
            var meta = [(soldQty > 0
                ? formatQtyUnit(soldQty) + " 소진 (" + Math.round((1 - ratio) * 100) + "%)" : "소진 없음") +
                " · 기록 " + formatDateTime(lot.recordedAt)];
            var history = lot.sellHistory || [];
            history.forEach(function (order) {
                meta.push("매도 " + STATUS.sellOrderStatusLabel(order.status) + " " + formatQtyUnit(order.sellQty) +
                    " @ " + formatPrice(order.basePrice) + " · " + formatDateTime(order.processedAt));
            });
            if (!history.length) {
                meta.push("매도 이력 없음");
            }

            html += '<div class="adm-alloc-item" data-lot-index="' + index + '" data-ratio="' + ratio + '">' +
                '<div class="adm-alloc-head"><span class="adm-alloc-name">' + escapeHtml(source) +
                " <small>· 매수 " + escapeHtml(formatDate(lot.purchaseDate)) + "</small></span>" +
                '<span class="adm-alloc-avail"><span>매수단가</span><span>' +
                escapeHtml(formatPrice(lot.purchasePrice, lot.purchaseCurrency)) + "</span></span></div>" +
                '<div class="adm-alloc-bar"><div class="adm-alloc-fill" style="transform:scaleX(' + ratio + ')"></div>' +
                '<span class="adm-alloc-amt">' + formatQty(currentQty) + " <small>/ " + formatQty(qty) + "</small></span></div>" +
                meta.map(function (line) {
                    return '<div class="adm-alloc-meta">' + escapeHtml(line) + "</div>";
                }).join("") +
                "</div>";
        });
        $box.html('<div class="adm-alloc">' + html + "</div>");
    }

    function renderDrawer(item) {
        var min = renderCompare(item);
        // 가용수량 = 기준수량 − 기존 승인 누적 (옛 화면과 같은 계산: 잔여 가능 수량 + 승인수량)
        var available = Number(item.remainingQty || 0) + Number(item.approvedQty || 0);
        var priorSum = Number(item.snapshotQty || 0) - available;
        var isZero = item.approvedQty === 0;

        $("#inbound-detail-title").text(productLabel(item));
        $("#inbound-detail-sub").text((item.customerName || "-") + " · " + MARIA.fmt.hyphenateAccountNo(item.accountNo));

        $("#inbound-detail-source").text(sourceLabel(item));
        $("#inbound-detail-processed-at").text(formatDateTime(item.processedAt));
        $("#inbound-detail-approved").text(formatQtyUnit(item.approvedQty));
        $("#inbound-detail-remaining").text(formatQtyUnit(item.remainingQty));

        // 0주 승인이면 사유를, 세 값의 최소와 승인수량이 다르면 가용수량 때문이라는 설명을 보인다.
        $("#inbound-zero-warning").prop("hidden", !isZero).text(isZero
            ? "이번 요청은 반려됐습니다 — " + STATUS.inboundZeroReasonLabel(STATUS.inboundZeroReason(item)) : "");
        var reduced = !isZero && min != null && item.approvedQty != null && round4(item.approvedQty) !== min &&
            round4(available) === round4(item.approvedQty);
        $("#inbound-reduced-note").prop("hidden", !reduced).text(reduced
            ? "12.23 기준수량에서 기존 승인 " + formatQtyUnit(priorSum) + "를 빼면 가용수량이 " +
            formatQtyUnit(available) + "라서 승인수량이 " + formatQtyUnit(item.approvedQty) + "로 줄었어요." : "");

        renderLots(item);

        $("#inbound-detail-snapshot").text(formatQtyUnit(item.snapshotQty));
        $("#inbound-detail-prior-sum").text(formatQtyUnit(priorSum));
        $("#inbound-detail-available").text(formatQtyUnit(available));
        setFold(false);
        loadPriorApprovals(item.inboundId);

        $("#inbound-drawer-backdrop, #inbound-detail-drawer").addClass("is-open");
        $("#inbound-detail-drawer").attr("aria-hidden", "false");
        $("#inbound-detail-drawer .adm-panel-body").scrollTop(0);
        $("#inbound-drawer-close").trigger("focus");
    }

    function closeDrawer() {
        if (!$("#inbound-detail-drawer").hasClass("is-open")) {
            return;
        }
        $("#inbound-drawer-backdrop, #inbound-detail-drawer").removeClass("is-open");
        $("#inbound-detail-drawer").attr("aria-hidden", "true");
        var $row = $("#inbound-list tr.is-selected").removeClass("is-selected");
        if ($row.length) {
            $row.trigger("focus");
        }
        selectedInboundId = null;
    }

    function selectRow($row) {
        var inboundId = Number($row.data("inbound-id"));
        var item = inbounds.filter(function (i) { return Number(i.inboundId) === inboundId; })[0];
        if (!item) {
            return;
        }
        selectedInboundId = inboundId;
        $("#inbound-list tr[data-inbound-id]").removeClass("is-selected");
        $row.addClass("is-selected");
        renderDrawer(item);
    }

    $("#inbound-list").on("click", "tr[data-inbound-id]", function () {
        selectRow($(this));
    });
    $("#inbound-list").on("keydown", "tr[data-inbound-id]", function (event) {
        if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            selectRow($(this));
        }
    });
    $("#inbound-list").on("click", ".inbound-retry", function () {
        loadAccountInbounds(inboundPage);
    });

    $("#inbound-drawer-close, #inbound-drawer-backdrop").on("click", closeDrawer);

    // 기존 승인 내역·가용수량 접기 (처음엔 접힘, 패널을 새로 열 때마다 다시 접는다)
    function setFold(open) {
        $("#inbound-fold-toggle").attr("aria-expanded", open ? "true" : "false");
        $("#inbound-fold-body").prop("hidden", !open);
    }
    $("#inbound-fold-toggle").on("click", function () {
        setFold($(this).attr("aria-expanded") !== "true");
    });

    // ---------- 계좌 선택 · 검색 ----------
    $("#inbound-account-list").on("click", ".adm-master-row", function () {
        var accountId = Number($(this).data("account-id"));
        if (accountId === Number(selectedAccountId)) {
            return;
        }
        selectedAccountId = accountId;
        closeDrawer();
        renderAccountList();
        loadAccountInbounds(0);
    });
    $("#inbound-account-list").on("click", ".inbound-account-retry", function () {
        loadAccounts(accountPage);
    });
    $("#inbound-account-list").on("click", ".inbound-account-empty-reset", resetSearch);

    function searchAccounts() {
        accountKeyword = $("#inbound-account-search").val().trim();
        loadAccounts(0);
    }

    function resetSearch() {
        accountKeyword = "";
        $("#inbound-account-search").val("");
        loadAccounts(0);
    }

    $("#inbound-account-search-submit").on("click", searchAccounts);
    $("#inbound-account-search-reset").on("click", resetSearch);
    $("#inbound-account-search").on("keydown", function (event) {
        if (event.key === "Enter") {
            searchAccounts();
        }
    });

    // 오른쪽 표 페이지 크기 (서버 size 파라미터)
    $("#inbound-page-size").on("click", "button", function () {
        var size = parseInt($(this).attr("data-size"), 10);
        if (!size || size === inboundPageSize) {
            return;
        }
        inboundPageSize = size;
        $("#inbound-page-size button").removeClass("is-on");
        $(this).addClass("is-on");
        closeDrawer();
        loadAccountInbounds(0);
    });

    // 좁은 화면(화면 너비 ÷ 글자 크기 배율 < 900, 원본 기준): 계좌 목록이 표 위로 올라가 한 줄로 쌓인다.
    // 글자 크기 배율은 layout.js 가 #admShell 의 --font-scale 로 정한다.
    function applyNarrowLayout() {
        var shell = document.getElementById("admShell");
        var zoom = shell ? parseFloat(getComputedStyle(shell).getPropertyValue("--font-scale")) || 1 : 1;
        $(".inbound-management").toggleClass("is-narrow", window.innerWidth / zoom < 900);
    }
    $(window).on("resize", applyNarrowLayout);
    if (window.MutationObserver && document.getElementById("admShell")) {
        new MutationObserver(applyNarrowLayout).observe(document.getElementById("admShell"), { attributes: true, attributeFilter: ["style"] });
    }
    applyNarrowLayout();

    var deepLinkAccountNo = MARIA.deeplink.accountNoFromUrl();
    if (deepLinkAccountNo) {
        accountKeyword = deepLinkAccountNo;
        $("#inbound-account-search").val(deepLinkAccountNo);
    }

    renderInboundLoading();
    loadSummary();
    loadAccounts(0);
});
