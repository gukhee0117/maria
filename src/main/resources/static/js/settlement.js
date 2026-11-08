$(function () {
    var STATUS = MARIA.status; // 배치 상태·항목 결과·실패 사유 표기는 js/admin/status-labels.js 의 공통 매핑

    var INT_FORMATTER = new Intl.NumberFormat("ko-KR");
    var RATE_FORMATTER = new Intl.NumberFormat("ko-KR", { minimumFractionDigits: 2, maximumFractionDigits: 6 });

    var PAGE_SIZE = 20;
    var currentPage = 0;
    var SKELETON_ROWS = 6;
    var SKELETON_WIDTHS = ["62%", "70%", "48%", "54%", "66%", "78%", "56%", "40%", "60%"];
    var LIST_COLUMNS = 8;
    var FAIL_COLUMNS = 9;
    var MAX_LOG_LINES = 200;

    var allBatches = [];         // 받아 온 배치 목록 전체 (요약 집계 기준)
    var filteredBatches = [];    // 검색어·상태까지 거른 결과
    var sortSpec = null;         // 머리글로 고른 정렬 (받아 온 목록 전체를 정렬한다)
    var businessToday = null;    // 업무일자(GET /api/admin/system-clock) — "오늘" 기준
    var summaryLoaded = false;
    var selectedBatchId = null;
    var failItems = [];          // 선택한 배치의 실패 항목
    var failLoaded = false;
    var itemScope = "fail";      // 하단 표 범위: fail(실패) / all(전체) / pending(처리대기)
    var scopeItems = [];         // all·pending 범위의 항목
    var scopeLoaded = false;
    var scopeError = null;
    var scopeHasMore = false;
    var scopeSeq = 0;
    var failError = null;
    var liveLines = [];          // 실시간 알림(SSE)으로 쌓인 로그
    var failRequestSeq = 0;
    var selectedItemId = null;
    var canAct = false;          // ADMIN·SETTLEMENT 만 처리 버튼을 본다 (API 의 @PreAuthorize 와 같음)
    var acting = false;          // 처리 요청 중
    var currentItem = null;      // 항목 상세 패널에 열린 항목
    var pendingAction = null;    // 확인 창에서 기다리는 처리
    var confirmReturnFocus = null;

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

    function formatTime(value) {
        var date = parseDate(value);
        return !date ? "--:--:--" : pad(date.getHours()) + ":" + pad(date.getMinutes()) + ":" + pad(date.getSeconds());
    }

    function formatCount(value) {
        return value == null ? "-" : INT_FORMATTER.format(Number(value));
    }

    // 금액은 숫자만 (단위는 열 이름이 말해 준다). 값이 없으면 "-".
    function formatAmount(value) {
        return value == null ? "-" : INT_FORMATTER.format(Math.trunc(Number(value)));
    }

    function formatRate(value) {
        return value == null ? "-" : RATE_FORMATTER.format(Number(value));
    }

    function chip(tone, label, title) {
        return '<span class="adm-chip adm-chip-' + tone + '"' + (title ? ' title="' + escapeHtml(title) + '"' : "") + ">" +
            escapeHtml(label) + "</span>";
    }

    function batchChip(batch) {
        var kind = STATUS.settlementBatchKind(batch);
        return kind ? chip(STATUS.settlementBatchTone(kind), STATUS.settlementBatchLabel(kind), batch.failureMessage) : "-";
    }

    function batchLabel(batchId) {
        return "#" + batchId;
    }

    function productLabel(item) {
        return [item.productName, item.ticker].filter(Boolean).join(" ") || "-";
    }

    function errorMessage(xhr, fallback) {
        return (xhr.responseJSON && xhr.responseJSON.message) || fallback;
    }

    function findBatch(batchId) {
        return allBatches.filter(function (batch) {
            return Number(batch.batchId) === Number(batchId);
        })[0] || null;
    }

    // ---------- 조회조건 목록 ----------
    ["RUNNING", "COMPLETED", "PARTIAL", "FAILED"].forEach(function (kind) {
        $("#settlement-filter-status").append($("<option>", { value: kind, text: STATUS.settlementBatchLabel(kind) }));
    });

    // ---------- 요약 (배치 목록 전체를 화면에서 센다) ----------
    function renderSummary() {
        if (!summaryLoaded) {
            return;
        }
        var todayText = businessToday ? formatDate(businessToday) : null;
        var runs = 0;
        var success = 0;
        var failed = 0;
        var latest = null;
        allBatches.forEach(function (batch) {
            var at = parseDate(batch.executedAt);
            var latestAt = latest ? parseDate(latest.executedAt) : null;
            if (at && (!latestAt || at > latestAt || (at.getTime() === latestAt.getTime() && Number(batch.batchId) > Number(latest.batchId)))) {
                latest = batch;
            }
            if (todayText && formatDate(batch.executedAt) === todayText) {
                runs += 1;
                success += Number(batch.successCount || 0);
                failed += Number(batch.failedCount || 0);
            }
        });
        $("#settlement-summary-runs").text(todayText ? formatCount(runs) : "-");
        $("#settlement-summary-success").text(todayText ? formatCount(success) : "-");
        $("#settlement-summary-failed").text(todayText ? formatCount(failed) : "-");
        // 재처리 대기 = 가장 최근에 실행된 배치의 실패 건수 (#64)
        $("#settlement-summary-retry").text(latest ? formatCount(latest.failedCount) : "-");
    }

    function clearSummary() {
        $("#settlement-summary-runs, #settlement-summary-success, #settlement-summary-failed, #settlement-summary-retry").text("-");
    }

    // ---------- 실행 현황 ----------
    function renderRunPanel() {
        var batch = selectedBatchId == null ? null : findBatch(selectedBatchId);
        if (!batch) {
            $("#settlement-run-id").text("-").removeAttr("title");
            $("#settlement-run-chip").html("");
            $("#settlement-run-text").text("실행 내역이 없습니다.");
            $("#settlement-run-pct").text("-");
            $("#settlement-run-fill").css("transform", "scaleX(0)");
            $("#settlement-run-start, #settlement-run-success, #settlement-run-failed").text("-");
            return;
        }
        var total = Number(batch.totalCount || 0);
        var processed = Number(batch.processedCount || 0);
        var ratio = total > 0 ? Math.max(0, Math.min(1, processed / total)) : 0;
        $("#settlement-run-id").text(batchLabel(batch.batchId)).attr("title", batch.runId ? "Run ID " + batch.runId : "");
        $("#settlement-run-chip").html(batchChip(batch));
        $("#settlement-run-text").text(formatCount(total) + "건 중 " + formatCount(processed) + "건 처리 (성공 " +
            formatCount(batch.successCount) + " / 실패 " + formatCount(batch.failedCount) + ")");
        $("#settlement-run-pct").text(Math.round(ratio * 100) + "%");
        $("#settlement-run-fill").css("transform", "scaleX(" + ratio + ")").attr("data-ratio", ratio);
        $("#settlement-run-start").text(formatDateTime(batch.executedAt));
        $("#settlement-run-success").text(formatCount(batch.successCount));
        $("#settlement-run-failed").text(formatCount(batch.failedCount));
    }

    // ---------- 실행 로그 ----------
    // 서버에 로그 저장·조회 API 가 없어(#62) 받은 데이터로 한 줄씩 만든다:
    // 시작(실행 일시) → 실패 항목마다 경고 → 종료 요약, 그 뒤에 이 화면이 켜져 있는 동안 받은 실시간 알림이 이어진다.
    function composeLog() {
        var lines = [];
        var batch = selectedBatchId == null ? null : findBatch(selectedBatchId);
        if (batch) {
            lines.push({ time: formatTime(batch.executedAt), level: "INFO",
                text: "배치 " + batchLabel(batch.batchId) + " 시작 · 전체 " + formatCount(batch.totalCount) + "건" });
            failItems.slice().sort(function (left, right) {
                return new Date(left.processedAt) - new Date(right.processedAt);
            }).forEach(function (item) {
                lines.push({ time: formatTime(item.processedAt), level: "WARN",
                    text: "Item " + batchLabel(item.itemId) + " " + MARIA.fmt.hyphenateAccountNo(item.accountNo) + " " +
                    STATUS.settlementFailureLabel(item.failureCode, item.failureMessage) });
            });
            if (batch.status === "RUNNING") {
                lines.push({ time: "--:--:--", level: "INFO",
                    text: "처리 중 · " + formatCount(batch.processedCount) + " / " + formatCount(batch.totalCount) });
            } else if (batch.status === "FAILED") {
                lines.push({ time: "--:--:--", level: "ERROR", text: "배치 실패" + (batch.failureMessage ? " · " + batch.failureMessage : "") });
            } else {
                lines.push({ time: "--:--:--", level: "INFO",
                    text: "배치 종료 · 성공 " + formatCount(batch.successCount) + " / 실패 " + formatCount(batch.failedCount) });
            }
        }
        return lines.concat(liveLines).slice(-MAX_LOG_LINES);
    }

    function renderLog() {
        var html = composeLog().map(function (line) {
            return '<div class="adm-live-logline"><span class="adm-live-logtime">' + escapeHtml(line.time) + "</span>" +
                '<span class="adm-live-loglevel is-' + line.level.toLowerCase() + '">' + line.level + "</span>" +
                '<span class="adm-live-logtext" title="' + escapeHtml(line.text) + '">' + escapeHtml(line.text) + "</span></div>";
        }).join("");
        var $log = $("#settlement-log").html(html);
        $log.scrollTop($log[0].scrollHeight);
    }

    function pushLive(level, text) {
        liveLines.push({ time: formatTime(new Date()), level: level, text: text });
        if (liveLines.length > MAX_LOG_LINES) {
            liveLines.shift();
        }
        renderLog();
    }

    // ---------- 실행 목록 ----------
    function stateRow(columns, className, innerHtml, fill) {
        return '<tr class="is-state ' + (fill ? "is-state-fill " : "") + className + '"><td colspan="' + columns + '">' +
            innerHtml + "</td></tr>";
    }

    function skeleton(columns, rows, className) {
        var html = "";
        for (var r = 0; r < rows; r++) {
            html += '<tr class="is-state ' + className + '" aria-label="불러오는 중"><td colspan="' + columns +
                '"><div class="adm-skel-row"><div class="adm-skel-cell"></div>';
            for (var c = 0; c < columns - 1; c++) {
                html += '<div class="adm-skel-cell"><span style="width:' + SKELETON_WIDTHS[(c + r) % SKELETON_WIDTHS.length] +
                    ';animation-delay:' + ((c + r) % 5) * 0.06 + 's"></span></div>';
            }
            html += "</div></td></tr>";
        }
        return html;
    }

    function renderRows() {
        var $body = $("#settlement-list").empty();
        var start = currentPage * PAGE_SIZE;
        var pageRows = filteredBatches.slice(start, start + PAGE_SIZE);

        if (!pageRows.length) {
            $body.append(stateRow(LIST_COLUMNS, "settlement-empty",
                '<div class="adm-state-empty">' +
                '<img src="/images/admin/char-front.png" alt="">' +
                '<div class="adm-state-empty-title">정산 배치 실행 내역이 없습니다.</div>' +
                '<div class="adm-state-empty-desc">조회조건을 바꾸거나 초기화한 뒤 다시 조회해 주세요.</div>' +
                '<button type="button" class="adm-btn settlement-empty-reset">조건 초기화</button>' +
                "</div>", true));
            return;
        }

        pageRows.forEach(function (batch, index) {
            var selectedClass = Number(batch.batchId) === Number(selectedBatchId) ? " is-selected" : "";
            $body.append(
                '<tr class="is-clickable' + selectedClass + '" tabindex="0" data-batch-id="' + batch.batchId + '">' +
                '<td class="adm-col-no">' + (start + index + 1) + "</td>" +
                '<td><span class="settlement-mono">' + escapeHtml(batchLabel(batch.batchId)) + "</span></td>" +
                '<td class="settlement-col-at"><span>' + formatDateTime(batch.executedAt) + "</span></td>" +
                '<td class="is-c">' + batchChip(batch) + "</td>" +
                '<td class="is-r"><span>' + formatCount(batch.totalCount) + "</span></td>" +
                '<td class="is-r"><span>' + formatCount(batch.successCount) + "</span></td>" +
                '<td class="is-r"><span>' + formatCount(batch.failedCount) + "</span></td>" +
                '<td class="is-r"><span>' + formatCount(batch.processedCount) + "</span></td>" +
                "</tr>"
            );
        });
    }

    function renderPager() {
        var $pager = $("#settlement-pagination").empty();
        var totalPages = Math.max(1, Math.ceil(filteredBatches.length / PAGE_SIZE));
        if (!filteredBatches.length || totalPages <= 1) {
            return;
        }
        var BLOCK_SIZE = 10;
        var blockStart = Math.floor(currentPage / BLOCK_SIZE) * BLOCK_SIZE;
        var blockEnd = Math.min(totalPages - 1, blockStart + BLOCK_SIZE - 1);

        function addButton(label, targetPage, isDisabled, isActive) {
            var classes = "adm-page-btn" + (isActive ? " is-active" : "") + (/^[0-9]+$/.test(label) ? "" : " is-nav");
            var $btn = $('<button type="button" class="' + classes + '">' + label + "</button>");
            $btn.prop("disabled", isDisabled || isActive);
            if (!isDisabled && !isActive) {
                $btn.on("click", function () {
                    currentPage = targetPage;
                    renderRows();
                    renderPager();
                });
            }
            $pager.append($btn);
        }

        if (blockStart > 0) {
            addButton("이전", blockStart - 1, false, false);
        }
        for (var i = blockStart; i <= blockEnd; i++) {
            addButton(String(i + 1), i, false, i === currentPage);
        }
        addButton("다음", blockEnd + 1, blockEnd === totalPages - 1, false);
    }

    // 배치 ID 검색어와 상태를 화면에서 거른다 (서버 목록 API 는 조건을 받지 않는다 → #63). 순서는 서버 순서 그대로.
    function applyFilters(keepPage) {
        var keyword = ($("#settlement-keyword").val() || "").trim().replace(/^#/, "").toLowerCase();
        var kind = $("#settlement-filter-status").val();

        filteredBatches = allBatches.filter(function (batch) {
            if (kind && STATUS.settlementBatchKind(batch) !== kind) {
                return false;
            }
            return !keyword || String(batch.batchId).indexOf(keyword) >= 0 ||
                String(batch.runId || "").toLowerCase().indexOf(keyword) >= 0;
        });

        if (sortSpec) {
            filteredBatches = MARIA.tableSort.sortList(filteredBatches, function (batch) {
                return sortSpec.key === "status" ? STATUS.settlementBatchKind(batch) : batch[sortSpec.key];
            }, sortSpec.dir);
        }

        if (!keepPage) {
            currentPage = 0;
        }
        $("#settlement-count").text(formatCount(filteredBatches.length));
        renderRows();
        renderPager();
    }

    function loadBatches() {
        $("#settlement-list").html(skeleton(LIST_COLUMNS, SKELETON_ROWS, "settlement-loading"));

        MARIA.auth.ajax({ url: "/api/admin/settlement/batches", method: "GET" })
            .done(function (res) {
                allBatches = res.data || [];
                summaryLoaded = true;
                renderSummary();
                applyFilters(true);

                // 처음이거나 고른 배치가 목록에서 사라졌으면 첫 배치를 고른다 (원본: 처음부터 첫 줄이 선택됨)
                if (selectedBatchId == null || !findBatch(selectedBatchId)) {
                    var first = filteredBatches[0] || allBatches[0] || null;
                    selectBatch(first ? first.batchId : null);
                } else {
                    selectBatch(selectedBatchId);
                }
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                allBatches = [];
                filteredBatches = [];
                summaryLoaded = false;
                clearSummary();
                $("#settlement-count").text("-");
                $("#settlement-pagination").empty();
                selectBatch(null);
                $("#settlement-list").html(stateRow(LIST_COLUMNS, "settlement-error",
                    '<div class="adm-state-error"><div class="adm-state-error-text">' +
                    '<div class="adm-state-error-title">' + escapeHtml(errorMessage(xhr, "배치 목록을 불러오지 못했습니다.")) + "</div>" +
                    '<div class="adm-state-error-desc">잠시 후 다시 시도해 주세요.</div></div>' +
                    '<button type="button" class="adm-btn settlement-retry">다시 시도</button></div>', true));
            });
    }

    function loadBusinessToday() {
        MARIA.auth.ajax({ url: "/api/admin/system-clock", method: "GET" })
            .done(function (res) {
                businessToday = parseDate(res.data);
                renderSummary();
            });
    }

    // ---------- 선택한 배치의 실패 항목 ----------
    var SCOPE_TITLE = { fail: "실패", all: "전체", pending: "처리대기" };

    function scopeRowHtml(item, index) {
        var selectedClass = Number(item.itemId) === Number(selectedItemId) ? " is-selected" : "";
        var reason = item.result === "FAILED" ? STATUS.settlementFailureLabel(item.failureCode, item.failureMessage) : "-";
        var resultChip = item.result
            ? chip(STATUS.settlementItemResultTone(item.result), STATUS.settlementItemResultLabel(item.result))
            : chip("warn", "처리대기");
        return '<tr class="is-clickable' + selectedClass + '" tabindex="0" data-item-id="' + item.itemId + '">' +
            '<td class="adm-col-no">' + (index + 1) + "</td>" +
            '<td><span class="settlement-mono">' + escapeHtml(batchLabel(item.itemId)) + "</span></td>" +
            "<td><span>" + (item.accountNo ? escapeHtml(MARIA.fmt.hyphenateAccountNo(item.accountNo)) : "-") + "</span></td>" +
            "<td><span>" + (item.productName || item.ticker ? escapeHtml(productLabel(item)) : "-") + "</span></td>" +
            '<td class="is-r"><span>' + (item.provisionalAmount == null ? "-" : formatAmount(item.provisionalAmount)) + "</span></td>" +
            '<td class="is-r"><span>' + (item.finalAmount == null ? "-" : formatAmount(item.finalAmount)) + "</span></td>" +
            '<td class="is-c">' + resultChip + "</td>" +
            '<td><span title="' + escapeHtml(item.failureMessage || reason) + '">' + escapeHtml(reason) + "</span></td>" +
            "<td><span>" + (item.processedAt ? formatDateTime(item.processedAt) : "-") + "</span></td></tr>";
    }

    function renderScopeItems() {
        var $body = $("#settlement-fail-list").empty();
        var batch = selectedBatchId == null ? null : findBatch(selectedBatchId);
        $("#settlement-fail-batch").text(batch ? batchLabel(batch.batchId) : "");
        $("#settlement-pending-more").prop("hidden", !(scopeHasMore && itemScope === "pending"));
        if (!batch) {
            $("#settlement-fail-count").text("-");
            $body.append(stateRow(FAIL_COLUMNS, "settlement-fail-none", "배치를 선택하세요."));
        } else if (scopeError) {
            $("#settlement-fail-count").text("-");
            $body.append(stateRow(FAIL_COLUMNS, "settlement-fail-error", escapeHtml(scopeError)));
        } else if (!scopeLoaded) {
            $("#settlement-fail-count").text("-");
            $body.html(skeleton(FAIL_COLUMNS, 3, "settlement-fail-loading"));
        } else if (!scopeItems.length) {
            $("#settlement-fail-count").text("0");
            $body.append(stateRow(FAIL_COLUMNS, "settlement-fail-empty", "이 배치에는 " + SCOPE_TITLE[itemScope] + " 항목이 없습니다."));
        } else {
            $("#settlement-fail-count").text(formatCount(scopeItems.length) + (scopeHasMore ? "+" : ""));
            scopeItems.forEach(function (item, index) { $body.append(scopeRowHtml(item, index)); });
        }
    }

    // all: GET /batches/detail/{batchId}, pending: GET /batches/{batchId}/items/pending?lastItemId= (이어 받기)
    function loadScopeItems(append) {
        var batchId = selectedBatchId;
        var seq = ++scopeSeq;
        if (!append) {
            scopeItems = [];
            scopeLoaded = false;
            scopeHasMore = false;
        }
        scopeError = null;
        renderScopeItems();
        if (batchId == null) {
            return;
        }
        var req = itemScope === "all"
            ? { url: "/api/admin/settlement/batches/detail/" + batchId, method: "GET" }
            : { url: "/api/admin/settlement/batches/" + batchId + "/items/pending", method: "GET",
                data: { lastItemId: append && scopeItems.length ? scopeItems[scopeItems.length - 1].itemId : 0 } };
        MARIA.auth.ajax(req).done(function (res) {
            if (seq !== scopeSeq) {
                return;
            }
            var rows = res.data || [];
            scopeItems = append ? scopeItems.concat(rows) : rows;
            // 서버가 한 번에 100건씩 준다 — 100건 미만이면 마지막 쪽
            scopeHasMore = itemScope === "pending" && rows.length >= 100;
            scopeLoaded = true;
            renderScopeItems();
        }).fail(function (xhr) {
            if (seq !== scopeSeq || xhr.status === 401) {
                return;
            }
            scopeError = errorMessage(xhr, "항목을 불러오지 못했습니다.");
            renderScopeItems();
        });
    }

    function setScope(scope) {
        itemScope = scope;
        $("#settlement-scope button").removeClass("is-on").filter(function () { return $(this).attr("data-scope") === scope; }).addClass("is-on");
        $("#settlement-scope-title").text(SCOPE_TITLE[scope]);
        $("#settlement-pending-more").prop("hidden", true);
        if (scope === "fail") {
            renderFailItems();
        } else {
            loadScopeItems(false);
        }
    }
    $("#settlement-scope").on("click", "button", function () {
        var scope = $(this).attr("data-scope");
        if (scope !== itemScope) {
            setScope(scope);
        }
    });
    $("#settlement-pending-more").on("click", function () { loadScopeItems(true); });

    function renderFailItems() {
        if (itemScope !== "fail") {
            renderScopeItems();
            return;
        }
        var $body = $("#settlement-fail-list").empty();
        var batch = selectedBatchId == null ? null : findBatch(selectedBatchId);
        $("#settlement-fail-batch").text(batch ? batchLabel(batch.batchId) : "");

        if (!batch) {
            $("#settlement-fail-count").text("-");
            $body.append(stateRow(FAIL_COLUMNS, "settlement-fail-none", "배치를 선택하세요."));
            return;
        }
        if (failError) {
            $("#settlement-fail-count").text("-");
            $body.append(stateRow(FAIL_COLUMNS, "settlement-fail-error",
                '<div class="adm-state-error"><div class="adm-state-error-text">' +
                '<div class="adm-state-error-title">' + escapeHtml(failError) + "</div>" +
                '<div class="adm-state-error-desc">잠시 후 다시 시도해 주세요.</div></div>' +
                '<button type="button" class="adm-btn settlement-fail-retry">다시 시도</button></div>', true));
            return;
        }
        if (!failLoaded) {
            $("#settlement-fail-count").text("-");
            $body.html(skeleton(FAIL_COLUMNS, 3, "settlement-fail-loading"));
            return;
        }
        $("#settlement-fail-count").text(formatCount(failItems.length));
        if (!failItems.length) {
            $body.append(stateRow(FAIL_COLUMNS, "settlement-fail-empty",
                '<div class="adm-state-empty"><img src="/images/admin/char-front.png" alt="">' +
                '<div class="adm-state-empty-title">이 배치에는 실패 항목이 없습니다</div></div>', true));
            return;
        }
        failItems.forEach(function (item, index) {
            var selectedClass = Number(item.itemId) === Number(selectedItemId) ? " is-selected" : "";
            var reason = STATUS.settlementFailureLabel(item.failureCode, item.failureMessage);
            $body.append(
                '<tr class="is-clickable' + selectedClass + '" tabindex="0" data-item-id="' + item.itemId + '">' +
                '<td class="adm-col-no">' + (index + 1) + "</td>" +
                '<td><span class="settlement-mono">' + escapeHtml(batchLabel(item.itemId)) + "</span></td>" +
                "<td><span>" + escapeHtml(MARIA.fmt.hyphenateAccountNo(item.accountNo)) + "</span></td>" +
                "<td><span>" + escapeHtml(productLabel(item)) + "</span></td>" +
                '<td class="is-r"><span>' + formatAmount(item.provisionalAmount) + "</span></td>" +
                '<td class="is-r"><span>' + formatAmount(item.finalAmount) + "</span></td>" +
                '<td class="is-c">' + chip(STATUS.settlementItemResultTone(item.result), STATUS.settlementItemResultLabel(item.result)) + "</td>" +
                '<td><span title="' + escapeHtml(item.failureMessage || reason) + '">' + escapeHtml(reason) + "</span></td>" +
                "<td><span>" + formatDateTime(item.processedAt) + "</span></td>" +
                "</tr>"
            );
        });
    }

    function loadFailItems() {
        var batchId = selectedBatchId;
        var seq = ++failRequestSeq;
        failItems = [];
        failLoaded = false;
        failError = null;
        renderFailItems();
        renderLog();
        if (batchId == null) {
            return;
        }
        MARIA.auth.ajax({ url: "/api/admin/settlement/batches/detail/fail/" + batchId, method: "GET" })
            .done(function (res) {
                if (seq !== failRequestSeq) {
                    return;
                }
                failItems = res.data || [];
                failLoaded = true;
                renderFailItems();
                renderLog();
            })
            .fail(function (xhr) {
                if (seq !== failRequestSeq || xhr.status === 401) {
                    return;
                }
                failError = errorMessage(xhr, "실패 항목을 불러오지 못했습니다.");
                renderFailItems();
                renderLog();
            });
    }

    function selectBatch(batchId) {
        selectedBatchId = batchId == null ? null : Number(batchId);
        closeDrawer();
        $("#settlement-list tr[data-batch-id]").removeClass("is-selected")
            .filter(function () { return Number($(this).data("batch-id")) === selectedBatchId; }).addClass("is-selected");
        renderRunPanel();
        loadFailItems();
        if (itemScope !== "fail") {
            loadScopeItems(false);
        }
        updateActionButtons();
    }

    // ---------- 정산 항목 상세 패널 ----------
    function renderDrawer(item) {
        currentItem = item;
        var reason = STATUS.settlementFailureLabel(item.failureCode, item.failureMessage);
        var difference = item.finalAmount == null || item.provisionalAmount == null ? null :
            Number(item.finalAmount) - Number(item.provisionalAmount);

        $("#settlement-detail-title").text("정산 항목 " + batchLabel(item.itemId));
        $("#settlement-detail-sub").text(item.accountNo ? MARIA.fmt.hyphenateAccountNo(item.accountNo) : "-");

        $("#settlement-detail-result").html(chip(STATUS.settlementItemResultTone(item.result), STATUS.settlementItemResultLabel(item.result)));
        $("#settlement-detail-reason").text(item.failureCode || item.failureMessage ? reason : "-");
        $("#settlement-detail-product").text(productLabel(item));
        $("#settlement-detail-exchange-status").text(STATUS.settlementStatusLabel(item.settlementStatus));
        $("#settlement-detail-processed-at").text(formatDateTime(item.processedAt));
        $("#settlement-detail-notice").prop("hidden", !item.failureMessage).text(item.failureMessage || "");

        $("#settlement-detail-provisional-amount").text(item.provisionalAmount == null ? "-" : formatAmount(item.provisionalAmount) + "원");
        $("#settlement-detail-provisional-at").text(formatDateTime(item.provisionalAt));
        $("#settlement-detail-provisional-rate").text(formatRate(item.settlementFxRate));
        $("#settlement-detail-final-amount").text(item.finalAmount == null ? "-" : formatAmount(item.finalAmount) + "원");
        $("#settlement-detail-final-at").text(formatDate(item.finalAt));
        $("#settlement-detail-final-rate").text(formatRate(item.finalRate));
        $("#settlement-detail-difference").text(difference == null ? "-" :
            (difference > 0 ? "+" : "") + formatAmount(difference) + "원");

        $("#settlement-detail-item-id").text(batchLabel(item.itemId));
        $("#settlement-detail-batch").text(batchLabel(item.batchId));
        $("#settlement-detail-exchange-id").text(item.exchangeId == null ? "-" : item.exchangeId);
        $("#settlement-detail-account-id").text(item.accountId == null ? "-" : item.accountId);
        $("#settlement-detail-order-id").text(item.orderId == null ? "-" : item.orderId);
        $("#settlement-detail-failure-code").text(item.failureCode || "-");

        setFold(false);
        updateActionButtons();
        $("#settlement-drawer-backdrop, #settlement-detail-drawer").addClass("is-open");
        $("#settlement-detail-drawer").attr("aria-hidden", "false");
        $("#settlement-detail-drawer .adm-panel-body").scrollTop(0);
        $("#settlement-drawer-close").trigger("focus");
    }

    function closeDrawer() {
        if (!$("#settlement-detail-drawer").hasClass("is-open")) {
            return;
        }
        $("#settlement-drawer-backdrop, #settlement-detail-drawer").removeClass("is-open");
        $("#settlement-detail-drawer").attr("aria-hidden", "true");
        var $row = $("#settlement-fail-list tr.is-selected").removeClass("is-selected");
        if ($row.length) {
            $row.trigger("focus");
        }
        selectedItemId = null;
        currentItem = null;
    }

    function openItem(itemId) {
        selectedItemId = Number(itemId);
        $("#settlement-fail-list tr[data-item-id]").removeClass("is-selected")
            .filter(function () { return Number($(this).data("item-id")) === selectedItemId; }).addClass("is-selected");
        MARIA.auth.ajax({ url: "/api/admin/settlement/batches/" + selectedBatchId + "/items/" + selectedItemId, method: "GET" })
            .done(function (res) {
                if (Number(selectedItemId) !== Number(itemId)) {
                    return;
                }
                renderDrawer(res.data || {});
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                MARIA.ui.showError(errorMessage(xhr, "정산 항목 상세를 불러오지 못했습니다."));
            });
    }

    function setFold(open) {
        $("#settlement-fold-toggle").attr("aria-expanded", open ? "true" : "false");
        $("#settlement-fold-body").prop("hidden", !open);
    }

    $("#settlement-fold-toggle").on("click", function () {
        setFold($(this).attr("aria-expanded") !== "true");
    });
    $("#settlement-drawer-close, #settlement-drawer-backdrop").on("click", closeDrawer);

    $("#settlement-list").on("click", "tr[data-batch-id]", function () {
        selectBatch($(this).data("batch-id"));
    });
    $("#settlement-list").on("keydown", "tr[data-batch-id]", function (event) {
        if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            selectBatch($(this).data("batch-id"));
        }
    });
    $("#settlement-fail-list").on("click", "tr[data-item-id]", function () {
        openItem($(this).data("item-id"));
    });
    $("#settlement-fail-list").on("keydown", "tr[data-item-id]", function (event) {
        if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            openItem($(this).data("item-id"));
        }
    });
    $("#settlement-fail-list").on("click", ".settlement-fail-retry", loadFailItems);
    $("#settlement-list").on("click", ".settlement-retry", loadBatches);

    // ---------- 조회 조건 ----------
    function resetFilters() {
        $("#settlement-keyword").val("");
        $("#settlement-filter-status").val("");
        applyFilters();
    }

    $("#settlement-search-button").on("click", function () { applyFilters(); });
    $("#settlement-keyword").on("keydown", function (event) {
        if (event.key === "Enter") {
            applyFilters();
        }
    });
    $("#settlement-filter-status").on("change", function () { applyFilters(); });
    $("#settlement-filter-reset").on("click", resetFilters);
    $("#settlement-list").on("click", ".settlement-empty-reset", resetFilters);

    $("#settlement-page-size").on("click", "button", function () {
        var size = parseInt($(this).attr("data-size"), 10);
        if (!size || size === PAGE_SIZE) {
            return;
        }
        PAGE_SIZE = size;
        $("#settlement-page-size button").removeClass("is-on");
        $(this).addClass("is-on");
        currentPage = 0;
        renderRows();
        renderPager();
    });

    // ---------- 처리: 정산 배치 실행 · 실패 배치 재처리 · 실패 항목 재처리 (모두 확인 창을 거친다) ----------
    // 요청은 수정 전 화면과 같다: POST /settlement/jobs, POST /settlement/batches/{id}/retry, POST /settlement/batches/{id}/items/{itemId}/retry (본문 없음).
    // 역할: 세 API 모두 @PreAuthorize("hasAnyRole('ADMIN','SETTLEMENT')"). 서버는 실패(FAILED) 상태 배치의 실패 항목만 재처리한다.
    function selectedBatch() {
        return selectedBatchId == null ? null : findBatch(selectedBatchId);
    }

    function updateActionButtons() {
        var batch = selectedBatch();
        var batchRetryable = !!batch && batch.status === "FAILED";
        var itemRetryable = batchRetryable && !!currentItem && currentItem.result === "FAILED";
        $("#settlement-execute").prop("hidden", !canAct).prop("disabled", acting);
        $("#settlement-retry-batch").prop("hidden", !canAct).prop("disabled", acting || !batchRetryable)
            .attr("title", batchRetryable || !canAct ? "" : "실패 상태 배치만 재처리할 수 있어요");
        $("#settlement-detail-foot").prop("hidden", !canAct);
        $("#settlement-retry-item").prop("disabled", acting || !itemRetryable)
            .attr("title", itemRetryable || !canAct ? "" : "실패 상태 배치의 실패 항목만 재처리할 수 있어요");
    }

    function openConfirm(action, returnFocus) {
        if (!canAct || acting) {
            return;
        }
        pendingAction = action;
        confirmReturnFocus = returnFocus || null;
        $("#settlement-confirm-title").text(action.question);
        $("#settlement-confirm-desc").text(action.body);
        $("#settlement-confirm-ok").text(action.confirm).prop("disabled", false);
        $("#settlement-confirm").prop("hidden", false);
        $("#settlement-confirm-ok").trigger("focus");
    }

    function closeConfirm() {
        $("#settlement-confirm").prop("hidden", true);
        pendingAction = null;
        if (confirmReturnFocus) {
            $(confirmReturnFocus).trigger("focus");
        }
    }

    $("#settlement-confirm-close, #settlement-confirm-cancel").on("click", closeConfirm);
    $("#settlement-confirm").on("click", function (event) {
        if (event.target === this) {
            closeConfirm();
        }
    });
    // 확인 창이 열려 있을 땐 Esc 가 창만 닫는다 (뒤의 패널까지 닫히지 않게 capture 단계에서 막는다)
    document.addEventListener("keydown", function (event) {
        if (event.key === "Escape" && !$("#settlement-confirm").prop("hidden")) {
            event.stopImmediatePropagation();
            closeConfirm();
        }
    }, true);

    function runAction(action) {
        acting = true;
        updateActionButtons();
        MARIA.auth.ajax({ url: action.url, method: "POST" })
            .done(function (res) {
                MARIA.ui.showSuccess(action.toast);
                MARIA.ui.setStatus(action.toast);
                action.after(res);
            })
            .fail(function (xhr) {
                if (xhr.status === 401) {
                    return;
                }
                MARIA.ui.showError(errorMessage(xhr, action.failMessage));
            })
            .always(function () {
                acting = false;
                updateActionButtons();
            });
    }

    $("#settlement-confirm-ok").on("click", function () {
        var action = pendingAction;
        if (!action || acting || !canAct) {
            return;
        }
        $("#settlement-confirm").prop("hidden", true);
        pendingAction = null;
        runAction(action);
    });

    $("#settlement-execute").on("click", function () {
        openConfirm({
            question: "정산 배치를 실행할까요?",
            body: "가환전 상태의 환전 건을 확정 환율로 정산해요. 끝나면 고객 화면에도 알림이 가요.",
            confirm: "정산 배치 실행",
            url: "/api/admin/settlement/jobs",
            toast: "정산 배치를 시작했어요",
            failMessage: "정산 배치 실행에 실패했습니다.",
            after: function (res) {
                if (res.data && res.data.batchId != null) {
                    selectedBatchId = Number(res.data.batchId);
                }
                closeDrawer();
                loadBatches();
            }
        }, "#settlement-execute");
    });

    $("#settlement-retry-batch").on("click", function () {
        var batch = selectedBatch();
        if (!batch || batch.status !== "FAILED") {
            return;
        }
        openConfirm({
            question: "실패 배치를 재처리할까요?",
            body: batchLabel(batch.batchId) + " 배치의 실패 " + formatCount(batch.failedCount) + "건을 다시 정산해요.",
            confirm: "재처리",
            url: "/api/admin/settlement/batches/" + batch.batchId + "/retry",
            toast: "실패 배치를 재처리 대기열에 올렸어요",
            failMessage: "정산 배치 재처리에 실패했습니다.",
            after: function () {
                closeDrawer();
                loadBatches();
            }
        }, "#settlement-retry-batch");
    });

    $("#settlement-retry-item").on("click", function () {
        var batch = selectedBatch();
        var item = currentItem;
        if (!batch || !item || batch.status !== "FAILED" || item.result !== "FAILED") {
            return;
        }
        openConfirm({
            question: "실패 항목을 재처리할까요?",
            body: batchLabel(item.itemId) + " 항목(" + MARIA.fmt.hyphenateAccountNo(item.accountNo) + " · " + productLabel(item) + ")을 다시 정산해요.",
            confirm: "재처리",
            url: "/api/admin/settlement/batches/" + batch.batchId + "/items/" + item.itemId + "/retry",
            toast: "실패 항목을 재처리했어요",
            failMessage: "정산 항목 재처리에 실패했습니다.",
            after: function () {
                closeDrawer();
                loadBatches();
            }
        }, "#settlement-retry-item");
    });

    // ---------- 실시간 알림 (SSE) ----------
    // GET /api/admin/settlement/notifications — 연결되면 "connected", 배치가 끝나면 "settlement-batch-completed"({batchId, completedAt}).
    // 인증은 쿠키라 EventSource 로 바로 붙는다. 화면을 떠나면(pagehide) 연결을 닫는다.
    var notificationSource = null;
    var notificationLost = false;

    function closeNotifications() {
        if (notificationSource) {
            notificationSource.close();
            notificationSource = null;
        }
    }

    function connectNotifications() {
        if (!window.EventSource) {
            return;
        }
        notificationSource = new EventSource("/api/admin/settlement/notifications");
        notificationSource.addEventListener("connected", function () {
            notificationLost = false;
            pushLive("INFO", "정산 알림에 연결했어요");
        });
        notificationSource.addEventListener("settlement-batch-completed", function (event) {
            var data = {};
            try {
                data = JSON.parse(event.data);
            } catch (e) {
                data = {};
            }
            pushLive("INFO", "배치 " + batchLabel(data.batchId) + " 정산이 끝났어요" +
                (data.completedAt ? " · " + formatDateTime(data.completedAt) : ""));
            loadBatches();
        });
        notificationSource.onerror = function () {
            if (!notificationLost) {
                notificationLost = true;
                pushLive("WARN", "정산 알림 연결이 끊겼어요. 다시 연결하는 중이에요");
            }
        };
    }

    $(window).on("pagehide beforeunload", closeNotifications);

    // ---------- 좁은 화면 ----------
    // 화면 너비 ÷ 글자 크기 배율 < 900 이면 세로로 쌓고(is-narrow), 높이 ÷ 배율 < 800 이면 실행 현황을 낮춘다(is-short).
    // 좁으면 본문이 스크롤된다 (원본 기준). 배율은 layout.js 가 #admShell 의 --font-scale 로 정한다.
    function applyNarrowLayout() {
        var shell = document.getElementById("admShell");
        var zoom = shell ? parseFloat(getComputedStyle(shell).getPropertyValue("--font-scale")) || 1 : 1;
        $(".settlement-management").toggleClass("is-narrow", window.innerWidth / zoom < 900)
            .toggleClass("is-short", window.innerHeight / zoom < 800); // 낮은 화면: 실행 현황 높이 120px, 시작·성공·실패 칸 숨김 (원본 기준)
    }
    $(window).on("resize", applyNarrowLayout);
    if (window.MutationObserver && document.getElementById("admShell")) {
        new MutationObserver(applyNarrowLayout).observe(document.getElementById("admShell"), { attributes: true, attributeFilter: ["style"] });
    }
    applyNarrowLayout();

    var deepLinkAccountNo = MARIA.deeplink.accountNoFromUrl();
    if (deepLinkAccountNo) {
        $("#settlement-keyword").val("");
    }

    renderRunPanel();
    renderFailItems();
    $(document).on("maria:system-clock-changed", loadBusinessToday);

    // 역할을 확인한 뒤에 버튼을 보이고 목록을 부른다 (옛 화면은 확인 전에 역할을 검사해 버튼이 안 보일 수 있었음)
    MARIA.auth.requireAuth().done(function (admin) {
        canAct = !!admin && (admin.role === "ADMIN" || admin.role === "SETTLEMENT");
        updateActionButtons();
        loadBusinessToday();
        loadBatches();
        connectNotifications();
    });

    MARIA.tableSort.bind(".settlement-batch-table", function (key, dir) {
        sortSpec = { key: key, dir: dir };
        applyFilters(false);
    });
});
