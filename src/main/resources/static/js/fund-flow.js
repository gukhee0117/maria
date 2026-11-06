/* 자금 환류 대시보드 (1405) — A-14a · A-14b
   - 7개 API(overview · funnel · monthly · leakage · segments · relief · allocation)를 동시에 부르고, 패널마다 따로 로딩 · 오류(다시 시도) · 빈 상태를 둔다.
   - 모든 숫자는 API 응답에서만 온다. 화면에서 하는 계산은 응답 값끼리의 합계 · 비율 · 차이뿐이고, 계산식은 패널 아래 근거 문구에 적는다.
   - ① 요약 6칸 ② Sankey(SVG) ③ 월별 콤보(Chart.js) ④ 예수금 브리지(SVG) ⑤ 퍼널(CSS) ⑥ 혜택 누수(CSS) ⑦ 감면 워터폴(SVG) ⑧ 국내 운용처(CSS)
     ⑨ 연령대 × 투자성향 히트맵(CSS grid) ⑩ 지표 정의 · 데이터 품질(표) + PDF 로 저장(새 창 A4 세로 인쇄). */
$(function () {
    var KRW = new Intl.NumberFormat("ko-KR");
    var state = {};          // API 이름 → 응답 data
    var chart = null;        // ③ Chart.js 인스턴스
    var segMetric = "reinvestRatio";
    var BOUNDARIES = ["05", "07"]; // 공제율 구간 경계: 5/31 · 7/31
    var LEAK_ORDER = ["NO_SELL", "NO_IMPACT", "PARTIAL_REDUCED", "BENEFIT_CANCELED", "EXTERNAL_BUY_OFFSET"];
    var LEAK_COLOR = {
        NO_SELL: "--adm-chart-gray",
        NO_IMPACT: "--adm-chart-3",
        PARTIAL_REDUCED: "--adm-chart-gold",
        BENEFIT_CANCELED: "--adm-warn-fg",
        EXTERNAL_BUY_OFFSET: "--adm-fail-fg"
    };
    var BUCKET_COLOR_SECTOR = ["--adm-chart-bar", "--adm-chart-2", "--adm-chart-3", "--adm-chart-4", "--adm-chart-5"]; // 업종 상위 5 (큰 순서대로 진하게 → 연하게)
    var LIGHT_BG = { "--adm-chart-gray": 1, "--adm-chart-gold": 1, "--adm-chart-5": 1, "--adm-chart-4": 1 }; // 밝은 바탕은 글자색을 어둡게

    var API = {
        overview: { url: "/api/admin/fund-flow/overview" },
        funnel: { url: "/api/admin/fund-flow/funnel" },
        monthly: { url: "/api/admin/fund-flow/monthly" },
        leakage: { url: "/api/admin/fund-flow/leakage" },
        segments: { url: "/api/admin/fund-flow/segments" },
        relief: { url: "/api/admin/fund-flow/relief" },
        allocation: { url: "/api/admin/fund-flow/allocation" }
    };

    /* ---------- 표기 ---------- */
    function esc(value) {
        return $("<div>").text(value == null ? "" : String(value)).html();
    }

    function num(value) {
        var n = Number(value);
        return isFinite(n) ? n : 0;
    }

    // 큰 금액: "186억 4,230만" (조 단위는 쓰지 않고 억을 콤마로 이어 쓴다)
    function krwShort(value) {
        if (value == null || value === "") return "-";
        var n = Math.round(Number(value));
        if (!isFinite(n)) return "-";
        var sign = n < 0 ? "-" : "";
        var v = Math.abs(n);
        if (v >= 1e8) {
            var eok = Math.floor(v / 1e8);
            var man = Math.floor((v % 1e8) / 1e4);
            return sign + KRW.format(eok) + "억" + (man ? " " + KRW.format(man) + "만" : "");
        }
        if (v >= 1e4) {
            var m = Math.floor(v / 1e4);
            var rest = v % 1e4;
            return sign + KRW.format(m) + "만" + (rest ? " " + KRW.format(rest) : "");
        }
        return sign + KRW.format(v);
    }

    // 그림 안 짧은 표기: "1,234.5억"
    function krwCompact(value) {
        var n = num(value);
        var v = Math.abs(n);
        if (v >= 1e8) return (n < 0 ? "-" : "") + (v / 1e8).toLocaleString("ko-KR", { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + "억";
        if (v >= 1e4) return (n < 0 ? "-" : "") + KRW.format(Math.round(v / 1e4)) + "만";
        return KRW.format(n);
    }

    function krwFull(value) {
        return "₩" + KRW.format(Math.round(num(value)));
    }

    function pct1(ratio) {
        return ratio == null || !isFinite(ratio) ? "-" : (ratio * 100).toFixed(1) + "%";
    }

    function cssVar(name) {
        return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    }

    // PDF 는 다크 모드여도 라이트 색을 쓴다: data-theme="light" 안에서 읽은 값
    function lightVar(name) {
        var probe = document.getElementById("ffLightProbe");
        if (!probe) {
            probe = document.createElement("div");
            probe.id = "ffLightProbe";
            probe.setAttribute("data-theme", "light");
            probe.hidden = true;
            document.body.appendChild(probe);
        }
        return getComputedStyle(probe).getPropertyValue(name).trim();
    }

    function zoomOf() {
        var shell = document.getElementById("admShell");
        return shell ? parseFloat(getComputedStyle(shell).getPropertyValue("--font-scale")) || 1 : 1;
    }

    function monthKey(m) {
        return String(m.flowMonth || "").slice(0, 7);
    }

    /* ---------- 패널 상태 ---------- */
    function setPanel($card, panelState) {
        $card.attr("data-state", panelState);
    }

    function showPanelError($card, label, api) {
        setPanel($card, "error");
        $card.children(".ff-state-error").html(
            '<div class="adm-state-error">' +
            '<div class="adm-state-error-text"><div class="adm-state-error-title">' + esc(label) + '을 불러오지 못했어요</div>' +
            '<div class="adm-state-error-desc">잠시 후 다시 시도해 주세요.</div></div>' +
            '<button type="button" class="adm-btn" data-ff-retry="' + api + '">다시 시도</button></div>');
    }

    function showPanelEmpty($card) {
        setPanel($card, "empty");
        var tpl = document.getElementById("ffEmptyTpl");
        $card.children(".ff-state-empty").empty().append(tpl.content.cloneNode(true));
    }

    function setAsOf(api) {
        var data = state[api];
        $('.ff-card[data-api="' + api + '"] [data-ff-asof]').each(function () {
            if (data && data.asOfDate) {
                $(this).text("집계 기준일 " + data.asOfDate).prop("hidden", false);
            } else {
                $(this).prop("hidden", true);
            }
        });
    }

    function addNote($ul, text, warn) {
        $ul.append($("<li>").toggleClass("is-warn", !!warn).text(text));
    }

    /* ---------- ① 요약 ---------- */
    function summaryLoading(ids) {
        ids.forEach(function (id) {
            $("#" + id).removeClass("is-error").removeAttr("data-ff-retry").css("cursor", "")
                .find("[data-ff-value]").addClass("is-loading").text("");
        });
    }

    function summarySet(id, html, title) {
        var $item = $("#" + id);
        $item.removeClass("is-error").removeAttr("data-ff-retry").css("cursor", "");
        $item.find("[data-ff-value]").removeClass("is-loading").html(html);
        if (title) $item.attr("title", title); else $item.removeAttr("title");
    }

    function summaryFail(ids, api) {
        ids.forEach(function (id) {
            var $item = $("#" + id);
            $item.addClass("is-error").attr({ "data-ff-retry": api, title: "불러오지 못했어요. 눌러서 다시 시도" }).css("cursor", "pointer");
            $item.find("[data-ff-value]").removeClass("is-loading").text("-");
        });
    }

    function renderOverviewSummary() {
        var d = state.overview;
        summarySet("ffSumOpened", d.openedAccounts == null ? "-" : KRW.format(d.openedAccounts), d.basis);
        summarySet("ffSumInflow", krwShort(d.inflowTotal), "확정된 원화 유입 합계 (가정산 제외)");
        summarySet("ffSumReinvest", d.reinvestRatio == null ? "-" : (d.reinvestRatio * 100).toFixed(1), "재투자율 = 국내 매수 체결금액 합계 ÷ 확정 원화 유입 합계 (합계 비율)");
        $("#ffAsOf").text("집계 기준일 " + (d.asOfDate || "-"));
    }

    function renderReliefSummary() {
        var d = state.relief;
        summarySet("ffSumRelief", krwShort(d.reliefTotal), "일반 과세 세액 − 최종 세액의 합계 (예상값)");
        if (d.investPerRelief == null) {
            summarySet("ffSumPerRelief", "-", "감면액이 0원이라 계산하지 않아요");
        } else {
            summarySet("ffSumPerRelief", Number(d.investPerRelief).toFixed(1), "순투자액 ÷ 감면액 (" + (d.definitionVersion || "잠정") + " 정의)");
        }
    }

    // 혜택 상태: 매도 없음은 판정 대상이 아니라 분모에서 뺀다.
    // 정상 = 영향 없음 / 부분 축소 = 부분 축소 / 취소 = 혜택 취소 + 순매수 소멸
    function leakageGroups(data) {
        var groups = { normal: 0, reduced: 0, canceled: 0, excluded: 0 };
        (data.categories || []).forEach(function (c) {
            groups[MARIA.status.leakageGroup(c.code)] += num(c.accounts);
        });
        groups.base = groups.normal + groups.reduced + groups.canceled;
        return groups;
    }

    function benefitTexts(g) {
        return ["normal", "reduced", "canceled"].map(function (k) {
            return MARIA.status.leakageGroupLabel(k) + " " + pct1(g[k] / g.base);
        });
    }

    function renderLeakageSummary() {
        var d = state.leakage;
        var g = leakageGroups(d);
        if (!g.base) {
            summarySet("ffSumBenefit", "-", "매도한 계좌가 없어요");
            return;
        }
        function part(group, tip) {
            var label = MARIA.status.leakageGroupLabel(group);
            return '<span class="ff-sum-part' + (group === "canceled" ? " is-canceled" : "") + '" title="' + esc(tip) + '">' +
                label + " " + pct1(g[group] / g.base) + "</span>";
        }
        var html = part("normal", "영향 없음 " + KRW.format(g.normal) + "계좌") +
            part("reduced", "다른 계좌 순매수로 혜택이 일부 줄어든 계좌 " + KRW.format(g.reduced) + "개") +
            part("canceled", "혜택 취소(한도초과·조기 인출·해지 등 제도적 배제)와 순매수 소멸(타 계좌 순매수로 조정비율 0)을 합산 · " + KRW.format(g.canceled) + "계좌");
        summarySet("ffSumBenefit", html,
            "분모 = 매도한 계좌 " + KRW.format(g.base) + "개 (전체 " + KRW.format(num(d.total)) + " − 매도 없음 " + KRW.format(g.excluded) + ")");
    }

    /* ---------- 월별 합계 ---------- */
    function monthsOf() {
        return (state.monthly && state.monthly.months) || [];
    }

    function totals(months) {
        var t = { sell: 0, krw: 0, buy: 0, dsell: 0, wd: 0 };
        months.forEach(function (m) {
            t.sell += num(m.foreignSellAmount);
            t.krw += num(m.krwInflowAmount);
            t.buy += num(m.domesticBuyAmount);
            t.dsell += num(m.domesticSellAmount);
            t.wd += num(m.withdrawalAmount);
        });
        return t;
    }

    /* ---------- 툴팁 (data-tip) ---------- */
    var $tip = $("#ffTip");

    function moveTip(event) {
        var zoom = zoomOf();
        var width = $tip.outerWidth() * zoom;
        var left = event.clientX + 12;
        if (left + width > window.innerWidth - 4) left = event.clientX - width - 12;
        $tip.css({ left: Math.max(4, left) / zoom, top: (event.clientY + 14) / zoom });
    }

    $(document).on("mouseenter mousemove", ".fund-flow [data-tip]", function (event) {
        $tip.text($(this).attr("data-tip")).prop("hidden", false);
        moveTip(event);
    }).on("mouseleave", ".fund-flow [data-tip]", function () {
        $tip.prop("hidden", true);
    });

    /* ---------- SVG 도우미 ---------- */
    var SVG_NS = "http://www.w3.org/2000/svg";

    function svgEl(name, attrs, parent) {
        var el = document.createElementNS(SVG_NS, name);
        Object.keys(attrs || {}).forEach(function (k) { el.setAttribute(k, attrs[k]); });
        if (parent) parent.appendChild(el);
        return el;
    }

    function svgText(parent, x, y, anchor, cls, size, text, extra) {
        var t = svgEl("text", { x: x, y: y, "text-anchor": anchor, "class": cls }, parent);
        t.setAttribute("style", "font-size:" + size + "px" + (extra || ""));
        t.textContent = text;
        return t;
    }

    function ribbon(x0, y0, h0, x1, y1, h1) {
        var xm = (x0 + x1) / 2;
        return "M" + x0 + "," + y0 + "C" + xm + "," + y0 + " " + xm + "," + y1 + " " + x1 + "," + y1 +
            "L" + x1 + "," + (y1 + h1) + "C" + xm + "," + (y1 + h1) + " " + xm + "," + (y0 + h0) + " " + x0 + "," + (y0 + h0) + "Z";
    }

    /* ---------- ② 자금 흐름 경로 (Sankey) ---------- */
    // host 안에 그린다. width 를 주면 그 폭으로(PDF), 없으면 host 의 실제 폭으로 그린다. 그릴 게 없으면 false.
    function buildSankey($host, $notes, width) {
        var months = monthsOf();
        $host.empty();
        $notes.empty();
        var t = totals(months);
        var S = t.sell, K = t.krw, B = t.buy, D = t.dsell, W = t.wd;
        var R = K + D - B - W;
        var negative = R < 0;
        if (negative) R = 0;

        width = Math.max(300, Math.floor(width || $host.width()));
        var narrow = width < 560;
        var padL = narrow ? 92 : 128;
        var padR = narrow ? 104 : 150;
        var NW = 10, GAP = 10, PAD_Y = 8, H = 214;

        var gapIn = Math.max(0, K - S);   // 확정 원화가 더 큰 만큼 (회색 유입)
        var gapOut = Math.max(0, S - K);  // 해외 매도가 더 큰 만큼 (회색 유출)

        // 열별 노드 (위 → 아래)
        var nodes = {
            A: { key: "A", name: "해외 매도", value: S, col: 0, color: "--adm-chart-bar" },
            GI: { key: "GI", name: "차이", value: gapIn, col: 0, gap: true },
            K: { key: "K", name: "확정 원화", value: K, col: 1, color: "--adm-chart-2" },
            GO: { key: "GO", name: "차이", value: gapOut, col: 1, gap: true },
            D: { key: "D", name: "국내 매도 회수", value: D, col: 1, color: "--adm-chart-5" },
            B: { key: "B", name: "국내 매수", value: B, col: 2, color: "--adm-chart-3" },
            R: { key: "R", name: "예수금 잔류", value: R, col: 2, color: "--adm-chart-gray" },
            W: { key: "W", name: "인출", value: W, col: 2, color: "--adm-chart-gold" }
        };
        var cols = [["A", "GI"], ["K", "GO", "D"], ["B", "R", "W"]].map(function (keys) {
            return keys.filter(function (k) { return nodes[k].value > 0; });
        });
        if (!cols[0].length && !cols[1].length && !cols[2].length) return false;
        var scale = Infinity;
        cols.forEach(function (keys) {
            var sum = keys.reduce(function (s, k) { return s + nodes[k].value; }, 0);
            if (sum > 0) scale = Math.min(scale, (H - PAD_Y * 2 - GAP * Math.max(0, keys.length - 1)) / sum);
        });
        var xs = [padL, 0, width - padR - NW];
        xs[1] = Math.round((xs[0] + xs[2]) / 2);

        cols.forEach(function (keys, c) {
            var y = PAD_Y;
            keys.forEach(function (k) {
                var n = nodes[k];
                n.x = xs[c];
                n.y = y;
                n.h = n.value * scale;
                n.out = 0;
                n.in = 0;
                y += n.h + GAP;
            });
        });

        // 흐름 (출처 배분: 인출 → 국내 매수 → 잔류 순으로 확정 원화를 채우고, 모자란 국내 매수는 국내 매도 회수가 채운다)
        var kw = Math.min(K, W);
        var kb = Math.min(K - kw, B);
        var kr = K - kw - kb;
        var db = Math.min(D, B - kb);
        var dr = D - db;
        var links = [
            { s: "A", t: "K", v: Math.min(S, K), c: "A" },
            { s: "A", t: "GO", v: gapOut, gap: true },
            { s: "GI", t: "K", v: gapIn, gap: true },
            { s: "K", t: "B", v: kb, c: "B" },
            { s: "K", t: "R", v: kr, c: "R" },
            { s: "K", t: "W", v: kw, c: "W" },
            { s: "D", t: "B", v: db, c: "B" },
            { s: "D", t: "R", v: dr, c: "R" }
        ].filter(function (l) { return l.v > 0 && nodes[l.s].value > 0 && nodes[l.t].value > 0; });

        var svg = svgEl("svg", { viewBox: "0 0 " + width + " " + H, height: H, role: "img", "aria-label": "해외 매도에서 확정 원화를 거쳐 국내 매수 · 예수금 잔류 · 인출로 가는 자금 흐름" });
        var gLinks = svgEl("g", {}, svg);
        var base = S || K || 1;
        var names = { A: "해외 매도", GI: "차이", K: "확정 원화", GO: "차이", D: "국내 매도 회수", B: "국내 매수", R: "예수금 잔류", W: "인출" };

        links.forEach(function (l) {
            var s = nodes[l.s], tn = nodes[l.t];
            var h = l.v * scale;
            var path = svgEl("path", { d: ribbon(s.x + NW, s.y + s.out, h, tn.x, tn.y + tn.in, h), "class": "ff-link" + (l.gap ? " is-gap" : "") }, gLinks);
            if (!l.gap) path.setAttribute("style", "fill:var(" + nodes[l.c].color + ")");
            path.setAttribute("data-tip", names[l.s] + " → " + names[l.t] + "\n" + krwShort(l.v) + "원 (" + krwFull(l.v) + ")\n해외 매도 대비 " + pct1(l.v / base));
            if (h >= 14 && tn.x - (s.x + NW) > 70) {
                svgText(svg, (s.x + NW + tn.x) / 2, s.y + s.out + h / 2 + 4, "middle", "ff-t-sub", 11, krwCompact(l.v), ";fill:var(--adm-text);pointer-events:none");
            }
            s.out += h;
            tn.in += h;
        });

        // 노드 + 이름표 (이름표끼리 30px 이상 떨어뜨린다)
        cols.forEach(function (keys, c) {
            var lastBottom = -Infinity;
            keys.forEach(function (k) {
                var n = nodes[k];
                var rect = svgEl("rect", { x: n.x, y: n.y, width: NW, height: Math.max(1.5, n.h), "class": "ff-node" + (n.gap ? " is-gap" : "") }, svg);
                if (!n.gap) rect.setAttribute("style", "fill:var(" + n.color + ")");
                var title = n.gap
                    ? (k === "GO" ? "차이 (해외 매도 − 확정 원화)" : "차이 (확정 원화 − 해외 매도)")
                    : n.name;
                rect.setAttribute("data-tip", title + "\n" + krwShort(n.value) + "원 (" + krwFull(n.value) + ")\n해외 매도 대비 " + pct1(n.value / base));

                var cy = n.y + n.h / 2;
                var ty = Math.max(cy - 14, lastBottom + 2, 2);
                lastBottom = ty + 28;
                var anchor = c === 0 ? "end" : "start";
                var tx = c === 0 ? n.x - 6 : n.x + NW + 6;
                var halo = ";paint-order:stroke;stroke:var(--adm-panel);stroke-width:3px;stroke-linejoin:round";
                svgText(svg, tx, ty + 11, anchor, "ff-t-strong ff-label", 12, n.gap ? "차이" : n.name, halo);
                svgText(svg, tx, ty + 25, anchor, "ff-t-sub ff-label", 11.5, krwCompact(n.value) + (narrow ? "" : " · " + pct1(n.value / base)), halo);
            });
        });
        $host.get(0).appendChild(svg);

        // 근거 문구
        var range = months.length ? monthKey(months[0]) + " ~ " + monthKey(months[months.length - 1]) : "-";
        var partial = months.filter(function (m) { return m.partial; }).map(monthKey);
        addNote($notes, "기간: 출시 이후 누적 · 월별 합계(" + range + ")" +
            (partial.length ? " · 진행 중인 달(" + partial.join(", ") + ")은 집계 기준일까지 포함" : "") + " · 비율은 해외 매도 대비", false);
        addNote($notes, "예수금 잔류 = 확정 원화 + 국내 매도 회수 − 국내 매수 − 인출 (월별 합계로 계산). 연결선의 출처 배분은 인출 → 국내 매수 → 잔류 순으로 채운 표시용 배치이고, 노드 금액이 실제 합계예요", false);
        if (gapOut > 0) {
            addNote($notes, "차이 " + krwShort(gapOut) + "원(" + krwFull(gapOut) + ") = 해외 매도 − 확정 원화. 환율 · 수수료와 아직 확정되지 않은 가환전분 때문에 두 금액이 같지 않아요", false);
        } else if (gapIn > 0) {
            addNote($notes, "차이 " + krwShort(gapIn) + "원(" + krwFull(gapIn) + ") = 확정 원화 − 해외 매도. 환율 변동 등으로 확정 원화가 더 커요", false);
        }
        if (negative) {
            addNote($notes, "계산상 예수금 잔류가 음수예요(국내 매수 + 인출이 확정 원화 + 국내 매도 회수보다 커요). 월별 원천 값을 확인해 주세요", true);
        }
        sankeyOverviewNote($notes, t);
        return true;
    }

    // overview 의 국내 매수(체결금액)와 월별 합계의 국내 매수(수수료 포함 예수금 순액)는 기준이 달라 값이 다르다
    function sankeyOverviewNote($notes, t) {
        $notes.find("li.ff-overview-note").remove();
        if (!state.overview || !state.monthly) return;
        var diff = t.buy - num(state.overview.domesticBuyTotal);
        $notes.append($("<li>").addClass("ff-overview-note").text(
            "상단 국내 재투자율의 국내 매수(체결금액 " + krwShort(state.overview.domesticBuyTotal) + "원)와 이 그림의 국내 매수(수수료를 포함한 예수금 순액 " +
            krwShort(t.buy) + "원)는 기준이 달라 " + (diff === 0 ? "같은 값이에요" : "차이가 " + krwShort(Math.abs(diff)) + "원 있어요")));
    }

    function renderSankey() {
        if (!buildSankey($("#ffSankey"), $("#ffSankeyNotes"))) showPanelEmpty($("#ffPanelSankey"));
    }

    /* ---------- ③ 월별 유입 · 국내 매수와 재투자율 (Chart.js) ---------- */
    function hatch(color) {
        var c = document.createElement("canvas");
        c.width = c.height = 8;
        var g = c.getContext("2d");
        g.globalAlpha = 0.22;
        g.fillStyle = color;
        g.fillRect(0, 0, 8, 8);
        g.globalAlpha = 1;
        g.strokeStyle = color;
        g.lineWidth = 1.5;
        g.beginPath();
        g.moveTo(-2, 8); g.lineTo(8, -2);
        g.moveTo(0, 10); g.lineTo(10, 0);
        g.stroke();
        return g.createPattern(c, "repeat");
    }

    function monthLabels(months) {
        var years = {};
        months.forEach(function (m) { years[monthKey(m).slice(0, 4)] = true; });
        var oneYear = Object.keys(years).length <= 1;
        return months.map(function (m) {
            var key = monthKey(m);
            var text = oneYear ? Number(key.slice(5, 7)) + "월" : key.slice(2, 4) + "." + key.slice(5, 7);
            return m.partial ? [text, "진행 중"] : text;
        });
    }

    var boundaryPlugin = {
        id: "ffBoundary",
        afterDatasetsDraw: function (c, args, opts) {
            var area = c.chartArea;
            var keys = opts.keys || [];
            if (!keys.length) return;
            var cw = (area.right - area.left) / keys.length;
            var ctx = c.ctx;
            ctx.save();
            ctx.strokeStyle = opts.color;
            ctx.fillStyle = opts.color;
            ctx.lineWidth = 1.5;
            ctx.setLineDash([3, 3]);
            ctx.font = "600 10.5px " + opts.font;
            ctx.textAlign = "center";
            BOUNDARIES.forEach(function (mm) {
                var idx = -1;
                keys.forEach(function (k, i) { if (k.slice(5, 7) === mm) idx = i; });
                if (idx < 0 || idx >= keys.length - 1) return;
                var x = Math.round(area.left + cw * (idx + 1)) + 0.5;
                ctx.beginPath();
                ctx.moveTo(x, area.top);
                ctx.lineTo(x, area.bottom);
                ctx.stroke();
                ctx.setLineDash([]);
                ctx.fillText(Number(mm) + "/31", x, area.top - 4);
                ctx.setLineDash([3, 3]);
            });
            ctx.restore();
        }
    };

    // varFn: 색 변수를 읽는 함수(화면 = 현재 테마, PDF = 라이트). responsive 를 끄면 canvas 크기 그대로 한 번만 그린다.
    function buildMonthlyChart(canvas, varFn, responsive) {
        var months = monthsOf();
        var font = getComputedStyle(document.body).fontFamily;
        var sub = varFn("--adm-sub");
        var cInflow = varFn("--adm-chart-bar");
        var cBuy = varFn("--adm-chart-3");
        var cRate = varFn("--adm-chart-gold");
        var keys = months.map(monthKey);
        var inflow = months.map(function (m) { return num(m.krwInflowAmount); });
        var buy = months.map(function (m) { return num(m.domesticBuyAmount); });
        var rate = months.map(function (m) { return num(m.krwInflowAmount) > 0 ? num(m.domesticBuyAmount) / num(m.krwInflowAmount) : null; });
        var pInflow = hatch(cInflow);
        var pBuy = hatch(cBuy);
        function colors(solid, pattern) {
            return months.map(function (m) { return m.partial ? pattern : solid; });
        }
        function eokTick(v) {
            return KRW.format(Math.round(v / 1e8)) + "억";
        }
        var borderW = months.map(function (m) { return m.partial ? 1 : 0; });
        return new Chart(canvas, {
            data: {
                labels: monthLabels(months),
                datasets: [
                    { type: "bar", label: "확정 원화 유입", data: inflow, backgroundColor: colors(cInflow, pInflow), borderColor: cInflow, borderWidth: borderW, yAxisID: "y", order: 2, maxBarThickness: 28 },
                    { type: "bar", label: "국내 매수", data: buy, backgroundColor: colors(cBuy, pBuy), borderColor: cBuy, borderWidth: borderW, yAxisID: "y", order: 2, maxBarThickness: 28 },
                    { type: "line", label: "월 재투자율", data: rate, borderColor: cRate, backgroundColor: cRate, pointBackgroundColor: cRate, pointRadius: 3, borderWidth: 2, tension: 0, yAxisID: "y1", order: 1 }
                ]
            },
            options: {
                responsive: responsive,
                maintainAspectRatio: false,
                animation: false,
                interaction: { mode: "index", intersect: false },
                layout: { padding: { top: 14 } },
                plugins: {
                    legend: { position: "top", align: "start", labels: { boxWidth: 10, boxHeight: 10, color: sub, font: { size: 11, family: font }, padding: 8 } },
                    tooltip: {
                        callbacks: {
                            label: function (ctx) {
                                if (ctx.dataset.yAxisID === "y1") return ctx.dataset.label + " " + pct1(ctx.parsed.y);
                                var m = months[ctx.dataIndex];
                                return ctx.dataset.label + " " + krwShort(ctx.parsed.y) + "원" + (ctx.dataset.label === "국내 매수" && num(m.krwInflowAmount) > 0 ? " (유입 대비 " + pct1(num(m.domesticBuyAmount) / num(m.krwInflowAmount)) + ")" : "");
                            },
                            afterBody: function (items) {
                                var m = months[items[0].dataIndex];
                                return m && m.partial ? "진행 중인 달(집계 기준일까지)" : "";
                            }
                        }
                    },
                    ffBoundary: { keys: keys, color: cRate, font: font }
                },
                scales: {
                    x: { grid: { display: false }, ticks: { color: sub, font: { size: 11, family: font }, maxRotation: 0 }, border: { color: varFn("--adm-bd2") } },
                    y: { beginAtZero: true, grid: { color: varFn("--adm-line") }, border: { display: false }, ticks: { color: sub, font: { size: 11, family: font }, callback: eokTick, maxTicksLimit: 6 } },
                    y1: { position: "right", beginAtZero: true, grid: { display: false }, border: { display: false }, ticks: { color: sub, font: { size: 11, family: font }, callback: function (v) { return Math.round(v * 100) + "%"; }, maxTicksLimit: 6 } }
                }
            },
            plugins: [boundaryPlugin]
        });
    }

    function renderMonthly() {
        var $notes = $("#ffMonthlyNotes").empty();
        if (chart) { chart.destroy(); chart = null; }
        if (!window.Chart) {
            showPanelError($("#ffPanelMonthly"), "차트 도구", "monthly");
            return;
        }
        chart = buildMonthlyChart(document.getElementById("ffMonthlyChart"), cssVar, true);
        addNote($notes, "월 재투자율 = 국내 매수 ÷ 확정 원화 유입 (둘 다 월별 합계, 국내 매수는 수수료를 포함한 예수금 순액이라 상단 재투자율과 값이 달라요)", false);
        addNote($notes, "세로 점선 = 공제율 구간 경계(5/31, 7/31), 빗금 = 진행 중인 달(집계 기준일까지)", false);
    }

    /* ---------- ④ 예수금 브리지 (워터폴) ---------- */
    function fillBridgeSelect() {
        var months = monthsOf();
        var $sel = $("#ffBridgeMonth").empty();
        var last = -1;
        months.forEach(function (m, i) {
            $sel.append($("<option>").val(i).text(monthKey(m) + (m.partial ? " (진행 중)" : "")));
            if (!m.partial) last = i;
        });
        $sel.val(String(last >= 0 ? last : months.length - 1));
    }

    function buildBridge($host, $notes, idx, width) {
        var months = monthsOf();
        var m = months[idx];
        $host.empty();
        $notes.empty();
        if (!m) return;

        var inflow = num(m.krwInflowAmount), buy = num(m.domesticBuyAmount), dsell = num(m.domesticSellAmount), wd = num(m.withdrawalAmount);
        var begin = m.cashBalanceBegin == null ? null : num(m.cashBalanceBegin);
        var beginFromPrev = false;
        if (begin == null && idx > 0 && months[idx - 1].cashBalanceEnd != null) {
            begin = num(months[idx - 1].cashBalanceEnd);
            beginFromPrev = true;
        }
        var end = m.cashBalanceEnd == null ? null : num(m.cashBalanceEnd);
        var run = begin == null ? 0 : begin;
        var expected = run + inflow - buy + dsell - wd;
        var diff = end == null ? null : end - expected;

        var steps = [];
        steps.push({ label: "월초 예수금", kind: "total", value: begin, missing: begin == null });
        steps.push({ label: "확정 원화 유입", kind: "up", delta: inflow });
        steps.push({ label: "국내 매수", kind: "down", delta: -buy });
        steps.push({ label: "국내 매도", kind: "up", delta: dsell });
        steps.push({ label: "인출", kind: "down", delta: -wd });
        if (diff != null && Math.abs(diff) >= 1) steps.push({ label: "차이", kind: "gap", delta: diff });
        steps.push({ label: "월말 예수금", kind: "total", value: end, missing: end == null, pending: m.partial });

        // 누적 위치 계산
        var cur = 0;
        steps.forEach(function (s) {
            if (s.kind === "total") {
                s.from = 0;
                s.to = s.missing ? 0 : s.value;
                if (s.label === "월초 예수금") cur = s.missing ? 0 : s.value;
            } else {
                s.from = cur;
                cur += s.delta;
                s.to = cur;
            }
        });
        var hi = Math.max.apply(null, steps.map(function (s) { return Math.max(s.from, s.to); }).concat([0]));
        var lo = Math.min.apply(null, steps.map(function (s) { return Math.min(s.from, s.to); }).concat([0]));
        if (hi === lo) hi = lo + 1;

        width = Math.max(300, Math.floor(width || $host.width()));
        var H = 214, top = 22, bottom = 36, left = 4, right = 4;
        var plotH = H - top - bottom;
        var cw = (width - left - right) / steps.length;
        var bw = Math.min(40, cw * 0.62);
        function y(v) { return top + (hi - v) / (hi - lo) * plotH; }

        var svg = svgEl("svg", { viewBox: "0 0 " + width + " " + H, height: H, role: "img", "aria-label": "월초 예수금에서 월말 예수금까지의 증감" });
        svgEl("line", { x1: left, x2: width - right, y1: y(0), y2: y(0), "class": "ff-axis" }, svg);

        steps.forEach(function (s, i) {
            var cx = left + cw * i + cw / 2;
            var x0 = cx - bw / 2;
            var yTop = y(Math.max(s.from, s.to)), yBot = y(Math.min(s.from, s.to));
            var h = Math.max(1.5, yBot - yTop);
            var color = s.kind === "total" ? "var(--adm-chart-bar)" : s.kind === "up" ? "var(--adm-chart-3)" : s.kind === "down" ? "var(--adm-chart-gold)" : "var(--adm-bd2)";
            var valueText;
            var tip;
            if (s.kind === "total") {
                if (s.missing) {
                    valueText = s.pending ? "진행 중" : "-";
                    tip = s.label + "\n" + (s.pending ? "진행 중인 달이라 월말 예수금이 아직 없어요" : "값이 없어요");
                } else {
                    valueText = krwCompact(s.value);
                    tip = s.label + "\n" + krwShort(s.value) + "원 (" + krwFull(s.value) + ")";
                }
            } else {
                valueText = (s.delta >= 0 ? "+" : "-") + krwCompact(Math.abs(s.delta));
                tip = s.label + "\n" + (s.delta >= 0 ? "+" : "-") + krwShort(Math.abs(s.delta)) + "원 (" + krwFull(Math.abs(s.delta)) + ")" +
                    (inflow > 0 && s.kind !== "gap" ? "\n확정 원화 유입 대비 " + pct1(Math.abs(s.delta) / inflow) : "");
            }
            if (!(s.kind === "total" && s.missing)) {
                var rect = svgEl("rect", { x: x0, y: yTop, width: bw, height: h, "class": "ff-bar-rect" }, svg);
                rect.setAttribute("style", "fill:" + color);
                rect.setAttribute("data-tip", tip);
            } else {
                var hit = svgEl("rect", { x: x0, y: top, width: bw, height: plotH, fill: "transparent" }, svg);
                hit.setAttribute("data-tip", tip);
            }
            svgText(svg, cx, (s.kind === "total" && s.missing ? y(0) - 6 : yTop - 4), "middle", "ff-t-strong", 11, valueText);
            var parts = s.label.split(" ");
            var lines = parts.length > 1 ? [parts[0], parts.slice(1).join(" ")] : [s.label];
            lines.forEach(function (ln, li) {
                svgText(svg, cx, H - bottom + 14 + li * 13, "middle", "ff-t-sub", 11, ln);
            });
            if (i < steps.length - 1 && !(steps[i + 1].kind === "total" && steps[i + 1].missing)) {
                var nx = left + cw * (i + 1) + cw / 2 - bw / 2;
                svgEl("line", { x1: x0 + bw, x2: nx, y1: y(s.to), y2: y(s.to), "class": "ff-conn" }, svg);
            }
        });
        $host.get(0).appendChild(svg);

        // 근거 · 검산 문구
        addNote($notes, "월말 예수금 = 월초 예수금 + 확정 원화 유입 − 국내 매수 + 국내 매도 − 인출 (국내 매수는 수수료 포함, 국내 매도는 수수료 · 거래세를 뺀 순액)", false);
        if (m.partial) {
            addNote($notes, "진행 중인 달이라 월말 예수금이 아직 없어요(집계 기준일까지의 증감만 보여요)" + (beginFromPrev ? ". 월초는 전월 월말 예수금을 그대로 썼어요" : ""), false);
        } else if (begin == null) {
            addNote($notes, "월초 예수금 값이 없어 0에서 시작해 그렸어요", true);
        }
        if (diff != null) {
            if (Math.abs(diff) >= 1) {
                addNote($notes, "계산과 월말 예수금이 " + krwShort(Math.abs(diff)) + "원(" + krwFull(Math.abs(diff)) + ") 달라 차이 막대로 표시했어요", true);
            } else {
                addNote($notes, "계산한 값과 월말 예수금이 일치해요", false);
            }
        }
    }

    function renderBridge() {
        buildBridge($("#ffBridge"), $("#ffBridgeNotes"), Number($("#ffBridgeMonth").val()));
    }

    /* ---------- ⑤ 퍼널 (CSS 가로 막대) ---------- */
    function renderFunnel() {
        var d = state.funnel || {};
        var stages = d.stages || [];
        var $card = $("#ffPanelFunnel");
        if (!stages.length) { showPanelEmpty($card); return; }
        setPanel($card, "ready");
        var max = Math.max.apply(null, stages.map(function (s) { return num(s.customers); })) || 1;
        var html = "";
        stages.forEach(function (s, i) {
            var prev = i ? num(stages[i - 1].customers) : null;
            var cust = num(s.customers);
            var drop = prev == null ? 0 : Math.max(0, prev - cust);
            var tip = s.stage + "\n" + KRW.format(cust) + "명" +
                (s.convTotal != null ? "\n처음 대비 " + pct1(Number(s.convTotal)) : "") +
                (s.convPrev != null ? "\n직전 대비 " + pct1(Number(s.convPrev)) + " · 이탈 " + KRW.format(drop) + "명" : "");
            html += '<div class="ff-fun-row" data-tip="' + esc(tip) + '">' +
                '<span class="ff-fun-name">' + esc(s.stage) + '</span>' +
                '<div class="ff-fun-track"><div class="ff-fun-fill" style="width:' + (cust / max * 100).toFixed(2) + '%"></div>' +
                (drop ? '<div class="ff-fun-drop" style="left:' + (cust / max * 100).toFixed(2) + '%;width:' + (drop / max * 100).toFixed(2) + '%"></div>' : "") + '</div>' +
                '<b class="ff-fun-val">' + KRW.format(cust) + '명</b>' +
                '<span class="ff-fun-pct">' + (s.convTotal == null ? "-" : pct1(Number(s.convTotal))) + '</span></div>' +
                '<div class="ff-fun-sub">' + (s.convPrev == null ? "처음 단계" : "직전 대비 " + pct1(Number(s.convPrev)) + " · 이탈 " + KRW.format(drop) + "명") + '</div>';
        });
        $("#ffFunnel").html(html);
        var $notes = $("#ffFunnelNotes").empty();
        addNote($notes, "고객 수 기준 · 오른쪽 % = 처음 단계(웹 가입) 대비, 회색 막대 = 직전 단계에서 이탈한 고객(직전 단계 − 이 단계)", false);
        if (d.basis) addNote($notes, d.basis, false);
    }

    /* ---------- ⑥ 혜택 누수 (100% 누적 막대) ---------- */
    function renderLeakage() {
        var d = state.leakage || {};
        var $card = $("#ffPanelLeakage");
        var cats = LEAK_ORDER.map(function (code) {
            return (d.categories || []).filter(function (c) { return c.code === code; })[0];
        }).filter(Boolean);
        var total = cats.reduce(function (s, c) { return s + num(c.accounts); }, 0);
        if (!cats.length || !total) { showPanelEmpty($card); return; }
        setPanel($card, "ready");
        var g = leakageGroups(d);

        var bar = "";
        cats.forEach(function (c) {
            var share = num(c.accounts) / total;
            var tip = MARIA.status.leakageLabel(c.code) + "\n" + KRW.format(num(c.accounts)) + "계좌 (전체의 " + pct1(share) + ")";
            bar += '<div class="ff-stack-seg' + (LIGHT_BG[LEAK_COLOR[c.code]] ? ' is-light' : '') + '" style="width:' + (share * 100).toFixed(3) + '%;background:var(' + LEAK_COLOR[c.code] + ')" data-tip="' + esc(tip) + '">' +
                (share >= 0.07 ? '<span>' + pct1(share) + '</span>' : "") + '</div>';
        });
        $("#ffLeakBar").html(bar);

        var rows = '<div class="ff-leak-row is-head"><span>분류</span><span class="is-r">계좌 수</span><span class="is-r">전체 비중</span><span class="is-r">매도 계좌 비중</span><span class="is-r">타 계좌 순매수 있음</span></div>';
        cats.forEach(function (c) {
            var group = MARIA.status.leakageGroup(c.code);
            var chip = group === "excluded" ? '<span class="adm-chip adm-chip-off">판정 제외</span>' :
                group === "canceled" ? '<span class="adm-chip adm-chip-warn">취소</span>' : "";
            rows += '<div class="ff-leak-row"><span class="ff-leak-name"><i style="background:var(' + LEAK_COLOR[c.code] + ')"></i>' + esc(MARIA.status.leakageLabel(c.code)) + chip + '</span>' +
                '<span class="is-r">' + KRW.format(num(c.accounts)) + '</span>' +
                '<span class="is-r">' + (c.share == null ? pct1(num(c.accounts) / total) : pct1(Number(c.share))) + '</span>' +
                '<span class="is-r">' + (group === "excluded" ? "-" : pct1(num(c.accounts) / g.base)) + '</span>' +
                '<span class="is-r">' + KRW.format(num(c.withExternalBuy)) + '</span></div>';
        });
        $("#ffLeakRows").html(rows);

        var $notes = $("#ffLeakNotes").empty();
        var extSold = cats.filter(function (c) { return MARIA.status.leakageGroup(c.code) !== "excluded"; })
            .reduce(function (s, c) { return s + num(c.withExternalBuy); }, 0);
        addNote($notes, "다른 계좌 순매수가 있는 계좌 비율(매도한 계좌 기준) = " + KRW.format(extSold) + " ÷ " + KRW.format(g.base) + " = " + pct1(extSold / g.base), false);
        // 요약 ①-6 과 숫자 일치 검산: 이 패널 행으로 다시 계산한 값 vs 요약 칸에 실제로 보이는 값
        function rowsOf(code) {
            var c = cats.filter(function (x) { return x.code === code; })[0];
            return c ? num(c.accounts) : 0;
        }
        var again = benefitTexts({ normal: rowsOf("NO_IMPACT"), reduced: rowsOf("PARTIAL_REDUCED"), canceled: rowsOf("BENEFIT_CANCELED") + rowsOf("EXTERNAL_BUY_OFFSET"), base: g.base });
        var shown = $("#ffSumBenefit .ff-sum-part").map(function () { return $(this).text().trim(); }).get();
        var ok = shown.length === 3 && shown.every(function (t, i) { return t === again[i]; });
        $notes.append($("<li>").toggleClass("is-warn", !ok).addClass("ff-leak-check").text(
            (ok ? "요약 ①-6과 일치 ✓ " : "요약 ①-6과 달라요 ✗ ") + "· 분모 = 매도한 계좌 " + KRW.format(g.base) + "개 (전체 " + KRW.format(total) + " − 매도 없음 " + KRW.format(g.excluded) + "), " +
            again.join(" · ") + " (취소 = 혜택 취소 + 순매수 소멸)"));
        if (d.snapshotCalculatedAt) addNote($notes, "세액 스냅샷 계산 시각 " + String(d.snapshotCalculatedAt).replace("T", " ").slice(0, 16) + " · 표시용 예상값이며 확정 세액이 아니에요", false);
    }

    /* ---------- ⑦ 감면 성과 (워터폴, 잠정) ---------- */
    function buildRelief($host, width) {
        var d = state.relief || {};
        $host.empty();
        var general = num(d.generalTaxTotal), relief = num(d.reliefTotal), fin = num(d.finalTaxTotal);
        var diff = general - relief - fin;
        var steps = [
            { label: "일반 과세 세액", kind: "total", from: 0, to: general, v: general },
            { label: "감면액", kind: "down", from: general, to: general - relief, v: -relief },
            { label: "최종 세액", kind: "total", from: 0, to: fin, v: fin }
        ];
        if (Math.abs(diff) >= 1) steps.splice(2, 0, { label: "차이", kind: "gap", from: general - relief, to: general - relief - diff, v: -diff });
        var hi = Math.max.apply(null, steps.map(function (s) { return Math.max(s.from, s.to); }).concat([1]));
        var lo = Math.min.apply(null, steps.map(function (s) { return Math.min(s.from, s.to); }).concat([0]));
        width = Math.max(200, Math.floor(width || $host.width()));
        var H = 190, top = 22, bottom = 30;
        var plotH = H - top - bottom;
        var cw = width / steps.length;
        var bw = Math.min(46, cw * 0.6);
        function y(v) { return top + (hi - v) / (hi - lo) * plotH; }
        var svg = svgEl("svg", { viewBox: "0 0 " + width + " " + H, height: H, role: "img", "aria-label": "일반 과세 세액에서 감면액을 빼 최종 세액이 되는 과정" });
        svgEl("line", { x1: 0, x2: width, y1: y(0), y2: y(0), "class": "ff-axis" }, svg);
        steps.forEach(function (s, i) {
            var cx = cw * i + cw / 2;
            var yTop = y(Math.max(s.from, s.to)), yBot = y(Math.min(s.from, s.to));
            var color = s.kind === "total" ? (i === 0 ? "var(--adm-chart-bar)" : "var(--adm-chart-3)") : s.kind === "down" ? "var(--adm-chart-gold)" : "var(--adm-bd2)";
            var rect = svgEl("rect", { x: cx - bw / 2, y: yTop, width: bw, height: Math.max(1.5, yBot - yTop), "class": "ff-bar-rect" }, svg);
            rect.setAttribute("style", "fill:" + color);
            rect.setAttribute("data-tip", s.label + "\n" + (s.v < 0 ? "-" : "") + krwShort(Math.abs(s.v)) + "원 (" + krwFull(Math.abs(s.v)) + ")" +
                (general > 0 ? "\n일반 과세 세액 대비 " + pct1(Math.abs(s.v) / general) : ""));
            svgText(svg, cx, yTop - 4, "middle", "ff-t-strong", 11, (s.v < 0 ? "-" : "") + krwCompact(Math.abs(s.v)));
            svgText(svg, cx, H - bottom + 14, "middle", "ff-t-sub", 11, s.label);
            if (i < steps.length - 1) {
                svgEl("line", { x1: cx + bw / 2, x2: cw * (i + 1) + cw / 2 - bw / 2, y1: y(s.to), y2: y(s.to), "class": "ff-conn" }, svg);
            }
        });
        $host.get(0).appendChild(svg);
        return diff;
    }

    function renderRelief() {
        var d = state.relief || {};
        var $card = $("#ffPanelRelief");
        if (d.generalTaxTotal == null && d.reliefTotal == null) { showPanelEmpty($card); return; }
        setPanel($card, "ready");
        var diff = buildRelief($("#ffRelief"));
        var perRelief = d.investPerRelief == null ? "-" : Number(d.investPerRelief).toFixed(1);
        var withRelief = d.accountsWithRelief, accounts = d.accounts;
        $("#ffReliefBig").html(
            '<div class="ff-big-label">감면 1원당 국내 운용</div>' +
            '<div class="ff-big-value">' + esc(perRelief) + '<small>원</small></div>' +
            '<div class="ff-big-sub">순투자액 ÷ 감면액 (' + esc(d.definitionVersion || "잠정") + ' 정의)</div>' +
            '<div class="ff-big-line"><span>감면을 받는 계좌</span><b>' + (withRelief == null ? "-" : KRW.format(withRelief)) + ' / ' + (accounts == null ? "-" : KRW.format(accounts)) +
            (withRelief != null && accounts ? ' (' + pct1(withRelief / accounts) + ')' : '') + '</b></div>');
        $("#ffReliefKv").html(
            '<div>일반 과세 세액 합계<b>' + krwShort(d.generalTaxTotal) + '원</b></div>' +
            '<div>최종 세액 합계<b>' + krwShort(d.finalTaxTotal) + '원</b></div>' +
            '<div>감면액 합계<b>' + krwShort(d.reliefTotal) + '원</b></div>' +
            '<div>감면 비율(감면 ÷ 일반 과세)<b>' + (num(d.generalTaxTotal) > 0 ? pct1(num(d.reliefTotal) / num(d.generalTaxTotal)) : "-") + '</b></div>');
        var $notes = $("#ffReliefNotes").empty();
        addNote($notes, "감면액 = 일반 과세 세액 − 최종 세액 (응답 값, 예상값). 일반 과세 세액 = 세율 × max(0, 비가중 총양도소득 − 기본공제) · 정의 " + (d.definitionVersion || "-") + " · 범위 " + (d.scope || "-") + "(" + (d.scope === "POSITIVE" ? "감면 > 0 계좌만" : "전체 개설자") + ")", false);
        addNote($notes, Math.abs(diff) >= 1 ? "일반 과세 − 감면 − 최종 세액이 " + krwShort(Math.abs(diff)) + "원 달라 차이 막대로 표시했어요" : "일반 과세 세액 − 감면액 = 최종 세액이 맞아요", Math.abs(diff) >= 1);
        addNote($notes, "잠정 지표예요: 감면 계산에 쓰는 양도소득 범위가 아직 확정되지 않았어요(표시용 스냅샷 기준 예상값)", true);
    }

    /* ---------- ⑧ 국내 운용처 ---------- */
    function renderAllocation() {
        var d = state.allocation || {};
        var $card = $("#ffPanelAllocation");
        var buckets = d.buckets || [];
        if (!buckets.length && !(d.topNetBuy || []).length) { showPanelEmpty($card); return; }
        setPanel($card, "ready");
        var total = num(d.totalValue);

        // 응답 buckets(업종별 + ETF·펀드)를 화면에서 묶는다: 업종 상위 5 · ETF·펀드(따로) · 기타(나머지 업종). 금액은 합산만, 비중 = 금액 ÷ buckets 합계
        var ETF = "ETF·펀드";
        var sectors = buckets.filter(function (x) { return x.label !== ETF; }).sort(function (x, y) { return num(y.value) - num(x.value); });
        var etf = buckets.filter(function (x) { return x.label === ETF; })[0];
        var bucketSum = buckets.reduce(function (sum, x) { return sum + num(x.value); }, 0) || total || 1;
        var groups = sectors.slice(0, 5).map(function (x) { return { label: x.label, value: num(x.value), names: [x.label] }; });
        if (etf) groups.push({ label: ETF, value: num(etf.value), names: [ETF], etf: true });
        var rest = sectors.slice(5);
        if (rest.length) {
            groups.push({ label: "기타", value: rest.reduce(function (sum, x) { return sum + num(x.value); }, 0), names: rest.map(function (x) { return x.label; }), rest: true });
        }
        var bar = "", legend = "";
        groups.forEach(function (gp, i) {
            var color = gp.rest ? "--adm-chart-gray" : gp.etf ? "--adm-chart-gold" : BUCKET_COLOR_SECTOR[i % BUCKET_COLOR_SECTOR.length];
            var share = gp.value / bucketSum;
            var tip = gp.label + (gp.rest ? " (나머지 업종 " + gp.names.length + "개 합산)" : "") + "\n" + krwShort(gp.value) + "원 (" + krwFull(gp.value) + ")\n전체의 " + pct1(share) +
                (gp.rest ? "\n" + gp.names.join(", ") : "");
            bar += '<div class="ff-stack-seg' + (LIGHT_BG[color] ? ' is-light' : '') + '" style="width:' + (share * 100).toFixed(3) + '%;background:var(' + color + ')" data-tip="' + esc(tip) + '">' + (share >= 0.07 ? '<span>' + pct1(share) + '</span>' : "") + '</div>';
            legend += '<span class="ff-legend-item" data-tip="' + esc(tip) + '"><i style="background:var(' + color + ')"></i>' + esc(gp.label) + (gp.rest ? '(' + gp.names.length + '개 업종)' : '') + ' <b>' + krwShort(gp.value) + '원</b> ' + pct1(share) + '</span>';
        });
        $("#ffAllocBar").html(bar);
        $("#ffAllocLegend").html(legend + '<span class="ff-legend-item is-total">합계 <b>' + krwShort(total) + '원</b></span>');

        function hhiRow(label, v, tip) {
            if (v == null) return "";
            var x = Math.max(0, Math.min(1, Number(v)));
            return '<div class="ff-hhi-row" data-tip="' + esc(tip) + '"><span class="ff-hhi-name">' + label + '</span>' +
                '<div class="ff-hhi-track"><i style="left:' + (x * 100).toFixed(2) + '%"></i></div><b>' + Number(v).toFixed(3) + '</b></div>';
        }
        $("#ffHhi").html(
            hhiRow("구성 쏠림(HHI)", d.hhiBucket, "API 가 전체 buckets(업종 " + sectors.length + "개 + ETF·펀드 " + (etf ? 1 : 0) + "개)로 계산한 점유율² 합 = " + Number(d.hhiBucket).toFixed(4) + " (0 분산 ~ 1 쏠림). 위 막대의 묶음(상위 5 + 기타)으로 다시 계산한 값이 아니에요") +
            hhiRow("종목 쏠림(HHI)", d.hhiProduct, "종목 점유율² 합 = " + Number(d.hhiProduct).toFixed(4) + " (0 분산 ~ 1 쏠림)") +
            '<div class="ff-hhi-scale"><span></span><span>0 분산</span><span>1 쏠림</span><span></span></div>');

        var top = (d.topNetBuy || []).slice(0, 10);
        var maxBuy = Math.max.apply(null, top.map(function (t) { return Math.abs(num(t.netBuyExec)); }).concat([1]));
        var rows = "";
        top.forEach(function (t, i) {
            var v = num(t.netBuyExec);
            var tip = t.name + "\n" + MARIA.status.domesticTypeLabel(t.type) + (t.sectorName ? " · " + t.sectorName : "") + "\n순매수 " + krwShort(v) + "원 (" + krwFull(v) + ")";
            rows += '<div class="ff-top-row" data-tip="' + esc(tip) + '"><span class="ff-top-rank">' + (i + 1) + '</span>' +
                '<span class="ff-top-name">' + esc(t.name) + '<small>' + esc(t.sectorName || MARIA.status.domesticTypeLabel(t.type)) + '</small></span>' +
                '<div class="ff-top-track"><div style="width:' + (Math.abs(v) / maxBuy * 100).toFixed(2) + '%"></div></div>' +
                '<b class="ff-top-val">' + krwCompact(v) + '</b></div>';
        });
        $("#ffTopBuy").html(rows || '<div class="ff-soon-text">순매수 종목이 없어요</div>');

        var $notes = $("#ffAllocNotes").empty();
        addNote($notes, "매입금액 기준(보유수량 × 평균 매입가)이고 현재가 평가액이 아니에요. 예수금은 포함하지 않아요(보유 종목 매입금액만)", false);
        addNote($notes, "막대·범례는 응답 buckets 를 업종 상위 5 + ETF·펀드(따로) + 기타(나머지 업종 " + rest.length + "개)로 묶은 것이고, 비중 = 금액 ÷ buckets 합계(" + krwShort(bucketSum) + "원)예요", false);
        addNote($notes, "HHI = 점유율² 합, 0(분산) ~ 1(쏠림). 응답 값 그대로이며 묶기 전 전체 buckets(업종 " + sectors.length + "개 + ETF·펀드) 기준이에요. 순매수 = 매수 체결금액 − 매도 체결금액", false);
        if (d.basis) addNote($notes, d.basis, false);
    }

    /* ---------- ⑨ 연령대 × 투자성향 히트맵 ---------- */
    function renderSegments() {
        var d = state.segments || {};
        var $card = $("#ffPanelSegments");
        var cells = d.cells || [];
        if (!cells.length) { showPanelEmpty($card); return; }
        setPanel($card, "ready");
        var map = {};
        var ages = [], types = {};
        cells.forEach(function (c) {
            map[(c.ageBand || "") + "|" + (c.investorType || "")] = c;
            if (c.ageBand && ages.indexOf(c.ageBand) < 0) ages.push(c.ageBand);
            if (c.investorType) types[c.investorType] = true;
        });
        var typeList = MARIA.status.INVESTOR_TYPE_ORDER.filter(function (t) { return types[t]; });
        Object.keys(types).forEach(function (t) { if (typeList.indexOf(t) < 0) typeList.push(t); });
        var bandOrder = ["20대 이하", "30대", "40대", "50대", "60대 이상"];
        ages.sort(function (a, b) {
            var ia = bandOrder.indexOf(a), ib = bandOrder.indexOf(b);
            return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
        });

        var values = [];
        ages.forEach(function (a) {
            typeList.forEach(function (t) {
                var c = map[a + "|" + t];
                if (c && c[segMetric] != null) values.push(Number(c[segMetric]));
            });
        });
        var min = Math.min.apply(null, values.concat([1])), max = Math.max.apply(null, values.concat([0]));
        var nullCount = 0, bodyCount = 0;
        var metricLabel = segMetric === "reinvestRatio" ? "재투자율" : "순투자율";

        function cell(c, isBody, label) {
            if (!c) return '<div class="ff-heat-cell is-empty"></div>';
            if (isBody) bodyCount++;
            var v = c[segMetric];
            var tipHead = label + "\n" + KRW.format(num(c.accounts)) + "계좌";
            if (v == null) {
                if (isBody) nullCount++;
                return '<div class="ff-heat-cell is-null" data-tip="' + esc(tipHead + "\n5명 미만이라 비공개") + '">-</div>';
            }
            var p = max > min ? (Number(v) - min) / (max - min) : 0.5;
            var mix = Math.round(14 + p * 72);
            var tip = tipHead + "\n" + metricLabel + " " + pct1(Number(v)) + "\n유입 " + krwShort(c.inflowTotal) + "원 · 매수 " + krwShort(c.buyTotal) + "원";
            return '<div class="ff-heat-cell' + (p > 0.55 ? ' is-hot' : '') + (isBody ? '' : ' is-sum') + '" style="background:color-mix(in srgb, var(--adm-chart-3) ' + mix + '%, var(--adm-panel))" data-tip="' + esc(tip) + '">' + pct1(Number(v)) + '</div>';
        }

        var html = '<div class="ff-heat-row"><div class="ff-heat-h"></div>';
        typeList.forEach(function (t) { html += '<div class="ff-heat-h">' + esc(MARIA.status.investorTypeLabel(t)) + '</div>'; });
        html += '<div class="ff-heat-h is-sum">소계</div></div>';
        ages.forEach(function (a) {
            html += '<div class="ff-heat-row"><div class="ff-heat-h is-row">' + esc(a) + '</div>';
            typeList.forEach(function (t) {
                html += cell(map[a + "|" + t], true, a + " · " + MARIA.status.investorTypeLabel(t));
            });
            html += cell(map[a + "|"], false, a + " 소계") + '</div>';
        });
        html += '<div class="ff-heat-row is-total"><div class="ff-heat-h is-row">전체</div>';
        typeList.forEach(function (t) { html += cell(map["|" + t], false, "전체 · " + MARIA.status.investorTypeLabel(t)); });
        html += cell(map["|"], false, "전체") + '</div>';
        $("#ffHeat").html(html).css("--ff-heat-cols", typeList.length + 1);
        $("#ffHeatLegend").html('<i class="ff-heat-ramp"></i>낮음 → 높음 <span class="ff-heat-null">-</span> 5명 미만 비공개 <b>' + nullCount + '칸</b>');
        var $notes = $("#ffSegNotes").empty();
        addNote($notes, "칸 값 = 칸별 합계 비율(" + (segMetric === "reinvestRatio" ? "Σ국내 매수 체결금액 ÷ Σ확정 유입" : "(Σ매수 − Σ매도) ÷ Σ확정 유입") + ") — 계좌별 비율의 평균이나 중앙값이 아니에요. 색은 이 표 안 최솟값 " + pct1(min) + " ~ 최댓값 " + pct1(max), false);
        addNote($notes, "5명 미만인 칸은 개인 식별 방지를 위해 비율을 가려요(비공개 " + nullCount + "칸 / 표시 칸 " + bodyCount + "개)", false);
        if (d.basis) addNote($notes, d.basis, false);
    }

    /* ---------- ⑩ 지표 정의 · 데이터 품질 ---------- */
    function asOfOf(api) {
        return state[api] && state[api].asOfDate ? state[api].asOfDate : "-";
    }

    function renderDefs() {
        var lk = state.leakage;
        var snap = lk && lk.snapshotCalculatedAt ? String(lk.snapshotCalculatedAt).replace("T", " ").slice(0, 16) : null;
        var scope = (state.relief && state.relief.scope) || "-";
        var rows = [
            ["개설 계좌 수", "개설 완료 계좌", "-", "계좌 개설자", asOfOf("overview")],
            ["복귀 금액(확정 원화)", "Σ 확정 원화 유입(가정산 제외)", "-", "계좌 개설자", asOfOf("overview")],
            ["국내 재투자율", "Σ 국내 매수 체결금액(수수료 제외)", "Σ 확정 원화 유입", "계좌 개설자", asOfOf("overview")],
            ["월별 자금 흐름", "월별 합계(확정 원화·국내 매수·국내 매도·인출, 수수료 포함 예수금 순액)", "-", "계좌 개설자(진행 중인 달은 집계 기준일까지)", asOfOf("monthly")],
            ["퍼널(고객 수)", "단계별 고객 수(고객당 처음 도달)", "앞 단계를 지난 고객", "가입 고객", asOfOf("funnel")],
            ["혜택 상태(정상·부분 축소·취소)", "영향 없음 / 부분 축소 / 혜택 취소 + 순매수 소멸 계좌 수", "매도한 계좌 수(전체 − 매도 없음)", "세액 스냅샷 계좌", snap || asOfOf("leakage")],
            ["혜택 누수 5분류", "분류별 계좌 수", "세액 스냅샷 계좌 수", "계좌 개설자", snap || asOfOf("leakage")],
            ["예상 감면액 [잠정]", "일반 과세 세액 − 최종 세액 합계", "-", "세액 스냅샷 · 범위 " + scope, asOfOf("relief")],
            ["감면 1원당 국내 운용액 [잠정]", "순투자액(국내 매수 − 국내 매도)", "예상 감면액", "세액 스냅샷 · 범위 " + scope, asOfOf("relief")],
            ["국내 운용처", "보유 종목 매입금액(수량 × 평균 매입가)", "운용처 합계", "보유 수량 > 0", asOfOf("allocation")],
            ["연령대 × 투자성향", "칸별 Σ 국내 매수 체결금액 · 순투자액", "칸별 Σ 확정 원화 유입", "5명 미만 칸은 비공개", asOfOf("segments")]
        ];
        var html = '<div class="ff-defs-row is-head" role="row"><span>지표</span><span>분자</span><span>분모</span><span>대상</span><span>기준 시각</span></div>';
        rows.forEach(function (r) {
            html += '<div class="ff-defs-row" role="row"><b>' + esc(r[0]) + '</b><span>' + esc(r[1]) + '</span><span>' + esc(r[2]) + '</span><span>' + esc(r[3]) + '</span><span>' + esc(r[4]) + '</span></div>';
        });
        $("#ffDefs").html(html);

        var $l = $("#ffLimits").empty();
        var months = monthsOf();
        var diffNote = "";
        if (state.overview && months.length) {
            var t = totals(months);
            diffNote = " (지금 값: 체결금액 " + krwShort(state.overview.domesticBuyTotal) + "원 ↔ 월별 합계 " + krwShort(t.buy) + "원)";
        }
        addNote($l, "한계 · 혜택 상태 3분류 묶는 기준: 정상 = 영향 없음, 부분 축소 = 부분 축소, 취소 = 혜택 취소 + 순매수 소멸. 분모는 매도 없음을 뺀 계좌(매도한 계좌)예요. 계좌 관리 화면의 \"일부 감액\"(외부 순매수가 있는 모든 계좌)과는 다른 값이에요", false);
        addNote($l, "한계 · 국내 매수 기준 차이: 상단 재투자율(overview)은 체결금액(수수료 제외), 월별 그래프(monthly)는 수수료를 포함한 예수금 순액이라 두 값이 달라요" + diffNote + " (백엔드 요청 #74)", false);
        addNote($l, "한계 · 잠정 지표: 예상 감면액과 감면 1원당 국내 운용액은 정의 " + ((state.relief && state.relief.definitionVersion) || "draft-1") + " 기준 예상값이에요. 세액 스냅샷은 표시용이며 확정 세액이 아니에요", true);
        addNote($l, "한계 · 이 화면에 없는 지표: 기간 선택(이번 달·분기, #72), 원금 유지율(KM), 혜택 집중도(로렌츠·지니), 연령대별 중앙값(#73), 퍼널의 금액 기준 전환(API 없음). 기간은 \"출시 이후 누적\" 한 가지예요", false);
        addNote($l, "한계 · 인과 효과가 아니에요(RIA가 없었어도 돌아왔을 돈을 구분하지 못해요). 5명 미만 칸은 가려요. PDF 저장은 감사 로그에 남기지 않아요(기록 API 없음, #75)", false);
    }

    /* ---------- PDF 로 저장 (새 창 A4 세로 인쇄) ---------- */
    function savePdf() {
        var w = window.open("", "_blank");
        if (!w) {
            if (MARIA.ui && MARIA.ui.showError) MARIA.ui.showError("팝업이 막혀서 PDF 창을 열지 못했어요. 팝업을 허용해 주세요.");
            return;
        }
        var origin = location.origin;
        var asOf = (state.overview && state.overview.asOfDate) || $("#ffAsOf").text().replace("집계 기준일", "").trim() || "-";
        var clock = $("#clockValue").text().trim() || "-";
        var doc = w.document;

        // 인쇄용 내용: 화면 패널을 복제하고, 크기를 쓰는 그림은 인쇄 폭으로 다시 그린다
        var $root = $('<div></div>');
        var $sum = $("#ffSummary").clone();
        $sum.find("[data-ff-retry]").removeAttr("data-ff-retry");
        $sum.find("[title]").removeAttr("title");
        $root.append($('<section class="adm-card ff-print-sum"><div class="adm-card-head"><span class="adm-card-title">① 핵심 지표</span></div></section>').append($sum));
        $("#ffGrid .ff-card").each(function () {
            var $c = $(this).clone();
            $c.find("[data-ff-retry]").removeAttr("data-ff-retry");
            $c.find("[title]").removeAttr("title");
            $c.find("[data-tip]").removeAttr("data-tip");
            $root.append($c);
        });
        var PW = 660;
        if (monthsOf().length) {
            buildSankey($root.find("#ffSankey"), $root.find("#ffSankeyNotes"), PW);
            var idx = Number($("#ffBridgeMonth").val());
            $root.find("#ffBridgeMonth").closest(".ff-bridge-pick").html("<span>월 " + esc(monthKey(monthsOf()[idx] || {})) + "</span>");
            buildBridge($root.find("#ffBridge"), $root.find("#ffBridgeNotes"), idx, PW);
        }
        if (state.relief) buildRelief($root.find("#ffRelief"), 440);
        $root.find("#ffSegMetric").replaceWith("<b>" + (segMetric === "reinvestRatio" ? "재투자율" : "순투자율") + "</b>");
        // Chart.js 는 라이트 색으로 따로 그려 이미지로 바꾼다
        var $canvasHost = $root.find(".ff-chart-canvas");
        if (window.Chart && monthsOf().length) {
            var off = document.createElement("canvas");
            off.width = 660;
            off.height = 250;
            var holder = document.createElement("div");
            holder.style.cssText = "position:fixed;left:-9999px;top:0;width:660px;height:250px";
            holder.appendChild(off);
            document.body.appendChild(holder);
            var oc = buildMonthlyChart(off, lightVar, false);
            var url = oc.toBase64Image("image/png", 1);
            oc.destroy();
            document.body.removeChild(holder);
            $canvasHost.empty().css("height", "auto").append($('<img class="ff-print-chart" alt="월별 확정 원화 유입, 국내 매수, 월 재투자율">').attr("src", url));
        }
        $root.find("canvas").remove();

        var cover = '<section class="ff-cover"><h1>RIA 자금 환류 보고서</h1>' +
            '<p>서비스: Returns RIA</p><p>기간: 출시 이후 누적</p><p>집계 기준일: ' + esc(asOf) + '</p><p>출력 일시(업무시각): ' + esc(clock) + '</p>' +
            '<p>집계 데이터만 담았고 개인을 식별할 수 있는 정보(고객명·계좌번호)는 넣지 않았어요 · 5명 미만 칸은 \'-\'로 가렸어요</p>' +
            '<p>혜택 감면 지표는 잠정 정의(' + esc((state.relief && state.relief.definitionVersion) || "draft-1") + ')의 예상값이에요</p></section>';

        var css = ["tokens", "components", "fund-flow"].map(function (n) {
            return '<link rel="stylesheet" href="' + origin + '/css/admin/' + n + '.css">';
        }).join("");
        var printCss = "@page{size:A4 portrait;margin:14mm}" +
            "html,body{margin:0;background:var(--adm-panel);color:var(--adm-text);-webkit-print-color-adjust:exact;print-color-adjust:exact}" +
            ".ff-print{display:block;height:auto;max-width:182mm;margin:0 auto;font-size:11px}" +
            ".ff-print .ff-cover{break-after:page;padding:40mm 0 0}" +
            ".ff-print .ff-cover h1{font-size:26px;margin:0 0 18px;color:var(--adm-navy-fg)}" +
            ".ff-print .ff-cover p{margin:0 0 6px;font-size:13px}" +
            ".ff-print .ff-card,.ff-print .ff-print-sum{break-inside:avoid;margin:0 0 8px;overflow:visible;display:block}" +
            ".ff-print .adm-summary{flex-wrap:wrap;row-gap:4px;padding:4px}" +
            ".ff-print .ff-relief{flex-wrap:wrap}" +
            ".ff-print .ff-chart svg{width:100%;height:auto}" +
            ".ff-print .ff-print-chart{width:100%;height:auto;display:block}" +
            ".ff-print .ff-defs-row{break-inside:avoid;min-width:0;font-size:10px}" + ".ff-print .ff-defs{overflow:visible}";
        doc.open();
        doc.write('<!DOCTYPE html><html lang="ko" data-theme="light"><head><meta charset="utf-8"><title>RIA 자금 환류 보고서</title>' + css + '<style>' + printCss + '</style></head><body></body></html>');
        doc.close();
        var page = doc.createElement("div");
        page.className = "adm-page fund-flow ff-print";
        doc.body.appendChild(page);
        page.insertAdjacentHTML("beforeend", cover);
        $root.children().each(function () { page.appendChild(doc.importNode(this, true)); });
        Array.prototype.forEach.call(page.querySelectorAll(".ff-card"), function (c) {
            // 아직 불러오는 중인 패널은 인쇄물에서 "불러오지 못함" 상태로 둔다
            if (c.getAttribute("data-state") === "loading") c.setAttribute("data-state", "error");
        });
        var printed = false;
        function doPrint() {
            if (printed) return;
            printed = true;
            w.focus();
            w.print();
        }
        w.addEventListener("load", function () { setTimeout(doPrint, 500); });
        setTimeout(doPrint, 1800);
        window.__ffPdfWindow = w;
    }

    /* ---------- 불러오기 ---------- */
    var PANELS_OF = {
        monthly: ["#ffPanelSankey", "#ffPanelMonthly", "#ffPanelBridge"],
        funnel: ["#ffPanelFunnel"],
        leakage: ["#ffPanelLeakage"],
        relief: ["#ffPanelRelief"],
        allocation: ["#ffPanelAllocation"],
        segments: ["#ffPanelSegments"]
    };
    var SUMMARY_OF = {
        overview: ["ffSumOpened", "ffSumInflow", "ffSumReinvest"],
        relief: ["ffSumRelief", "ffSumPerRelief"],
        leakage: ["ffSumBenefit"]
    };
    var LABEL_OF = { monthly: "월별 자금 흐름", overview: "핵심 지표", relief: "감면 지표", leakage: "혜택 누수", funnel: "퍼널", allocation: "국내 운용처", segments: "연령대 × 투자성향" };

    function renderMonthlyPanels() {
        var months = monthsOf();
        if (!months.length) {
            PANELS_OF.monthly.forEach(function (sel) { showPanelEmpty($(sel)); });
            return;
        }
        PANELS_OF.monthly.forEach(function (sel) { setPanel($(sel), "ready"); });
        setAsOf("monthly");
        fillBridgeSelect();
        renderSankey();
        renderMonthly();
        renderBridge();
    }

    function load(api) {
        var panels = PANELS_OF[api] || [];
        panels.forEach(function (sel) { setPanel($(sel), "loading"); });
        if (SUMMARY_OF[api]) summaryLoading(SUMMARY_OF[api]);

        return MARIA.auth.ajax({ url: API[api].url, method: "GET" }).done(function (res) {
            state[api] = (res && res.data) || {};
            setAsOf(api);
            if (api === "overview") {
                renderOverviewSummary();
                if (state.monthly) sankeyOverviewNote($("#ffSankeyNotes"), totals(monthsOf()));
            } else if (api === "relief") {
                renderReliefSummary();
                renderRelief();
            } else if (api === "leakage") {
                renderLeakageSummary();
                renderLeakage();
            } else if (api === "monthly") {
                renderMonthlyPanels();
            } else if (api === "funnel") {
                renderFunnel();
            } else if (api === "allocation") {
                renderAllocation();
            } else if (api === "segments") {
                renderSegments();
            }
            renderDefs();
        }).fail(function () {
            delete state[api];
            if (SUMMARY_OF[api]) summaryFail(SUMMARY_OF[api], api);
            panels.forEach(function (sel) { showPanelError($(sel), LABEL_OF[api] || "데이터", api); });
            renderDefs();
        });
    }

    $(document).on("click", "[data-ff-retry]", function () {
        load($(this).attr("data-ff-retry"));
    });

    $("#ffBridgeMonth").on("change", renderBridge);
    $("#ffSegMetric").on("click", "button", function () {
        segMetric = $(this).attr("data-metric");
        $("#ffSegMetric button").removeClass("is-on");
        $(this).addClass("is-on");
        if (state.segments) renderSegments();
    });
    $("#ffPdfBtn").on("click", savePdf);

    // 크기 · 테마가 바뀌면 SVG 와 차트를 다시 그린다
    var redrawTimer = null;
    function redraw() {
        clearTimeout(redrawTimer);
        redrawTimer = setTimeout(function () {
            if (state.monthly && monthsOf().length && $("#ffPanelMonthly").attr("data-state") === "ready") {
                renderSankey();
                renderMonthly();
                renderBridge();
            }
            if (state.relief && $("#ffPanelRelief").attr("data-state") === "ready") buildRelief($("#ffRelief"));
        }, 120);
    }
    $(window).on("resize", redraw);
    if (window.ResizeObserver) {
        new ResizeObserver(redraw).observe(document.querySelector(".ff-scroll"));
    }
    if (window.MutationObserver) {
        new MutationObserver(redraw).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
        var shell = document.getElementById("admShell");
        if (shell) new MutationObserver(redraw).observe(shell, { attributes: true, attributeFilter: ["style"] });
    }

    renderDefs();
    MARIA.auth.requireAuth().done(function () {
        // 7개를 동시에 부른다 (한쪽이 느리거나 실패해도 나머지는 먼저 그려진다)
        Object.keys(API).forEach(function (api) { load(api); });
    });
});
