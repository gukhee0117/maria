$(function () {
    var QTY_FORMATTER = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 4 });
    var DATETIME_FORMATTER = new Intl.DateTimeFormat("ko-KR", {
        year: "numeric", month: "2-digit", day: "2-digit",
        hour: "2-digit", minute: "2-digit"
    });

    var accounts = [];
    var selectedAccountId = null;
    var selectedInboundId = null;
    var accountKeyword = "";
    var ACCOUNT_PAGE_SIZE = 10;

    var inbounds = [];
    var INBOUND_PAGE_SIZE = 10;

    function formatDateTime(isoString) {
        if (!isoString) {
            return "-";
        }
        return DATETIME_FORMATTER.format(new Date(isoString)).replace(/\. /g, "-").replace(".", "");
    }

    var DATE_FORMATTER = new Intl.DateTimeFormat("ko-KR", { year: "numeric", month: "2-digit", day: "2-digit" });
    var TIME_FORMATTER = new Intl.DateTimeFormat("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: true });

    function formatDateTwoLine(isoString) {
        if (!isoString) {
            return "-";
        }
        var date = new Date(isoString);
        var dateStr = DATE_FORMATTER.format(date).replace(/\. /g, "-").replace(/\.$/, "");
        var timeStr = TIME_FORMATTER.format(date);
        return '<div>' + dateStr + '</div><div class="ib-cell-sub">' + timeStr + '</div>';
    }

    function formatQty(qty) {
        return QTY_FORMATTER.format(qty || 0) + "주";
    }

    function escapeHtml(value) {
        return $("<div>").text(value).html();
    }

    function loadSummary() {
        MARIA.auth.ajax({ url: "/api/admin/inbounds/summary", method: "GET" })
            .done(function (res) {
                var s = res.data;
                $("#kpiTodayProcessed").text(s.todayProcessedCount + " 건");
                $("#kpiTodayRejected").text(s.todayRejectedCount + " 건");
                $("#kpiTodayReduced").text(s.todayReducedCount + " 건");
                $("#kpiTodayApprovedQty").text(formatQty(s.todayApprovedQtySum));
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
            });
    }

    function formatPrice(price, currency) {
        if (price == null) {
            return "-";
        }
        return Number(price).toLocaleString("ko-KR", { maximumFractionDigits: 4 }) + " " + (currency || "");
    }

    function lotProgressCell(lot) {
        var qty = Number(lot.qty) || 0;
        var currentQty = Number(lot.currentQty) || 0;
        var ratio = qty > 0 ? (currentQty / qty) : 0;
        var soldQty = qty - currentQty;
        var isDepleted = currentQty <= 0 && qty > 0;
        var note = soldQty > 0
            ? formatQty(soldQty) + " 소진 (" + Math.round((1 - ratio) * 100) + "%)"
            : "소진 없음";
        return (
            '<div class="ib-lot-progress">' +
            '<div class="ib-lot-progress-label">' + formatQty(currentQty) + ' / ' + formatQty(qty) + '</div>' +
            '<div class="ib-lot-progress-track"><div class="ib-lot-progress-fill' + (isDepleted ? " depleted" : "") + '" style="width:' + Math.round(ratio * 100) + '%"></div></div>' +
            '<div class="ib-lot-progress-note">' + note + '</div>' +
            '</div>'
        );
    }

    var SELL_STATUS_LABEL = { RECEIVED: "접수", EXECUTED: "체결", REJECTED: "거부" };

    var ACCOUNT_TYPE_LABEL = {
        BROKERAGE: "종합위탁계좌",
        CMA: "CMA",
        IRP: "IRP",
        PENSION_SAVINGS: "연금저축",
        ISA: "ISA"
    };

    function sourceLabel(lot) {
        if (lot.sourceBroker) {
            return escapeHtml(lot.sourceBroker);
        }
        if (lot.accountType && ACCOUNT_TYPE_LABEL[lot.accountType]) {
            return escapeHtml(ACCOUNT_TYPE_LABEL[lot.accountType]);
        }
        return "당사";
    }

    function sellHistoryCell(sellHistory) {
        if (!sellHistory || sellHistory.length === 0) {
            return '<span class="ib-sell-history-empty">없음</span>';
        }
        var items = sellHistory.map(function (order) {
            var label = SELL_STATUS_LABEL[order.status] || order.status;
            return (
                '<li>' + label + ' ' + formatQty(order.sellQty) +
                ' @ ' + formatPrice(order.basePrice, null) +
                ' · ' + formatDateTime(order.processedAt) +
                '</li>'
            );
        }).join("");
        return '<ul class="ib-sell-history">' + items + '</ul>';
    }

    function renderAccountList() {
        var $items = $("#ibAccountItems").empty();
        $("#ibAccountCount").text(accounts.length + "개 계좌");
        if (accounts.length === 0) {
            $items.append('<div class="dash-empty">입고 이력이 있는 계좌가 없습니다.</div>');
            return;
        }
        accounts.forEach(function (acc) {
            var isSelected = acc.accountId === selectedAccountId;
            var $row = $(
                '<div class="ib-list-item ib-account-item' + (isSelected ? " selected" : "") + '">' +
                '<div class="ib-list-item-top">' +
                '<span class="ib-list-item-name">' + escapeHtml(acc.customerName) + '</span>' +
                '<span class="ib-list-item-ticker">' + MARIA.fmt.hyphenateAccountNo(acc.accountNo) + '</span>' +
                '</div>' +
                '<div class="ib-list-item-bottom">' +
                '<span>입고 ' + acc.inboundCount + '건</span>' +
                '<span>최근 처리: ' + formatDateTime(acc.lastProcessedAt) + '</span>' +
                '</div>' +
                '</div>'
            );
            $row.on("click", function () {
                selectedAccountId = acc.accountId;
                renderAccountList();
                loadAccountInbounds(0);
            });
            $items.append($row);
        });
    }

    function renderAccountPagination(page) {
        renderPaginationInto($("#ibAccountPagination"), page.page, page.totalPages, function (targetPage) {
            loadAccounts(targetPage);
        });
    }

    function loadAccounts(page) {
        var targetPage = page || 0;
        var requestData = { page: targetPage, size: ACCOUNT_PAGE_SIZE };
        if (accountKeyword) {
            requestData.keyword = accountKeyword;
        }
        MARIA.auth.ajax({
            url: "/api/admin/inbounds/accounts",
            method: "GET",
            data: requestData
        })
            .done(function (res) {
                accounts = res.data.content || [];
                var totalPages = Math.ceil((res.data.totalCount || 0) / ACCOUNT_PAGE_SIZE);
                renderAccountList();
                renderAccountPagination({ page: res.data.page, totalPages: totalPages });

                if (accounts.length > 0) {
                    selectedAccountId = accounts[0].accountId;
                    renderAccountList();
                    loadAccountInbounds(0);
                } else {
                    selectedAccountId = null;
                    inbounds = [];
                    renderInboundList();
                    $("#ibInboundPagination").empty();
                }
                $("#ibLoading").hide();
                $("#ibBody").css("display", "flex");
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                $("#ibLoading").hide();
                var message = "계좌 목록을 불러오지 못했습니다.";
                if (xhr.responseJSON && xhr.responseJSON.message) {
                    message = xhr.responseJSON.message;
                }
                $("#ibError").text(message).show();
            });
    }

    function renderInboundList() {
        var $items = $("#ibInboundItems").empty();
        $("#ibInboundCount").text(inbounds.length + "건");
        if (!selectedAccountId) {
            $items.append('<div class="dash-empty">왼쪽에서 계좌를 선택하세요.</div>');
            return;
        }
        if (inbounds.length === 0) {
            $items.append('<div class="dash-empty">이 계좌의 입고 내역이 없습니다.</div>');
            return;
        }

        var rows = inbounds.map(function (item) {
            return (
                '<tr class="ib-inbound-row' +
                (Number(item.inboundId) === Number(selectedInboundId) ? ' selected' : '') +
                '" data-inbound-id="' + item.inboundId + '">' +
                '<td>' + escapeHtml(item.ticker || "-") + '<br><span class="ib-cell-sub">' + escapeHtml(item.productName || "-") + '</span></td>' +
                '<td>' + formatQty(item.requestedQty) + '</td>' +
                '<td>' + formatQty(item.snapshotQty) + '</td>' +
                '<td>' + formatQty(item.currentHoldingAtRequest) + '</td>' +
                '<td>' + (item.approvedQty === 0
                    ? '<span class="status-badge failed">0주 승인</span>'
                    : formatQty(item.approvedQty)) + '</td>' +
                '<td>' + formatQty(item.remainingQty) + '</td>' +
                '<td>' + (item.sourceBroker ? escapeHtml(item.sourceBroker) : "당사") + '</td>' +
                '<td>' + formatDateTime(item.processedAt) + '</td>' +
                '</tr>'
            );
        }).join("");

        var $table = $(
            '<table class="dash-table ib-inbound-table">' +
            '<thead><tr>' +
            '<th>종목</th><th>신청수량</th><th>기준일수량</th><th>현재보유수량</th>' +
            '<th>승인수량</th><th>잔여가능수량</th><th>출처</th><th>처리일시</th>' +
            '</tr></thead>' +
            '<tbody>' + rows + '</tbody>' +
            '</table>'
        );
        $items.append($table);

        $table.find("tbody tr").on("click", function () {
            var inboundId = Number($(this).data("inbound-id"));
            var item = inbounds.filter(function (i) { return i.inboundId === inboundId; })[0];
            if (item) {
                selectedInboundId = inboundId;
                renderInboundList();
                openDetailOverlay(item);
            }
        });
    }

        function renderInboundPagination(page) {
        renderPaginationInto($("#ibInboundPagination"), page.page, page.totalPages, function (targetPage) {
            loadAccountInbounds(targetPage);
        });
    }

    function loadAccountInbounds(page) {
        if (!selectedAccountId) {
            return;
        }
        var targetPage = page || 0;
        MARIA.auth.ajax({
            url: "/api/admin/inbounds/by-account/" + selectedAccountId,
            method: "GET",
            data: { page: targetPage, size: INBOUND_PAGE_SIZE }
        })
            .done(function (res) {
                inbounds = res.data.content || [];
                renderInboundList();
                renderInboundPagination(res.data);
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                var message = "입고 내역을 불러오지 못했습니다.";
                if (xhr.responseJSON && xhr.responseJSON.message) {
                    message = xhr.responseJSON.message;
                }
                $("#ibError").text(message).show();
            });
    }

    function renderPaginationInto($pagination, current, totalPages, onClick) {
        $pagination.empty();
        if (!totalPages || totalPages <= 1) {
            return;
        }

        var BLOCK_SIZE = 10;
        var blockStart = Math.floor(current / BLOCK_SIZE) * BLOCK_SIZE;
        var blockEnd = Math.min(totalPages - 1, blockStart + BLOCK_SIZE - 1);

        function addButton(label, targetPage, isDisabled, isActive) {
            var classes = "page-btn" + (isActive ? " active" : "");
            var $btn = $('<button type="button" class="' + classes + '">' + label + "</button>");
            $btn.prop("disabled", isDisabled || isActive);
            if (!isDisabled && !isActive) {
                $btn.on("click", function () {
                    onClick(targetPage);
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

    function minCard(label, value, isMatched, note) {
        var cls = "ib-min-card" + (isMatched ? " matched" : "");
        return (
            '<div class="' + cls + '">' +
            (isMatched ? '<span class="ib-min-badge">MIN 채택</span>' : "") +
            '<div class="ib-min-label">' + label + '</div>' +
            '<div class="ib-min-value">' + formatQty(value) + '</div>' +
            '<div class="ib-min-note">' + note + '</div>' +
            '</div>'
        );
    }

    function zeroApprovalReason(item) {
        if (item.currentHoldingAtRequest === 0) {
            return "요청 시점 기준 현재 보유수량이 0주라 입고할 자산이 없습니다.";
        }
        if (item.requestedQty === 0) {
            return "신청수량 자체가 0주로 접수됐습니다.";
        }
        return "이 계좌·종목으로 이미 승인된 누적수량이 12.23 기준수량을 다 채웠습니다.";
    }

    function loadPriorApprovals(inboundId, $container) {
        $container.append('<div class="section-header"><span>이 계좌·종목 기존 승인 내역</span></div>');
        MARIA.auth.ajax({ url: "/api/admin/inbounds/" + inboundId + "/prior-approvals", method: "GET" })
            .done(function (res) {
                var rows = res.data || [];
                if (rows.length === 0) {
                    $container.append('<div class="dash-empty">기존 승인 내역이 없습니다.</div>');
                    return;
                }
                var trs = rows.map(function (r) {
                    return (
                        '<tr>' +
                        '<td>' + formatDateTime(r.processedAt) + '</td>' +
                        '<td>' + formatQty(r.requestedQty) + '</td>' +
                        '<td>' + formatQty(r.approvedQty) + '</td>' +
                        '</tr>'
                    );
                }).join("");
                $container.append(
                    '<table class="dash-table ib-info-table">' +
                    '<thead><tr><th>처리일시</th><th>신청수량</th><th>승인수량</th></tr></thead>' +
                    '<tbody>' + trs + '</tbody>' +
                    '</table>'
                );
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                $container.append('<div class="dash-empty">기존 승인 내역을 불러오지 못했습니다.</div>');
            });
    }

    function openDetailOverlay(item) {
        var $body = $("#ibOverlayBody").empty();
        var $detail = $('<div class="ib-detail-card"></div>');
        $body.append($detail);

        var isZeroApproved = item.approvedQty === 0;
        var availableQty = item.remainingQty + item.approvedQty;
        $detail.append(
            '<div class="section-header">' +
            '<span>3-way MIN 계산 — ' + MARIA.fmt.accountNoHtml(item.accountNo) + ' · ' + escapeHtml(item.customerName) + ' · ' + escapeHtml(item.ticker || item.productName) + '</span>' +
            '<span class="section-sub' + (isZeroApproved ? " ib-zero-text" : "") + '">최종 채택: ' + formatQty(item.approvedQty) + '</span>' +
            '</div>' +
            (isZeroApproved
                ? '<div class="ib-zero-warning">이번 요청은 반려됐습니다 — ' + zeroApprovalReason(item) + '</div>'
                : "")
        );

        $detail.append(
            '<div class="ib-min-grid">' +
            minCard("1. 신청수량", item.requestedQty, item.requestedQty === item.approvedQty, "고객 입고 신청 수량") +
            minCard("2. 가용수량(기준수량 - 기승인)", availableQty, availableQty === item.approvedQty, "12.23 기준수량 " + formatQty(item.snapshotQty) + " 중<br>이미 승인된 수량 차감") +
            minCard("3. 현재보유수량", item.currentHoldingAtRequest, item.currentHoldingAtRequest === item.approvedQty, "요청시점 실보유수량") +
            '</div>'
        );

        $detail.append(
            '<div class="ib-min-formula">' +
            'MIN(' + formatQty(item.requestedQty) + ', ' + formatQty(availableQty) + ', ' + formatQty(item.currentHoldingAtRequest) + ') = ' +
            '<strong>' + formatQty(item.approvedQty) + '</strong>' +
            '</div>'
        );

        var alreadyApprovedQty = item.snapshotQty - availableQty;
        $detail.append(
            '<div class="section-header"><span>3-way MIN 근거 — 가용수량 분해</span></div>' +
            '<table class="dash-table ib-info-table">' +
            '<thead><tr><th>항목</th><th>수량</th></tr></thead>' +
            '<tbody>' +
            '<tr><td>12.23 기준수량 (한도)</td><td>' + formatQty(item.snapshotQty) + '</td></tr>' +
            '<tr><td>이 계좌·종목 기존 승인 누적</td><td>' + formatQty(alreadyApprovedQty) + '</td></tr>' +
            '<tr><td><strong>가용수량 (기준수량 − 기존승인)</strong></td><td><strong>' + formatQty(availableQty) + '</strong></td></tr>' +
            '</tbody>' +
            '</table>'
        );

        var $priorSection = $('<div></div>');
        $detail.append($priorSection);
        loadPriorApprovals(item.inboundId, $priorSection);

        $detail.append(
            '<div class="section-header"><span>기본 정보</span></div>' +
            '<table class="dash-table ib-info-table">' +
            '<thead><tr>' +
            '<th>계좌번호</th><th>고객명</th><th>종목</th><th>출처</th><th>처리일시</th><th>잔여가능수량</th>' +
            '</tr></thead>' +
            '<tbody><tr>' +
            '<td>' + MARIA.fmt.accountNoHtml(item.accountNo) + '</td>' +
            '<td>' + escapeHtml(item.customerName) + '</td>' +
            '<td>' + escapeHtml(item.ticker || "-") + ' (' + escapeHtml(item.productName || "-") + ')</td>' +
            '<td>' + (item.sourceBroker ? escapeHtml(item.sourceBroker) : "-") + '</td>' +
            '<td>' + formatDateTime(item.processedAt) + '</td>' +
            '<td><strong>' + formatQty(item.remainingQty) + '</strong></td>' +
            '</tr></tbody>' +
            '</table>'
        );

        if (item.lots && item.lots.length > 0) {
            var lotRows = item.lots.map(function (lot) {
                return (
                    '<tr>' +
                    '<td>' + sourceLabel(lot) + '</td>' +
                    '<td>' + formatDateTwoLine(lot.purchaseDate) + '</td>' +
                    '<td>' + formatDateTwoLine(lot.recordedAt) + '</td>' +
                    '<td class="ib-price-cell">' + formatPrice(lot.purchasePrice, lot.purchaseCurrency) + '</td>' +
                    '<td>' + lotProgressCell(lot) + '</td>' +
                    '<td>' + sellHistoryCell(lot.sellHistory) + '</td>' +
                    '</tr>'
                );
            }).join("");

            $detail.append(
                '<div class="section-header">' +
                '<span>취득 정보</span>' +
                '<span class="section-sub">lot ' + item.lots.length + '건</span>' +
                '</div>' +
                '<table class="dash-table ib-lot-table">' +
                '<thead><tr><th>출처</th><th>매수일</th><th>기록일</th><th>매수단가</th><th>보유 현황</th><th>매도 이력</th></tr></thead>' +
                '<tbody>' + lotRows + '</tbody>' +
                '</table>'
            );
        }

        $("#ibOverlayBackdrop").prop("hidden", false);
        requestAnimationFrame(function () {
            $("#ibOverlayBackdrop").addClass("is-open");
            $("#ibOverlayPanel").addClass("is-open");
        });
    }

    function closeDetailOverlay() {
        $("#ibOverlayBackdrop").removeClass("is-open");
        $("#ibOverlayPanel").removeClass("is-open");
        setTimeout(function () {
            $("#ibOverlayBackdrop").prop("hidden", true);
        }, 200);
    }

    $("#ibOverlayClose").on("click", closeDetailOverlay);
    $("#ibOverlayBackdrop").on("click", closeDetailOverlay);
    $(document).on("keydown", function (e) {
        if (e.key === "Escape") {
            closeDetailOverlay();
        }
    });

    $("#ibAccountSearchSubmit").on("click", function () {
        accountKeyword = $("#ibAccountSearch").val().trim();
        loadAccounts(0);
    });
    $("#ibAccountSearchReset").on("click", function () {
        accountKeyword = "";
        $("#ibAccountSearch").val("");
        loadAccounts(0);
    });
    $("#ibAccountSearch").on("keypress", function (e) {
        if (e.which === 13) {
            accountKeyword = $(this).val().trim();
            loadAccounts(0);
        }
    });

    var deepLinkAccountNo = MARIA.deeplink.accountNoFromUrl();
    if (deepLinkAccountNo) {
        accountKeyword = deepLinkAccountNo;
        $("#ibAccountSearch").val(deepLinkAccountNo);
    }

    loadSummary();
    loadAccounts(0);
});
