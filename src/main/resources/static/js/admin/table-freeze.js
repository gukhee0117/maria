// 좌우 스크롤 시 표의 왼쪽 칸 고정 (원본 admin.dc.html 의 frz: No · 첫 칸들은 고정, 나머지만 스크롤)
// 고정할 칸 수: 머리글에서 계좌번호 · 고객 · 고객명이 앞쪽 4칸 안에 있으면 그 칸까지, 없으면 No + 첫 칸(2칸).
// 열이 6칸보다 적은 작은 표는 고정하지 않는다. 표에 data-freeze="N" 을 주면 그 값을 쓴다 (0 이면 고정 안 함).
(function () {
    "use strict";

    var KEYS = ["계좌번호", "고객", "고객명"];

    function frozenCount(table, heads) {
        var preset = table.getAttribute("data-freeze");
        if (preset !== null && preset !== "") {
            return Math.max(0, Math.min(parseInt(preset, 10) || 0, heads.length - 1));
        }
        if (heads.length < 6) {
            return 0;
        }
        var last = 1;
        for (var i = 0; i < Math.min(4, heads.length); i++) {
            if (KEYS.indexOf(heads[i].textContent.replace(/\s+/g, "")) !== -1) {
                last = i;
            }
        }
        return last + 1;
    }

    function apply(table) {
        var headRow = table.querySelector("thead tr");
        if (!headRow) {
            return;
        }
        var heads = headRow.children;
        var n = frozenCount(table, heads);
        var rows = table.querySelectorAll("tr");
        var lefts = [], left = 0;
        for (var i = 0; i < n; i++) {
            lefts.push(left);
            left += heads[i].getBoundingClientRect().width;
        }
        for (var r = 0; r < rows.length; r++) {
            var row = rows[r];
            var isState = row.classList.contains("is-state");
            for (var c = 0; c < row.children.length; c++) {
                var cell = row.children[c];
                var on = !isState && c < n;
                cell.classList.toggle("adm-frz", on);
                cell.classList.toggle("adm-frz-last", on && c === n - 1);
                cell.style.left = on ? lefts[c] + "px" : "";
            }
        }
    }

    function watch(table) {
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
        } else {
            window.addEventListener("resize", schedule);
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
