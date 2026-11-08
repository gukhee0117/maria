// 표 마무리 손질 (원본 AdminTable 규칙 + 현업 표기)
// 1) 날짜·시각 칸은 머리글 이름으로 알아보고 가운데 정렬 (원본의 DATEC 목록과 같은 방식)
// 2) 금액 칸 머리글 옆에 단위 "(원)" 을 붙인다 (값마다 붙이지 않는다)
// 3) 글자만 있는 칸은 길면 말줄임(…)으로 줄이고, 잘린 칸은 마우스를 올리면 전체를 보여 준다(title)
// 표에 data-polish="off" 를 주면 이 손질을 하지 않는다.
(function () {
    "use strict";

    var DATE_RE = /(일시|시각|일|시작|종료)$/;
    var MONEY_RE = /(금액|액|한도|잔고|예탁금|투자금|세액|기준가|기준 가격|체결가|평균매수단가)$/;
    var MONEY_SKIP_RE = /(수량|비율|비중)/;
    // 고정폭 글꼴로 보여 줄 번호·아이디 칸 (원본 AdminTable 의 MONO 목록)
    var MONO = ["계좌번호", "배치 ID", "Item ID", "실행 ID", "아이디", "접수번호", "경유 일반계좌", "출처 계좌"];

    function label(th) {
        var unit = th.querySelector(".adm-unit");
        return (unit ? th.textContent.replace(unit.textContent, "") : th.textContent).replace(/\s+/g, " ").trim();
    }

    function apply(table) {
        var headRow = table.querySelector("thead tr");
        if (!headRow) {
            return;
        }
        var heads = headRow.children;
        var dateCols = [], monoCols = [];
        for (var i = 0; i < heads.length; i++) {
            var th = heads[i], name = label(th);
            if (MONO.indexOf(name) !== -1) {
                monoCols.push(i);
            }
            if (th.classList.contains("adm-col-no") || th.classList.contains("is-r")) {
                if (MONEY_RE.test(name) && !MONEY_SKIP_RE.test(name) && !th.hasAttribute("data-unit")) {
                    th.setAttribute("data-unit", "(원)");
                    var unit = document.createElement("span");
                    unit.className = "adm-unit";
                    unit.textContent = "(원)";
                    th.appendChild(unit);
                }
                continue;
            }
            if (DATE_RE.test(name)) {
                th.classList.add("is-c");
                dateCols.push(i);
            }
        }
        var rows = table.querySelectorAll("tbody tr:not(.is-state)");
        for (var r = 0; r < rows.length; r++) {
            var cells = rows[r].children;
            for (var d = 0; d < dateCols.length; d++) {
                if (cells[dateCols[d]]) {
                    cells[dateCols[d]].classList.add("is-c");
                }
            }
            for (var m = 0; m < monoCols.length; m++) {
                if (cells[monoCols[m]]) {
                    cells[monoCols[m]].classList.add("adm-mono");
                }
            }
            for (var c = 0; c < cells.length; c++) {
                var td = cells[c];
                if (td.classList.contains("adm-col-no") || td.childElementCount > 0 || !td.textContent.trim()) {
                    continue;
                }
                var span = document.createElement("span");
                span.textContent = td.textContent;
                td.textContent = "";
                td.appendChild(span);
            }
        }
        // 잘린 칸에만 전체 값을 title 로 (보이는 그대로면 붙이지 않는다)
        var spans = table.querySelectorAll("tbody td > span");
        for (var s = 0; s < spans.length; s++) {
            var el = spans[s];
            if (el.children.length === 0 && el.scrollWidth > el.clientWidth + 1) {
                if (!el.hasAttribute("title")) {
                    el.setAttribute("title", el.textContent);
                }
            } else if (el.getAttribute("title") === el.textContent && el.scrollWidth <= el.clientWidth) {
                el.removeAttribute("title");
            }
        }
    }

    function watch(table) {
        if (table.getAttribute("data-polish") === "off") {
            return;
        }
        var queued = false;
        function schedule() {
            if (queued) {
                return;
            }
            queued = true;
            requestAnimationFrame(function () {
                queued = false;
                apply(table);
            });
        }
        new MutationObserver(schedule).observe(table, { childList: true, subtree: true });
        if (window.ResizeObserver) {
            new ResizeObserver(schedule).observe(table);
        }
        schedule();
    }

    document.addEventListener("DOMContentLoaded", function () {
        var tables = document.querySelectorAll("table.adm-gtable");
        for (var i = 0; i < tables.length; i++) {
            watch(tables[i]);
        }
    });
})();
