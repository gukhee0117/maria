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
    var PAGE_SIZE = 6;

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

    function statusClass(status) {
        return (status || "").toLowerCase();
    }

    function showError(message) {
        MARIA.ui.showError(message);
    }

    function canProcessClosure() {
        var admin = MARIA.auth.currentAdmin();
        return !!admin && (admin.role === "ADMIN" || admin.role === "REVIEWER");
    }

    function renderList() {
        var $list = $("#closure-list").empty();
        $("#closure-count").text("총 " + closures.length + "건");

        if (!closures.length) {
            $list.append('<div class="closure-empty">해당 상태의 해지 신청이 없습니다.</div>');
            $("#closure-pagination").prop("hidden", true);
            clearDetail();
            return;
        }

        var totalPages = Math.max(1, Math.ceil(closures.length / PAGE_SIZE));
        currentPage = Math.min(currentPage, totalPages);
        var startIndex = (currentPage - 1) * PAGE_SIZE;
        var pageClosures = closures.slice(startIndex, startIndex + PAGE_SIZE);

        pageClosures.forEach(function (closure) {
            var selectedClass =
                Number(closure.closureRequestId) === Number(selectedClosureId)
                    ? " is-selected"
                    : "";
            $list.append(
                '<button type="button" class="closure-list-item' + selectedClass + '"' +
                ' data-closure-id="' + closure.closureRequestId + '">' +
                '<span class="closure-list-primary">' +
                '<span>' + escapeHtml(closure.customerName || "-") + '</span>' +
                '<span class="closure-status-badge ' + statusClass(closure.status) + '">' +
                escapeHtml(statusLabel(closure.status)) + '</span></span>' +
                '<span class="closure-list-secondary">' +
                '<span>RIA ' + MARIA.fmt.accountNoHtml(closure.accountNo) + '</span>' +
                '<span>' + escapeHtml(formatDateTime(closure.requestedAt)) + '</span>' +
                '</span></button>'
            );
        });

        $("#closure-page-info").text(currentPage + " / " + totalPages);
        $("#previous-closure-page").prop("disabled", currentPage === 1);
        $("#next-closure-page").prop("disabled", currentPage === totalPages);
        $("#closure-pagination").prop("hidden", false);
    }

    function clearDetail() {
        selectedClosureId = null;
        $("#closure-detail").addClass("is-empty");
        $("#closure-detail-empty").show();
        $("#closure-detail-content").prop("hidden", true);
    }

    function renderDetail(closure) {
        selectedClosureId = Number(closure.closureRequestId);
        $("#closure-detail").removeClass("is-empty");
        $("#closure-detail-empty").hide();
        $("#closure-detail-content").prop("hidden", false);
        $("#detail-request-title").text((closure.customerName || "-") + " 고객 해지 신청");
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
        $("#detail-status")
            .attr("class", "closure-status-badge " + statusClass(closure.status))
            .text(statusLabel(closure.status));

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
        $("#closure-list").html('<div class="closure-loading">불러오는 중...</div>');
        $("#search-button").prop("disabled", true);

        MARIA.auth.ajax({
            url: "/api/admin/account-closures",
            method: "GET",
            data: { status: status }
        })
            .done(function (response) {
                closures = response.data || [];
                currentPage = 1;
                if (closures.length) {
                    selectedClosureId = Number(closures[0].closureRequestId);
                    renderList();
                    loadDetail(selectedClosureId);
                } else {
                    selectedClosureId = null;
                    renderList();
                }
            })
            .fail(function (xhr) {
                closures = [];
                renderList();
                if (xhr.status !== 401) {
                    showError((xhr.responseJSON && xhr.responseJSON.message) || "해지 신청 목록을 불러오지 못했습니다.");
                }
            })
            .always(function () {
                $("#search-button").prop("disabled", false);
            });
    }

    function processClosure(action) {
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
    $(document).on("click", ".closure-list-item", function () {
        loadDetail(Number($(this).data("closure-id")));
    });
    $("#previous-closure-page").on("click", function () {
        if (currentPage > 1) {
            currentPage -= 1;
            selectFirstClosureOnCurrentPage();
        }
    });
    $("#next-closure-page").on("click", function () {
        var totalPages = Math.max(1, Math.ceil(closures.length / PAGE_SIZE));
        if (currentPage < totalPages) {
            currentPage += 1;
            selectFirstClosureOnCurrentPage();
        }
    });
    $("#approve-button").on("click", function () {
        processClosure("approve");
    });
    $("#reject-button").on("click", function () {
        processClosure("reject");
    });

    loadClosures();

    function selectFirstClosureOnCurrentPage() {
        var firstIndex = (currentPage - 1) * PAGE_SIZE;
        var firstClosure = closures[firstIndex];

        if (!firstClosure) {
            renderList();
            clearDetail();
            return;
        }

        selectedClosureId = Number(firstClosure.closureRequestId);
        renderList();
        loadDetail(selectedClosureId);
    }
});
