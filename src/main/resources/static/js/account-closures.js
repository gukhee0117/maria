$(function () {
    var STATUS_LABEL = {
        REQUESTED: "해지 신청",
        COMPLETED: "해지 완료",
        REJECTED: "반려"
    };
    var DATE_TIME_FORMATTER = new Intl.DateTimeFormat("ko-KR", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit"
    });
    var closures = [];
    var selectedClosureId = null;
    var currentPage = 1;
    var PAGE_SIZE = 20;
    var COLUMN_COUNT = 5;
    var sortSpec = null; // 머리글로 고른 정렬 (받아 온 목록 전체를 정렬한다)
    var TONE = { REQUESTED: "warn", COMPLETED: "ok", REJECTED: "off" };

    function escapeHtml(value) {
        return $("<div>").text(value == null ? "" : value).html();
    }

    function formatDateTime(value) {
        return value ? DATE_TIME_FORMATTER.format(new Date(value)) : "-";
    }

    function formatAmount(value) {
        return new Intl.NumberFormat("ko-KR").format(Number(value || 0)) + "원";
    }

    function statusLabel(status) {
        return STATUS_LABEL[status] || status || "-";
    }

    function showError(message) {
        MARIA.ui.showError(message);
    }

    function canProcessClosure() {
        return MARIA.auth.hasRole("REVIEWER");
    }

    function statusChip(status) {
        return '<span class="adm-chip adm-chip-' + (TONE[status] || "off") + '">' + escapeHtml(statusLabel(status)) + "</span>";
    }

    function stateRow(innerHtml) {
        return '<tr class="is-state"><td colspan="' + COLUMN_COUNT + '">' + innerHtml + "</td></tr>";
    }

    function sortedClosures() {
        if (!sortSpec) {
            return closures;
        }
        return MARIA.tableSort.sortList(closures, function (c) { return c[sortSpec.key]; }, sortSpec.dir);
    }

    function renderList() {
        var $body = $("#closure-list").empty();
        $("#closure-count").text(closures.length);

        if (!closures.length) {
            $body.append(stateRow('<div class="adm-state-empty">' +
                '<img src="/images/admin/char-front.png" alt="">' +
                '<div class="adm-state-empty-title">해당 상태의 해지 신청이 없습니다.</div></div>'));
            $("#closure-pagination").empty();
            return;
        }

        var totalPages = Math.max(1, Math.ceil(closures.length / PAGE_SIZE));
        currentPage = Math.min(currentPage, totalPages);
        var startIndex = (currentPage - 1) * PAGE_SIZE;
        var pageClosures = sortedClosures().slice(startIndex, startIndex + PAGE_SIZE);

        pageClosures.forEach(function (closure, index) {
            var selected = Number(closure.closureRequestId) === Number(selectedClosureId) ? " is-selected" : "";
            $body.append(
                '<tr class="is-clickable' + selected + '" tabindex="0" data-closure-id="' + closure.closureRequestId + '">' +
                '<td class="adm-col-no">' + (startIndex + index + 1) + "</td>" +
                "<td><span>" + MARIA.fmt.accountNoHtml(closure.accountNo) + "</span></td>" +
                '<td class="closure-col-name"><span>' + escapeHtml(closure.customerName || "-") + "</span></td>" +
                "<td><span>" + escapeHtml(formatDateTime(closure.requestedAt)) + "</span></td>" +
                '<td class="is-c">' + statusChip(closure.status) + "</td></tr>"
            );
        });
        renderPager(totalPages);
    }

    function renderPager(totalPages) {
        var $pager = $("#closure-pagination").empty();
        if (totalPages <= 1) {
            return;
        }
        var BLOCK = 10;
        var blockStart = Math.floor((currentPage - 1) / BLOCK) * BLOCK + 1;
        var blockEnd = Math.min(totalPages, blockStart + BLOCK - 1);

        function add(label, page, disabled, active) {
            var $b = $('<button type="button" class="adm-page-btn' + (active ? " is-active" : "") +
                (/^[0-9]+$/.test(label) ? "" : " is-nav") + '">' + label + "</button>");
            $b.prop("disabled", disabled || active);
            if (!disabled && !active) {
                $b.on("click", function () {
                    currentPage = page;
                    closeDrawer();
                    renderList();
                });
            }
            $pager.append($b);
        }
        if (blockStart > 1) {
            add("이전", blockStart - 1, false, false);
        }
        for (var n = blockStart; n <= blockEnd; n++) {
            add(String(n), n, false, n === currentPage);
        }
        if (blockEnd < totalPages) {
            add("다음", blockEnd + 1, false, false);
        }
    }

    function formatNumber(value) {
        return value == null ? "-" : new Intl.NumberFormat("ko-KR").format(Math.trunc(Number(value)));
    }

    function openDrawer() {
        $("#closure-drawer-backdrop, #closure-detail").addClass("is-open");
        $("#closure-detail").attr("aria-hidden", "false");
        $("#closure-detail .adm-panel-body").scrollTop(0);
        $("#closure-drawer-close").trigger("focus");
    }

    function closeDrawer() {
        if (!$("#closure-detail").hasClass("is-open")) {
            return;
        }
        $("#closure-drawer-backdrop, #closure-detail").removeClass("is-open");
        $("#closure-detail").attr("aria-hidden", "true");
        selectedClosureId = null;
        $("#closure-list tr.is-selected").removeClass("is-selected");
    }

    function renderDetail(closure) {
        selectedClosureId = Number(closure.closureRequestId);
        $("#detail-request-title").text((closure.customerName || "-") + " 님");
        $("#detail-customer-name").text(closure.customerName || "-");
        $("#detail-account-no").html(MARIA.fmt.accountNoHtml(closure.accountNo));
        $("#detail-account-amount").text(formatAmount(closure.accountAmount));
        $("#detail-destination-id").text(closure.destinationGeneralAccountId);
        $("#detail-requested-at").text(formatDateTime(closure.requestedAt));
        $("#detail-early-agreed").text(closure.earlyWithdrawalAgreed ? "동의함" : "동의하지 않음");
        if (closure.status === "COMPLETED") {
            $("#detail-immature-label").text("실제 미경과 인출액");
            $("#detail-immature-principal").text(formatAmount(closure.immaturePrincipalAmount));
            $("#detail-tax-label").text("세제혜택 처리 결과");
            $("#detail-tax-impact").text(
                closure.taxBenefitCancellationOccurred ? "전체 취소 발생" : "취소 없음"
            );
        } else if (closure.status === "REJECTED") {
            $("#detail-immature-label").text("실제 미경과 인출액");
            $("#detail-immature-principal").text("인출 없음");
            $("#detail-tax-label").text("세제혜택 처리 결과");
            $("#detail-tax-impact").text("취소 없음");
        } else {
            $("#detail-immature-label").text("현재 미경과 원금");
            $("#detail-immature-principal").text(
                closure.hasImmaturePrincipal
                    ? formatAmount(closure.immaturePrincipalAmount) + " 보유"
                    : "없음"
            );
            $("#detail-tax-label").text("세제혜택 예상 영향");
            $("#detail-tax-impact").text(
                closure.taxBenefitCancellationExpected ? "승인 시 전체 취소" : "영향 없음"
            );
        }
        $("#detail-status").text(statusLabel(closure.status));

        var hasTaxImpact = closure.taxBenefitCancellationExpected ||
            closure.taxBenefitCancellationOccurred;
        $("#early-withdrawal-warning").prop("hidden", !hasTaxImpact);
        $("#early-withdrawal-not-agreed").prop("hidden", hasTaxImpact);
        if (closure.taxBenefitCancellationOccurred) {
            $("#early-withdrawal-warning strong").text("조기인출로 세제혜택이 전체 취소되었습니다.");
            $("#early-withdrawal-warning p").text(
                "완료된 강제인출 내역에 1년 미경과 원금이 포함되어 있습니다."
            );
        } else {
            $("#early-withdrawal-warning strong").text("승인 시 세제혜택이 전체 취소됩니다.");
            $("#early-withdrawal-warning p").text(
                "현재 잔액에 1년 미경과 원금이 포함되어 강제인출 시 조기인출로 처리됩니다."
            );
        }
        $("#rejection-reason").val("");

        var editable = closure.status === "REQUESTED" && canProcessClosure();
        $("#closure-actions").toggle(editable);
        $("#closure-readonly-notice").prop("hidden", editable);
        renderList();
        openDrawer();
    }

    function loadDetail(closureRequestId) {
        MARIA.auth.ajax({
            url: "/api/admin/account-closures/" + closureRequestId,
            method: "GET"
        })
            .done(function (response) {
                renderDetail(response.data);
            })
            .fail(function (xhr) {
                if (xhr.status !== 401) {
                    showError((xhr.responseJSON && xhr.responseJSON.message) || "해지 신청 상세를 불러오지 못했습니다.");
                }
            });
    }

    function loadClosures() {
        var status = $("#closure-status").val();
        closeDrawer();
        $("#closure-list").html(stateRow("불러오는 중..."));
        $("#search-button").prop("disabled", true);

        MARIA.auth.ajax({
            url: "/api/admin/account-closures",
            method: "GET",
            data: { status: status }
        })
            .done(function (response) {
                closures = response.data || [];
                currentPage = 1;
                selectedClosureId = null;
                renderList();
            })
            .fail(function (xhr) {
                closures = [];
                renderList();
                if (xhr.status === 403) {
                    $("#closure-list").html(stateRow("접근 권한이 없습니다. 해지 신청은 심사담당 이상만 볼 수 있습니다."));
                    $("#closure-count").text("-");
                }
                if (xhr.status !== 401) {
                    showError((xhr.responseJSON && xhr.responseJSON.message) || "해지 신청 목록을 불러오지 못했습니다.");
                }
            })
            .always(function () {
                $("#search-button").prop("disabled", false);
            });
    }

    function processClosure(action) {
        if (!canProcessClosure()) {
            showError("계좌 해지 요청을 처리할 권한이 없습니다.");
            return;
        }
        if (!selectedClosureId) {
            showError("처리할 해지 신청을 선택해 주세요.");
            return;
        }

        var options = {
            url: "/api/admin/account-closures/" + selectedClosureId + "/" + action,
            method: "POST"
        };

        if (action === "reject") {
            var reason = ($("#rejection-reason").val() || "").trim();
            if (!reason) {
                showError("반려 사유를 입력해 주세요.");
                return;
            }
            options.contentType = "application/json";
            options.data = JSON.stringify({ reason: reason });
        }

        $("#approve-button, #reject-button").prop("disabled", true);
        MARIA.auth.ajax(options)
            .done(function () {
                loadClosures();
            })
            .fail(function (xhr) {
                if (xhr.status !== 401) {
                    showError((xhr.responseJSON && xhr.responseJSON.message) || "해지 신청 처리에 실패했습니다.");
                }
            })
            .always(function () {
                $("#approve-button, #reject-button").prop("disabled", false);
            });
    }

    $("#search-button").on("click", loadClosures);
    $("#closure-list").on("click", "tr[data-closure-id]", function () {
        var id = Number($(this).data("closure-id"));
        selectedClosureId = id;
        $("#closure-list tr.is-selected").removeClass("is-selected");
        $(this).addClass("is-selected");
        loadDetail(id);
    });
    $("#closure-list").on("keydown", "tr[data-closure-id]", function (e) {
        if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            $(this).trigger("click");
        }
    });
    $("#closure-drawer-close, #closure-drawer-backdrop").on("click", closeDrawer);
    MARIA.tableSort.bind(".closure-table", function (key, dir) {
        sortSpec = { key: key, dir: dir };
        currentPage = 1;
        closeDrawer();
        renderList();
    });
    $("#approve-button").on("click", function () {
        processClosure("approve");
    });
    $("#reject-button").on("click", function () {
        processClosure("reject");
    });

    // 인증 정보가 준비된 뒤 보호 데이터를 조회하고 역할별 UI를 렌더링한다.
    MARIA.auth.requireAuth().done(function () {
        loadClosures();
    });
});
