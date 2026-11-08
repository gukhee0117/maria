// 표 머리글 정렬 (원본 admin.dc.html 의 sortCols 와 같은 모양: ▲▼ 한 쌍, 누른 쪽만 파랑)
//
// 두 가지로 동작한다.
// 1) API 연동 표 — 머리글(th)에 data-sort-key 를 붙이면 그 칸이 정렬 칸이 된다. 이 파일은 "표시와 눌림"만 맡고,
//    실제 정렬은 화면 스크립트가 한다.
//      - 서버가 쪽을 나눠 주는 목록: onChange(key, dir) 에서 sort · direction 을 API 에 실어 다시 조회 (전체 데이터 기준)
//      - 한 번에 다 받아 오는 목록: onChange 에서 배열을 MARIA.tableSort.sortList 로 정렬해 다시 그림
//    사용: var sorter = MARIA.tableSort.bind(".some-table", function (key, dir) { ... });
// 2) 그 밖의 표 — data-sort-key 가 하나도 없는 표는 이 파일이 지금 표에 보이는 행 전체를 칸 값으로 정렬한다.
//    (탭·패널의 작은 표처럼 보이는 행이 곧 전체인 표용. 목록이 다시 그려지면 정렬 표시가 풀린다.)
// data-sort="off" 를 표에 주면 정렬하지 않는다. 머리글에 버튼·입력칸이 있는 표(계좌 목록)는 건드리지 않는다.
(function () {
    "use strict";

    window.MARIA = window.MARIA || {};
    var collator = new Intl.Collator("ko", { numeric: true });

    function isEmpty(v) {
        return v === null || v === undefined || v === "" || v === "-";
    }

    function toNumber(v) {
        if (typeof v === "number") {
            return v;
        }
        var t = String(v).replace(/,/g, "");
        return /^-?\d+(\.\d+)?$/.test(t) ? parseFloat(t) : null;
    }

    // 숫자처럼 보이면 숫자로, 아니면 한글 순서로 비교한다
    function compare(a, b) {
        var na = toNumber(a), nb = toNumber(b);
        if (na !== null && nb !== null) {
            return na - nb;
        }
        return collator.compare(String(a), String(b));
    }

    // list 를 getter(row) 값 기준으로 정렬한 새 배열. 빈 값은 방향과 상관없이 늘 뒤로, 같은 값은 원래 순서 유지
    function sortList(list, getter, dir) {
        var sign = dir === "desc" ? -1 : 1;
        return list.map(function (row, i) { return { row: row, i: i, v: getter(row) }; })
            .sort(function (x, y) {
                var ex = isEmpty(x.v), ey = isEmpty(y.v);
                if (ex || ey) {
                    return ex && ey ? x.i - y.i : (ex ? 1 : -1);
                }
                return compare(x.v, y.v) * sign || x.i - y.i;
            })
            .map(function (k) { return k.row; });
    }

    // 눌린 머리글(th) 하나에만 방향 표시를 한다 (같은 키를 쓰는 칸이 둘이어도 누른 칸만 파랗게)
    function mark(table, activeTh, dir) {
        var ths = table.querySelectorAll("thead th");
        for (var i = 0; i < ths.length; i++) {
            if (ths[i] === activeTh) {
                ths[i].setAttribute("data-sort-dir", dir);
                ths[i].setAttribute("aria-sort", dir === "asc" ? "ascending" : "descending");
            } else {
                ths[i].removeAttribute("data-sort-dir");
                ths[i].removeAttribute("aria-sort");
            }
        }
    }

    function resolve(target) {
        return typeof target === "string" ? document.querySelector(target) : target;
    }

    // ---------- 1) API 연동 표 ----------
    function bind(target, onChange) {
        var table = resolve(target);
        var state = null;
        if (!table) {
            return { reset: function () {}, current: function () { return null; } };
        }
        var ths = table.querySelectorAll("thead th[data-sort-key]");
        for (var i = 0; i < ths.length; i++) {
            ths[i].classList.add("adm-sortable");
            ths[i].setAttribute("title", "눌러서 정렬");
            ths[i].addEventListener("click", function () {
                var key = this.getAttribute("data-sort-key");
                var dir = state && state.th === this && state.dir === "asc" ? "desc" : "asc";
                state = { key: key, dir: dir, th: this };
                mark(table, this, dir);
                onChange(key, dir);
            });
        }
        return {
            reset: function () {
                state = null;
                mark(table, null, null);
            },
            current: function () {
                return state ? { key: state.key, dir: state.dir } : null;
            }
        };
    }

    // ---------- 2) 보이는 행 전체를 정렬하는 표 ----------
    function bindDom(table) {
        var ths = table.querySelectorAll("thead th");
        var tbody = table.querySelector("tbody");
        if (!tbody || ths.length < 2) {
            return;
        }
        var state = null;
        var observer = new MutationObserver(function () {
            state = null;
            mark(table, null, null); // 목록이 새로 그려지면(조회·갱신) 정렬 표시를 푼다
            tbody.removeAttribute("data-no-base");
        });
        observer.observe(tbody, { childList: true });

        function sortRows(index, dir) {
            var rows = Array.prototype.filter.call(tbody.children, function (tr) { return !tr.classList.contains("is-state"); });
            var firstNo = tbody.querySelector("tr:not(.is-state) > .adm-col-no");
            var base = 1;
            if (tbody.getAttribute("data-no-base")) {
                base = parseInt(tbody.getAttribute("data-no-base"), 10);
            } else if (firstNo && !isNaN(parseInt(firstNo.textContent, 10))) {
                base = parseInt(firstNo.textContent, 10);
                tbody.setAttribute("data-no-base", String(base));
            }
            sortList(rows, function (tr) {
                var td = tr.children[index];
                return td ? td.textContent.replace(/\s+/g, " ").trim() : "";
            }, dir).forEach(function (tr) { tbody.appendChild(tr); });
            // No 칸은 행에 붙은 번호가 아니라 화면 순서 번호 — 정렬해도 1, 2, 3… (원본: offset + i + 1)
            var noCells = tbody.querySelectorAll("tr:not(.is-state) > .adm-col-no");
            for (var n = 0; n < noCells.length; n++) {
                noCells[n].textContent = String(base + n);
            }
        }

        for (var i = 0; i < ths.length; i++) {
            if (ths[i].classList.contains("adm-col-no")) {
                continue;
            }
            ths[i].classList.add("adm-sortable");
            ths[i].setAttribute("title", "눌러서 정렬");
            ths[i].addEventListener("click", function () {
                var index = Array.prototype.indexOf.call(this.parentNode.children, this);
                var dir = state && state.th === this && state.dir === "asc" ? "desc" : "asc";
                sortRows(index, dir);
                observer.takeRecords(); // 정렬하며 생긴 변경은 새로 그린 것으로 치지 않는다
                state = { th: this, dir: dir };
                mark(table, this, dir);
            });
        }
    }

    window.MARIA.tableSort = { bind: bind, sortList: sortList, compare: compare };

    document.addEventListener("DOMContentLoaded", function () {
        var tables = document.querySelectorAll("table.adm-gtable");
        for (var i = 0; i < tables.length; i++) {
            var t = tables[i];
            var hasKeys = t.querySelector("thead th[data-sort-key]");
            if (hasKeys || t.getAttribute("data-sort") === "off" || t.querySelector("thead button, thead input")) {
                continue;
            }
            bindDom(t);
        }
    });
})();
