/* 자금 환류 대시보드 (1405) — A-14a · A-14b
   - 7개 API(overview · funnel · monthly · leakage · segments · relief · allocation)를 동시에 부르고, 패널마다 따로 로딩 · 오류(다시 시도) · 빈 상태를 둔다.
   - 모든 숫자는 API 응답에서만 온다. 화면에서 하는 계산은 응답 값끼리의 합계 · 비율 · 차이뿐이고, 계산식은 패널 아래 근거 문구에 적는다.
   - ① 요약 6칸 ② Sankey(SVG) ③ 월별 콤보(Chart.js) ④ 퍼널(CSS) ⑤ 혜택 누수(CSS · 조정비율 히스토그램) ⑥ 감면 워터폴(SVG) ⑦ 혜택 집중도(SVG · 로렌츠)
     ⑧ 원금 유지(SVG · Kaplan-Meier) ⑨ 국내 운용처(CSS) ⑩ 예수금 브리지(SVG) ⑪ 연령대 × 투자성향 히트맵(CSS grid · 연령대별 중앙값) ⑫ 지표 정의 · 데이터 품질(표)
   + PDF 로 저장(새 창 A4 세로 인쇄). 기간(이번 달 · 분기 · 출시 이후)은 period 를 받는 API 에만 적용하고 세액 스냅샷 패널은 기간과 무관하다. */
$(function () {
    var KRW = new Intl.NumberFormat("ko-KR");
    var state = {};          // API 이름 → 응답 data
    var chart = null;        // ③ Chart.js 인스턴스
    var segMetric = "reinvestRatio";
    var period = "ALL";      // 조회 기간: MONTH(이번 달) · QUARTER(분기) · ALL(출시 이후 누적)
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
        overview: { url: "/api/admin/fund-flow/overview", period: true },
        funnel: { url: "/api/admin/fund-flow/funnel", period: true },
        monthly: { url: "/api/admin/fund-flow/monthly", period: true },
        leakage: { url: "/api/admin/fund-flow/leakage" },       // 세액 스냅샷: 기간과 무관
        segments: { url: "/api/admin/fund-flow/segments", period: true },
        relief: { url: "/api/admin/fund-flow/relief" },         // 세액 스냅샷: 기간과 무관
        allocation: { url: "/api/admin/fund-flow/allocation", period: true }, // 구성은 현재 보유(무관), 순매수 Top 10 만 기간 적용
        retention: { url: "/api/admin/fund-flow/retention", period: true },     // 기간 = 그 기간에 환전이 확정된 건의 코호트
        concentration: { url: "/api/admin/fund-flow/concentration" }           // 세액 스냅샷: 기간과 무관
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

    // 서버와 같은 규칙: 기준일(asOfDate)이 속한 달 · 분기의 첫날부터 기준일까지. ALL 은 하한 없음.
    function periodStart(asOf) {
        if (period === "ALL" || !asOf) return null;
        var y = Number(String(asOf).slice(0, 4)), m = Number(String(asOf).slice(5, 7));
        var startMonth = period === "MONTH" ? m : Math.floor((m - 1) / 3) * 3 + 1;
        return y + "-" + (startMonth < 10 ? "0" : "") + startMonth + "-01";
    }

    function periodName() {
        return period === "MONTH" ? "이번 달" : period === "QUARTER" ? "분기" : "출시 이후 누적";
    }

    // "이번 달 (2026-11-01 ~ 2026-11-17)" 형태. 기준일을 아직 모르면 이름만.
    function periodLabel() {
        var asOf = (state.overview && state.overview.asOfDate) || (state.monthly && state.monthly.asOfDate);
        var from = periodStart(asOf);
        return from ? periodName() + " (" + from + " ~ " + asOf + ")" : periodName();
    }

    function renderPeriodRange() {
        $("#ffPeriodRange").text(periodLabel());
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

    function addNote($ul, text, warn) { } // 패널 하단 보조 설명 비활성화

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
        renderPeriodRange();
    }

    function renderReliefSummary() {
        var d = state.relief;
        summarySet("ffSumRelief", krwShort(d.reliefTotal), "일반 과세 세액 − 최종 세액의 합계 (예상값) · 기간과 무관한 기준일 스냅샷");
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
        addNote($notes, "기간: " + periodLabel() + " · 월별 합계(" + range + ")" +
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
        return true;
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
                    { type: "bar", label: "국내 매수(수수료 포함)", data: buy, backgroundColor: colors(cBuy, pBuy), borderColor: cBuy, borderWidth: borderW, yAxisID: "y", order: 2, maxBarThickness: 28 },
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
                                return ctx.dataset.label + " " + krwShort(ctx.parsed.y) + "원" + (ctx.dataset.label === "국내 매수(수수료 포함)" && num(m.krwInflowAmount) > 0 ? " (유입 대비 " + pct1(num(m.domesticBuyAmount) / num(m.krwInflowAmount)) + ")" : "");
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

    /* ---------- ⑩ 예수금 브리지 (워터폴) ---------- */
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

    /* ---------- ④ 퍼널 (CSS 가로 막대) ---------- */
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
        if (period !== "ALL") addNote($notes, "기간 적용: " + periodLabel() + "에 가입한 고객만 따라가는 코호트 기준이에요(이 고객들이 지금까지 각 단계에 도달한 비율)", false);
        if (d.basis) addNote($notes, d.basis, false);
    }

    /* ---------- ⑤ 혜택 누수 (100% 누적 막대) ---------- */
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

        var bins = d.ratioBins || [];
        var soldTotal = bins.reduce(function (sum, b) { return sum + num(b.accounts); }, 0);
        var maxShare = Math.max.apply(null, bins.map(function (b) { return num(b.share); }).concat([0.0001]));
        $("#ffRatioHist").html(bins.map(function (b) {
            var share = num(b.share);
            var tip = "조정비율 " + b.label + "\n" + KRW.format(num(b.accounts)) + "계좌 (매도한 계좌의 " + pct1(share) + ")";
            return '<div class="ff-hist-col" data-tip="' + esc(tip) + '"><span class="ff-hist-val">' + pct1(share) + '</span>' +
                '<div class="ff-hist-bar" style="height:' + Math.round(share / maxShare * 78) + 'px"></div>' +
                '<span class="ff-hist-lab">' + esc(b.label) + '</span></div>';
        }).join("") || '<div class="ff-soon-text">매도한 계좌가 없어요</div>');

        var $notes = $("#ffLeakNotes").empty();
        if (bins.length) {
            addNote($notes, "조정비율 = 1 − (타 계좌 순매수 가중합 ÷ RIA 내 매도 가중합), 0~1로 제한. 매도한 " + KRW.format(soldTotal) + "계좌 기준이고, 혜택 취소 계좌도 조정비율 값대로 들어가요", false);
        }
        var extSold = cats.filter(function (c) { return MARIA.status.leakageGroup(c.code) !== "excluded"; })
            .reduce(function (s, c) { return s + num(c.withExternalBuy); }, 0);
        addNote($notes, "다른 계좌 순매수가 있는 계좌 비율(매도한 계좌 기준) = " + KRW.format(extSold) + " ÷ " + KRW.format(g.base) + " = " + pct1(extSold / g.base), false);
        if (d.snapshotCalculatedAt) addNote($notes, "세액 스냅샷 계산 시각 " + String(d.snapshotCalculatedAt).replace("T", " ").slice(0, 16) + " · 표시용 예상값이며 확정 세액이 아니에요", false);
    }

    /* ---------- ⑥ 감면 성과 (워터폴, 잠정) ---------- */
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

    /* ---------- ⑨ 국내 운용처 ---------- */
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
        if (period !== "ALL") addNote($notes, "기간 적용: 순매수 상위 10종목만 " + periodLabel() + " 체결분이에요. 구성 막대와 HHI 는 현재 보유 기준이라 기간과 무관해요", false);
        addNote($notes, "막대·범례는 응답 buckets 를 업종 상위 5 + ETF·펀드(따로) + 기타(나머지 업종 " + rest.length + "개)로 묶은 것이고, 비중 = 금액 ÷ buckets 합계(" + krwShort(bucketSum) + "원)예요", false);
        addNote($notes, "HHI = 점유율² 합, 0(분산) ~ 1(쏠림). 응답 값 그대로이며 묶기 전 전체 buckets(업종 " + sectors.length + "개 + ETF·펀드) 기준이에요. 순매수 = 매수 체결금액 − 매도 체결금액", false);
        if (d.basis) addNote($notes, d.basis, false);
    }

    /* ---------- ⑪ 연령대 × 투자성향 히트맵 ---------- */
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
        var meds = d.ageMedians || [];
        var medHtml = '<div class="ff-leak-row is-head"><span>연령대</span><span class="is-r">유입 있는 계좌</span><span class="is-r">확정 유입 중앙값</span><span class="is-r">재투자율 중앙값</span><span class="is-r">재투자율(합계 비율)</span></div>';
        meds.forEach(function (m) {
            var sumCell = map[(m.ageBand || "") + "|"];
            var hidden = m.inflowMedian == null;
            medHtml += '<div class="ff-leak-row"><span>' + esc(m.ageBand || "전체") + '</span>' +
                '<span class="is-r">' + KRW.format(num(m.accountsWithInflow)) + ' / ' + KRW.format(num(m.accounts)) + '</span>' +
                '<span class="is-r">' + (hidden ? "-" : krwShort(m.inflowMedian) + "원") + '</span>' +
                '<span class="is-r">' + (hidden ? "-" : pct1(Number(m.reinvestMedian))) + '</span>' +
                '<span class="is-r">' + (sumCell && sumCell.reinvestRatio != null ? pct1(Number(sumCell.reinvestRatio)) : "-") + '</span></div>';
        });
        $("#ffMedian").html(meds.length ? medHtml : '<div class="ff-soon-text">계좌별 값이 없어요</div>');

        var $notes = $("#ffSegNotes").empty();
        if (meds.length) {
            addNote($notes, "중앙값 = 확정 유입이 있는 계좌별 값(재투자율은 국내 매수 ÷ 확정 유입)을 줄 세웠을 때 가운데 값이에요. 큰 계좌 한두 개에 끌려가지 않아서, 합계 비율과 많이 다르면 일부 큰 계좌가 전체 비율을 끌고 있다는 신호예요. 유입이 있는 계좌가 5개 미만이면 가려요", false);
        }
        addNote($notes, "칸 값 = 칸별 합계 비율(" + (segMetric === "reinvestRatio" ? "Σ국내 매수 체결금액 ÷ Σ확정 유입" : "(Σ매수 − Σ매도) ÷ Σ확정 유입") + ") — 계좌별 비율의 평균이나 중앙값이 아니에요. 색은 이 표 안 최솟값 " + pct1(min) + " ~ 최댓값 " + pct1(max), false);
        addNote($notes, "5명 미만인 칸은 개인 식별 방지를 위해 비율을 가려요(비공개 " + nullCount + "칸 / 표시 칸 " + bodyCount + "개)", false);
        if (period !== "ALL") addNote($notes, "기간 적용: 유입 · 매수 · 매도는 " + periodLabel() + " 합계이고, 칸의 계좌 수(5명 미만 가림 기준)는 누적 개설 계좌예요", false);
        if (d.basis) addNote($notes, d.basis, false);
    }

    /* ---------- ⑧ 원금 유지 (Kaplan-Meier + 95% 구간) ---------- */
    function renderRetention() {
        var d = state.retention || {};
        var $card = $("#ffPanelRetention");
        var pts = d.points || [];
        if (!num(d.lots) || !pts.length) { showPanelEmpty($card); return; }
        setPanel($card, "ready");

        var W = 600, H = 214, L = 46, R = 12, T = 14, B = 34;
        var XMAX = 365, obs = num(d.maxObservedDays);
        var minLow = Math.min.apply(null, pts.map(function (p) { return num(p.lower); }));
        var yMin = Math.floor(minLow * 20) / 20;
        if (yMin > 0.95) yMin = 0.95;
        function X(day) { return L + day / XMAX * (W - L - R); }
        function Y(v) { return T + (1 - (v - yMin) / (1 - yMin)) * (H - T - B); }
        function pctTick(v) { return (Math.round(v * 1000) / 10) + "%"; }

        var svg = svgEl("svg", { viewBox: "0 0 " + W + " " + H, role: "img", "aria-label": "확정 후 경과일별 원금 유지 확률과 95% 구간" });
        if (obs < XMAX) {
            var un = svgEl("rect", { x: X(obs), y: T, width: X(XMAX) - X(obs), height: H - T - B, "class": "ff-unobs" }, svg);
            un.setAttribute("data-tip", "아직 관찰되지 않은 구간\n확정 후 " + obs + "일을 넘긴 건이 없어서\n1년(365일) 유지율은 계산할 수 없어요");
            svgText(svg, (X(obs) + X(XMAX)) / 2, T + 16, "middle", "ff-t-sub", 11, "관찰 전", "");
        }
        for (var i = 0; i <= 4; i++) {
            var v = yMin + (1 - yMin) * i / 4;
            svgEl("line", { x1: L, x2: W - R, y1: Y(v), y2: Y(v), "class": "ff-grid-line" }, svg);
            svgText(svg, L - 6, Y(v) + 4, "end", "ff-t-sub", 11, pctTick(v), "");
        }
        [0, 90, 180, 270, 365].forEach(function (day) {
            svgText(svg, X(day), H - B + 15, "middle", "ff-t-sub", 11, day + "일", "");
        });
        svgText(svg, (L + W - R) / 2, H - 3, "middle", "ff-t-sub", 11, "확정 후 경과일", "");
        svgEl("line", { x1: L, x2: W - R, y1: H - B, y2: H - B, "class": "ff-axis" }, svg);

        var upper = pts.map(function (p) { return X(p.day).toFixed(1) + "," + Y(num(p.upper)).toFixed(1); });
        var lower = pts.map(function (p) { return X(p.day).toFixed(1) + "," + Y(num(p.lower)).toFixed(1); }).reverse();
        svgEl("polygon", { points: upper.concat(lower).join(" "), "class": "ff-ci" }, svg);
        svgEl("polyline", { points: pts.map(function (p) { return X(p.day).toFixed(1) + "," + Y(num(p.survival)).toFixed(1); }).join(" "), "class": "ff-km" }, svg);

        var stepW = pts.length > 1 ? X(pts[1].day) - X(pts[0].day) : 12;
        pts.forEach(function (p) {
            var tip = "확정 후 " + p.day + "일\n원금 유지 " + pct1(Number(p.survival)) +
                "\n95% 구간 " + pct1(Number(p.lower)) + " ~ " + pct1(Number(p.upper)) +
                "\n금액 가중 " + pct1(Number(p.weightedSurvival)) +
                "\n위험집합 " + KRW.format(num(p.atRisk)) + "건";
            var hit = svgEl("rect", { x: X(p.day) - stepW / 2, y: T, width: stepW, height: H - T - B, "class": "ff-hit" }, svg);
            hit.setAttribute("data-tip", tip);
        });
        $("#ffRetention").empty().get(0).appendChild(svg);

        var rowsPts = [];
        [30, 90, 180].forEach(function (day) {
            var hit = pts.filter(function (q) { return q.day === day; })[0];
            if (hit) rowsPts.push(hit);
        });
        var lastPt = pts[pts.length - 1];
        if (rowsPts.indexOf(lastPt) < 0) rowsPts.push(lastPt);
        var html = '<div class="ff-leak-row is-head"><span>경과일</span><span class="is-r">유지율(건수)</span><span class="is-r">95% 구간</span><span class="is-r">유지율(금액 가중)</span><span class="is-r">위험집합</span></div>';
        rowsPts.forEach(function (p) {
            html += '<div class="ff-leak-row"><span>확정 후 ' + p.day + '일</span>' +
                '<span class="is-r">' + pct1(Number(p.survival)) + '</span>' +
                '<span class="is-r">' + pct1(Number(p.lower)) + ' ~ ' + pct1(Number(p.upper)) + '</span>' +
                '<span class="is-r">' + pct1(Number(p.weightedSurvival)) + '</span>' +
                '<span class="is-r">' + KRW.format(num(p.atRisk)) + '건</span></div>';
        });
        $("#ffRetTable").html(html);

        var $notes = $("#ffRetNotes").empty();
        addNote($notes, "확정된 환전 " + KRW.format(num(d.lots)) + "건(" + KRW.format(num(d.accounts)) + "계좌) 중 1년 미경과 원금 인출 " + KRW.format(num(d.events)) +
            "건. 시작 = 환전 확정, 사건 = 그 건의 1년 미경과 원금 인출, 사건이 없으면 기준일에서 중도절단", false);
        addNote($notes, "가장 오래 관찰된 건이 확정 후 " + obs + "일이라 1년(365일) 유지율은 계산할 수 없어요. 회색은 아직 관찰되지 않은 구간이에요", false);
        addNote($notes, "세로축은 " + pctTick(yMin) + "부터 시작해요(변화를 보이게 하려고 아래쪽을 잘랐어요). 띠는 95% 구간: 같은 계좌의 건은 사건 시점을 공유해 계좌 단위로 " + num(d.bootstrapReplicates) + "회 다시 뽑아 구했어요", false);
        var tail = num(lastPt.atRisk) / num(d.lots);
        if (tail < 0.1) {
            addNote($notes, "오른쪽 끝(" + lastPt.day + "일)은 위험집합이 " + KRW.format(num(lastPt.atRisk)) + "건(전체의 " + pct1(tail) + ")뿐이라 구간이 넓고 해석을 조심해야 해요", true);
        }
        addNote($notes, "합성 데이터 주의: 1년 미경과 원금 인출이 해지 강제 인출뿐이라 곡선이 평평한 것은 고객 행동이 아니라 데이터 생성 규칙의 결과일 수 있어요", false);
    }

    /* ---------- ⑦ 혜택 집중도 (로렌츠 곡선 · 지니계수, 잠정) ---------- */
    function renderConcentration() {
        var d = state.concentration || {};
        var $card = $("#ffPanelConc");
        var pts = d.lorenz || [];
        if (!num(d.accounts) || !pts.length) { showPanelEmpty($card); return; }
        setPanel($card, "ready");

        var S = 230, L = 38, R = 10, T = 10, B = 34;
        var w = S - L - R, h = S - T - B;
        function X(v) { return L + v * w; }
        function Y(v) { return T + (1 - v) * h; }
        var svg = svgEl("svg", { viewBox: "0 0 " + S + " " + S, role: "img", "aria-label": "감면액 로렌츠 곡선" });
        [0, 0.25, 0.5, 0.75, 1].forEach(function (v) {
            svgEl("line", { x1: L, x2: S - R, y1: Y(v), y2: Y(v), "class": "ff-grid-line" }, svg);
            svgText(svg, L - 5, Y(v) + 4, "end", "ff-t-sub", 10.5, Math.round(v * 100) + "%", "");
            svgText(svg, X(v), S - B + 14, "middle", "ff-t-sub", 10.5, Math.round(v * 100) + "%", "");
        });
        svgText(svg, (L + S - R) / 2, S - 3, "middle", "ff-t-sub", 10.5, "계좌 누적(감면액 적은 순)", "");
        svgEl("line", { x1: X(0), y1: Y(0), x2: X(1), y2: Y(1), "class": "ff-diag" }, svg);
        var curve = pts.map(function (p) { return X(num(p.populationShare)).toFixed(1) + "," + Y(num(p.valueShare)).toFixed(1); });
        var back = pts.slice().reverse().map(function (p) { return X(num(p.populationShare)).toFixed(1) + "," + Y(num(p.populationShare)).toFixed(1); });
        svgEl("polygon", { points: curve.concat(back).join(" "), "class": "ff-ci" }, svg);
        svgEl("polyline", { points: curve.join(" "), "class": "ff-km" }, svg);
        pts.forEach(function (p) {
            if (num(p.populationShare) <= 0) return;
            var c = svgEl("circle", { cx: X(num(p.populationShare)), cy: Y(num(p.valueShare)), r: 4, "class": "ff-hit" }, svg);
            c.setAttribute("data-tip", "감면액이 적은 쪽 " + pct1(num(p.populationShare)) + "의 계좌가\n전체 감면액의 " + pct1(num(p.valueShare)) + "를 가져가요");
        });
        $("#ffLorenz").empty().get(0).appendChild(svg);

        var gini = d.gini == null ? "-" : Number(d.gini).toFixed(2);
        $("#ffConcBig").html(
            '<div class="ff-big-label">지니계수</div>' +
            '<div class="ff-big-value">' + esc(gini) + '</div>' +
            '<div class="ff-big-sub">0 균등 ~ 1 한 계좌 독점</div>' +
            '<div class="ff-big-line"><span>상위 10% 계좌 점유율</span><b>' + pct1(d.top10Share == null ? null : Number(d.top10Share)) + '</b></div>');
        $("#ffConcKv").html(
            '<div>감면을 받는 계좌<b>' + KRW.format(num(d.accounts)) + '개</b></div>' +
            '<div>감면액 합계<b>' + krwShort(d.reliefTotal) + '원</b></div>' +
            '<div>계좌당 평균 감면액<b>' + krwShort(num(d.reliefTotal) / num(d.accounts)) + '원</b></div>');
        var $notes = $("#ffConcNotes").empty();
        addNote($notes, "감면액이 0보다 큰 계좌만 대상이에요. 곡선이 대각선(완전 균등)에서 멀수록, 지니계수가 클수록 소수 계좌에 감면이 몰려 있다는 뜻이에요", false);
        addNote($notes, "감면액은 양도소득 규모에 비례해 커지는 구조라, 집중이 곧 불공평을 뜻하지는 않아요(규모 효과). 자산 규모 구간별 형평성 분석은 구간 정의가 정해지면 추가해요", false);
        addNote($notes, "잠정 지표예요: 감면액 정의(draft-1)와 양도소득 범위가 확정되지 않았어요. 세액 스냅샷 기준 예상값이라 기간 선택과 무관해요", true);
    }

    /* ---------- ⑫ 지표 정의 · 데이터 품질 ---------- */
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
            ["연령대 × 투자성향", "칸별 Σ 국내 매수 체결금액 · 순투자액", "칸별 Σ 확정 원화 유입", "5명 미만 칸은 비공개", asOfOf("segments")],
            ["연령대별 중앙값", "계좌별 확정 유입 · 재투자율(매수 ÷ 유입)의 가운데 값", "-", "확정 유입이 있는 계좌(5개 미만 비공개)", asOfOf("segments")],
            ["조정비율 분포", "조정비율 구간별 계좌 수", "매도한 계좌 수", "세액 스냅샷 계좌", snap || asOfOf("leakage")],
            ["원금 유지 [Kaplan-Meier]", "확정 후 경과일까지 1년 미경과 원금이 인출되지 않은 건 비율", "그 경과일 이상 관찰된 건(위험집합)", "확정된 환전 건 · 95% 구간은 계좌 단위 부트스트랩", asOfOf("retention")],
            ["혜택 집중도 [잠정]", "감면액 상위 10% 계좌 점유율 · 지니계수 · 로렌츠 곡선", "감면액 합계", "감면액 > 0 계좌 · 세액 스냅샷", asOfOf("concentration")]
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
        addNote($l, "한계 · 이 화면에 없는 지표: 퍼널의 금액 기준 전환(API 없음), 자산 규모 구간별 형평성(구간 정의 미확정). 혜택 누수 · 감면 성과 · 혜택 집중도는 세액 스냅샷이라 기간 선택을 따르지 않아요(기준일 값)", false);
        addNote($l, "한계 · 원금 유지: 가장 오래 관찰된 건이 1년에 못 미쳐 1년 유지율은 정의할 수 없고, 관찰된 기간까지만 보여요. 오른쪽 끝은 위험집합이 작아 구간이 넓어요", false);
        addNote($l, "한계 · 인과 효과가 아니에요(RIA가 없었어도 돌아왔을 돈을 구분하지 못해요). 5명 미만 칸은 가려요. PDF 저장은 감사 로그에 남기지 않아요(기록 API 없음, #75)", false);
    }

    /* ---------- 해석 층: 핵심 한 줄(응답 값으로 만든 문장) + 접어둔 상세 해석 ----------
       문장은 모두 state 의 값에서 만든다. 원인을 단정하는 표현(~때문에, ~덕분, ~효과)은 쓰지 않고,
       "무엇을 보나 / 어떻게 읽나 / 말할 수 없는 것"을 나눠 적는다. PDF 에서는 상세 해석을 펼쳐서 낸다. */
    function setInterp(sel, insight, detail) {
        var $card = $(sel);
        var $body = $card.children(".ff-body");
        if (!$body.length) return;
        var $box = $body.children(".ff-interp");
        if (!$box.length) {
            $box = $('<div class="ff-interp"></div>');
            var $notes = $body.children(".ff-notes").first();
            if ($notes.length) $box.insertBefore($notes); else $box.appendTo($body);
        }
        var html = "";
        if (insight) html += '<p class="ff-insight"><b>핵심</b>' + esc(insight) + '</p>';
        if (detail) {
            html += '<details class="ff-detail"><summary>해석 보기</summary><dl>' +
                '<dt>무엇을 보나</dt><dd>' + esc(detail.see) + '</dd>' +
                '<dt>어떻게 읽나</dt><dd>' + esc(detail.read) + '</dd>' +
                '<dt>말할 수 없는 것</dt><dd>' + esc(detail.limit) + '</dd></dl></details>';
        }
        $box.html(html);
    }

    function hhiWord(v) {
        if (v == null) return "-";
        return v < 0.15 ? "분산" : v > 0.25 ? "집중" : "보통";
    }

    var INTERP = {
        sankey: function () {
            var months = monthsOf();
            if (!months.length) return;
            var t = totals(months);
            var base = t.sell || t.krw || 1;
            var rest = t.krw + t.dsell - t.buy - t.wd;
            setInterp("#ffPanelSankey",
                "해외 매도 " + krwShort(t.sell) + "원 중 확정 원화는 " + krwShort(t.krw) + "원(" + pct1(t.krw / base) + ")이고, 여기에 국내 매도 회수 " + krwShort(t.dsell) +
                "원을 더해 국내 매수 " + krwShort(t.buy) + "원 · 인출 " + krwShort(t.wd) + "원을 쓰고 " + krwShort(Math.max(0, rest)) + "원이 예수금에 남았어요",
                {
                    see: "해외 주식을 판 돈이 원화로 확정된 뒤 국내 매수, 예수금 잔류, 인출로 나뉘는 규모를 월별 합계로 본 그림이에요.",
                    read: "노드 높이가 금액이에요. 해외 매도와 확정 원화의 '차이'는 환율 · 수수료 · 미확정 가환전분이에요. 국내 매수가 크고 예수금 잔류가 작을수록 돈이 국내 투자로 이어진 비중이 커요.",
                    limit: "연결선의 출처 배분은 표시용 배치라서 '이 돈이 저기로 갔다'는 개별 추적이 아니에요. 합계 흐름이므로 계좌마다 어떻게 움직였는지는 알 수 없어요."
                });
        },
        monthly: function () {
            var months = monthsOf().filter(function (m) { return !m.partial; });
            if (!months.length) return;
            function group(m) {
                var mm = Number(monthKey(m).slice(5, 7));
                return mm <= 5 ? "1~5월" : mm <= 7 ? "6~7월" : "8월 이후";
            }
            var sums = {}, counts = {};
            months.forEach(function (m) {
                var g = group(m);
                sums[g] = (sums[g] || 0) + num(m.krwInflowAmount);
                counts[g] = (counts[g] || 0) + 1;
            });
            var parts = ["1~5월", "6~7월", "8월 이후"].filter(function (g) { return counts[g]; }).map(function (g) {
                return g + " " + krwShort(sums[g] / counts[g]) + "원";
            });
            var rates = months.filter(function (m) { return num(m.krwInflowAmount) > 0; }).map(function (m) {
                return { key: monthKey(m), v: num(m.domesticBuyAmount) / num(m.krwInflowAmount) };
            });
            var tail = "";
            if (rates.length) {
                var lo = rates.reduce(function (a, b) { return b.v < a.v ? b : a; });
                var hi = rates.reduce(function (a, b) { return b.v > a.v ? b : a; });
                tail = " · 월 재투자율은 " + lo.key + " " + pct1(lo.v) + " ~ " + hi.key + " " + pct1(hi.v) + " 범위예요";
            }
            setInterp("#ffPanelMonthly", "월평균 확정 원화 유입: " + parts.join(" · ") + tail,
                {
                    see: "달마다 확정된 원화 유입과 국내 매수, 그리고 유입 대비 매수 비율(월 재투자율)이에요. 점선은 공제율이 바뀌는 5/31, 7/31이에요.",
                    read: "구간 경계 앞뒤로 월평균 유입이 크게 다르면 감면율이 내려가기 전후로 매도 시점이 달랐다는 신호예요. 월 재투자율이 100%를 넘는 달은 이전 예수금이나 국내 매도 회수금이 함께 쓰인 거예요.",
                    limit: "경계 앞에서 유입이 많다고 해서 감면율 때문이라고 단정할 수 없어요(상관만 보여요). 진행 중인 달은 부분 집계이고, 월별 국내 매수는 수수료를 포함한 순액이라 상단 재투자율과 값이 달라요."
                });
        },
        bridge: function () {
            var m = monthsOf()[Number($("#ffBridgeMonth").val())];
            if (!m) return;
            var parts = ["확정 원화 +" + krwShort(num(m.krwInflowAmount)), "국내 매수 −" + krwShort(num(m.domesticBuyAmount)),
                "국내 매도 +" + krwShort(num(m.domesticSellAmount)), "인출 −" + krwShort(num(m.withdrawalAmount))];
            var net = num(m.krwInflowAmount) - num(m.domesticBuyAmount) + num(m.domesticSellAmount) - num(m.withdrawalAmount);
            setInterp("#ffPanelBridge", monthKey(m) + (m.partial ? "(진행 중)" : "") + ": " + parts.join(", ") + " → 예수금 순증감 " + (net >= 0 ? "+" : "−") + krwShort(Math.abs(net)) + "원",
                {
                    see: "고른 달에 예수금이 어디서 들어와 어디로 나갔는지 월초 잔고에서 월말 잔고까지 나눠 본 그림이에요.",
                    read: "초록 막대가 늘리고 주황 막대가 줄이는 항목이에요. 회색 '차이' 막대가 없으면 계산한 월말과 실제 월말 예수금이 맞는다는 뜻이라 데이터가 앞뒤로 맞물려요.",
                    limit: "달 전체의 합계라 하루 중 어느 시점에 움직였는지는 알 수 없어요. 차이 막대가 생기면 원천 값을 확인해야 하고, 그 원인은 이 화면에서 알 수 없어요."
                });
        },
        funnel: function () {
            var st = (state.funnel && state.funnel.stages) || [];
            if (st.length < 2) return;
            var worst = 1;
            for (var i = 2; i < st.length; i++) {
                if (num(st[i].convPrev) < num(st[worst].convPrev)) worst = i;
            }
            var last = st[st.length - 1];
            var drop = Math.max(0, num(st[worst - 1].customers) - num(st[worst].customers));
            setInterp("#ffPanelFunnel",
                (period === "ALL" ? "" : periodLabel() + "에 가입한 고객 기준 · ") +
                "가장 크게 빠지는 단계는 '" + st[worst - 1].stage + " → " + st[worst].stage + "'(" + pct1(num(st[worst].convPrev)) + " 통과, " + KRW.format(drop) +
                "명 이탈)이고, 가입한 고객의 " + pct1(num(last.convTotal)) + "가 '" + last.stage + "'까지 도달했어요",
                {
                    see: "웹에 가입한 고객이 마이데이터 동의, 계좌 신청, 개설, 첫 매도, 첫 국내 매수 순으로 몇 명이나 다음 단계에 도달했는지예요. 앞 단계를 모두 지난 고객만 세요.",
                    read: "직전 단계 대비 전환율이 낮은 단계가 이탈이 큰 지점이에요. 회색 막대는 직전 단계에서 빠진 고객이고, 오른쪽 %는 가입 고객 대비 누적 도달률이에요. 기간을 고르면 그 기간에 가입한 고객만 따라가요.",
                    limit: "고객 수 기준이라 금액 규모는 알 수 없고, 이탈한 이유도 이 화면에서는 알 수 없어요. 합성 데이터라 단계 간 전환율에는 데이터 생성 규칙이 반영돼 있어요."
                });
        },
        leakage: function () {
            var d = state.leakage;
            if (!d) return;
            var g = leakageGroups(d);
            if (!g.base) return;
            var offset = (d.categories || []).filter(function (c) { return c.code === "EXTERNAL_BUY_OFFSET"; })[0];
            setInterp("#ffPanelLeakage",
                "매도한 " + KRW.format(g.base) + "계좌 중 영향 없음 " + pct1(g.normal / g.base) + ", 부분 축소 " + pct1(g.reduced / g.base) + ", 취소 · 소멸 " + pct1(g.canceled / g.base) +
                "예요. 타 계좌 순매수로 혜택이 사라진 계좌는 " + KRW.format(num(offset && offset.accounts)) + "개(전체의 " + pct1(num(offset && offset.accounts) / num(d.total)) + ")예요",
                {
                    see: "RIA 밖 계좌에서 해외주식을 다시 사면 그만큼 혜택이 줄어요. 모든 계좌를 '매도 없음 → 혜택 취소 → 영향 없음 → 부분 축소 → 순매수 소멸' 중 먼저 걸리는 하나로만 분류했어요.",
                    read: "조정비율이 1이면 영향 없음, 0과 1 사이면 부분 축소, 0이면 소멸이에요. 조정비율 0 안에 매도 없음 · 해지 · 순매수 소멸이 섞여 있어서 원인별로 따로 셌어요. 아래 히스토그램은 매도한 계좌의 조정비율 분포예요.",
                    limit: "세액 스냅샷의 예상값이고 확정 세액이 아니에요. 계좌 수 비율이라 금액 규모는 반영되지 않아서, 소멸 계좌가 적어도 금액이 작다고 말할 수는 없어요."
                });
        },
        relief: function () {
            var d = state.relief;
            if (!d || d.generalTaxTotal == null) return;
            var ratio = num(d.generalTaxTotal) > 0 ? num(d.reliefTotal) / num(d.generalTaxTotal) : null;
            setInterp("#ffPanelRelief",
                "예상 감면액 " + krwShort(d.reliefTotal) + "원(일반 과세 세액의 " + pct1(ratio) + ")이고, 감면을 받는 계좌는 " + KRW.format(num(d.accountsWithRelief)) + " / " + KRW.format(num(d.accounts)) +
                "개(" + pct1(num(d.accounts) ? num(d.accountsWithRelief) / num(d.accounts) : null) + ")예요" +
                (d.investPerRelief == null ? "" : ". 감면 1원당 국내 순투자는 " + Number(d.investPerRelief).toFixed(1) + "원이에요"),
                {
                    see: "RIA가 없었다면 냈을 일반 과세 세액과 RIA를 적용한 최종 세액의 차이(감면액), 그리고 감면 1원당 국내에 순투자된 금액이에요.",
                    read: "감면 비율이 높을수록 정부가 포기한 세수가 커요. 감면 1원당 순투자는 클수록 같은 감면으로 더 많은 돈이 국내에 남았다는 뜻이고, 분모를 전체 계좌로 볼지 감면 받는 계좌만으로 볼지에 따라 값이 크게 달라져요.",
                    limit: "감면 1원당 투자액은 상관을 나눈 값이라 '감면 1원이 이만큼을 만들었다'는 정책 효과가 아니에요. 감면이 없었어도 돌아왔을 돈이 섞여 있어요. 양도소득 범위 정의가 확정되지 않은 잠정 값이에요."
                });
        },
        allocation: function () {
            var d = state.allocation;
            if (!d || !(d.buckets || []).length) return;
            var top = d.buckets.slice().sort(function (a, b) { return num(b.value) - num(a.value); })[0];
            var tn = (d.topNetBuy || [])[0];
            setInterp("#ffPanelAllocation",
                "보유 중인 국내 자산 중 가장 큰 항목은 '" + top.label + "'(" + pct1(num(top.share)) + ")이에요. 구성 쏠림(HHI) " + Number(d.hhiBucket).toFixed(3) + "(" + hhiWord(Number(d.hhiBucket)) +
                "), 종목 쏠림 " + Number(d.hhiProduct).toFixed(3) + "(" + hhiWord(Number(d.hhiProduct)) + ")" + (tn ? ". " + periodName() + " 순매수 1위 종목: " + tn.name : ""),
                {
                    see: "RIA 계좌들이 국내에 투자해 들고 있는 자산을 업종 · ETF·펀드로 나눈 구성과, 점유율 제곱합(HHI)으로 본 쏠림, 순매수 상위 종목이에요.",
                    read: "HHI는 0에 가까울수록 분산, 1에 가까울수록 한 곳에 쏠려요(통상 0.15 미만 분산, 0.25 초과 집중으로 봐요). 업종 구성 쏠림과 종목 쏠림을 따로 봐야 해요. 순매수는 기간을 고르면 그 기간 체결분만 보여요.",
                    limit: "현재가가 아니라 매입금액 기준이라 오른 종목의 비중은 작게 보일 수 있어요. 구성과 HHI는 현재 보유 기준이라 기간 선택을 따르지 않아요. 왜 그 종목을 샀는지는 알 수 없어요."
                });
        },
        segments: function () {
            var cells = (state.segments && state.segments.cells) || [];
            var key = segMetric;
            var body = cells.filter(function (c) { return c.ageBand && c.investorType && c[key] != null; });
            if (!body.length) return;
            var hi = body.reduce(function (a, b) { return Number(b[key]) > Number(a[key]) ? b : a; });
            var lo = body.reduce(function (a, b) { return Number(b[key]) < Number(a[key]) ? b : a; });
            var all = cells.filter(function (c) { return !c.ageBand && !c.investorType; })[0];
            var all2 = (state.segments.ageMedians || []).filter(function (m) { return !m.ageBand; })[0];
            var name = key === "reinvestRatio" ? "재투자율" : "순투자율";
            function label(c) { return c.ageBand + " · " + MARIA.status.investorTypeLabel(c.investorType); }
            var tail = "";
            if (key === "reinvestRatio" && all && all.reinvestRatio != null && all2 && all2.reinvestMedian != null) {
                tail = ". 전체 합계 비율은 " + pct1(Number(all.reinvestRatio)) + ", 계좌별 중앙값은 " + pct1(Number(all2.reinvestMedian)) + "예요";
            }
            setInterp("#ffPanelSegments",
                name + "이 가장 높은 칸은 " + label(hi) + " " + pct1(Number(hi[key])) + ", 가장 낮은 칸은 " + label(lo) + " " + pct1(Number(lo[key])) + "(격차 " + ((Number(hi[key]) - Number(lo[key])) * 100).toFixed(1) + "%p)예요" + tail,
                {
                    see: "고객을 연령대와 투자성향으로 나눠, 칸마다 확정 원화 유입 대비 국내 매수(재투자율) 또는 순투자 비율을 본 거예요.",
                    read: "색이 진할수록 비율이 높아요(이 표 안의 최솟값~최댓값 기준이라 다른 지표와 색을 비교하면 안 돼요). 아래 중앙값 표에서 합계 비율과 중앙값이 많이 다르면 일부 큰 계좌가 전체 비율을 끌고 있다는 신호예요.",
                    limit: "칸마다 계좌 수가 달라서 계좌가 적은 칸의 극단값을 과하게 해석하면 안 돼요. 5명 미만 칸은 가려요. 연령이나 성향이 재투자를 '만든다'고 말할 수 없고, 합성 데이터의 분포일 뿐이에요."
                });
        },
        retention: function () {
            var d = state.retention;
            var pts = (d && d.points) || [];
            if (!d || !num(d.lots) || !pts.length) return;
            var last = pts[pts.length - 1];
            setInterp("#ffPanelRetention",
                "확정 후 " + last.day + "일 시점까지 원금이 유지된 비율은 " + pct1(Number(last.survival)) + "(95% 구간 " + pct1(Number(last.lower)) + " ~ " + pct1(Number(last.upper)) +
                ")이고, 1년 미경과 원금이 인출된 건은 " + KRW.format(num(d.events)) + " / " + KRW.format(num(d.lots)) + "건이에요",
                {
                    see: "환전이 확정된 뒤 1년이 되기 전에 원금을 빼면 혜택이 취소돼요. 확정 후 며칠이 지나도록 원금이 그대로 남아 있는 건이 얼마나 되는지를 Kaplan-Meier 방식으로 본 곡선이에요.",
                    read: "곡선이 내려갈수록 중간에 원금이 인출된 건이 늘어난다는 뜻이고, 띠(95% 구간)가 좁을수록 믿을 만해요. 금액 가중 값이 건수 기준보다 높으면 큰 금액은 상대적으로 덜 인출됐다는 뜻이에요.",
                    limit: "가장 오래 관찰된 건이 1년이 안 돼서 1년 유지율은 정의할 수 없어요. 오른쪽 끝은 관찰된 건이 적어 불확실해요. 인출이 대부분 해지 강제 인출이라, 곡선의 모양은 고객 행동이 아니라 데이터 생성 규칙의 결과일 수 있어요."
                });
        },
        concentration: function () {
            var d = state.concentration;
            if (!d || !num(d.accounts)) return;
            setInterp("#ffPanelConc",
                "감면을 받는 " + KRW.format(num(d.accounts)) + "개 계좌 중 상위 10%가 감면액의 " + pct1(d.top10Share == null ? null : Number(d.top10Share)) + "를 차지하고, 지니계수는 " +
                (d.gini == null ? "-" : Number(d.gini).toFixed(2)) + "예요",
                {
                    see: "감면액이 계좌들 사이에 얼마나 고르게(또는 한쪽으로) 나뉘어 있는지를 로렌츠 곡선과 지니계수, 상위 10% 점유율로 본 거예요.",
                    read: "곡선이 대각선에 붙어 있으면 감면이 고르게 나뉜 거예요. 대각선에서 멀수록(지니계수가 1에 가까울수록) 소수 계좌에 몰려 있어요. 상위 10% 점유율은 그 쏠림을 가장 직관적으로 보여줘요.",
                    limit: "감면액은 양도소득이 클수록 커지는 구조라 집중이 곧 불공평은 아니에요. 자산 규모 구간별 비교는 구간 정의가 없어서 아직 못 해요. 감면액 정의가 잠정이라 값도 잠정이에요."
                });
        }
    };

    var INTERP_OF = {
        monthly: ["sankey", "monthly", "bridge"],
        funnel: ["funnel"],
        leakage: ["leakage"],
        relief: ["relief"],
        allocation: ["allocation"],
        segments: ["segments"],
        retention: ["retention"],
        concentration: ["concentration"]
    };

    function refreshInterp(api) {
        (INTERP_OF[api] || []).forEach(function (name) {
            try { INTERP[name](); } catch (e) { if (window.console) console.warn("해석 문장을 만들지 못했어요", name, e); }
        });
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
            $c.find("details.ff-detail").attr("open", "open");
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
            '<p>서비스: Returns RIA</p><p>기간: ' + esc(periodLabel()) + '</p><p>집계 기준일: ' + esc(asOf) + '</p><p>출력 일시(업무시각): ' + esc(clock) + '</p>' +
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
        segments: ["#ffPanelSegments"],
        retention: ["#ffPanelRetention"],
        concentration: ["#ffPanelConc"]
    };
    var SUMMARY_OF = {
        overview: ["ffSumOpened", "ffSumInflow", "ffSumReinvest"],
        relief: ["ffSumRelief", "ffSumPerRelief"],
        leakage: ["ffSumBenefit"]
    };
    var LABEL_OF = { monthly: "월별 자금 흐름", overview: "핵심 지표", relief: "감면 지표", leakage: "혜택 누수", funnel: "퍼널", allocation: "국내 운용처", segments: "연령대 × 투자성향", retention: "원금 유지", concentration: "혜택 집중도" };

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

    var loadSeq = {}; // API 별 요청 번호: 기간을 연달아 바꿀 때 늦게 온 옛 응답이 새 응답을 덮지 않게 한다

    function load(api) {
        var seq = (loadSeq[api] = (loadSeq[api] || 0) + 1);
        var panels = PANELS_OF[api] || [];
        panels.forEach(function (sel) { setPanel($(sel), "loading"); });
        if (SUMMARY_OF[api]) summaryLoading(SUMMARY_OF[api]);

        return MARIA.auth.ajax({ url: API[api].url, method: "GET", data: API[api].period ? { period: period } : undefined }).done(function (res) {
            if (seq !== loadSeq[api]) return;
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
            } else if (api === "retention") {
                renderRetention();
            } else if (api === "concentration") {
                renderConcentration();
            }
            refreshInterp(api);
            renderDefs();
        }).fail(function () {
            if (seq !== loadSeq[api]) return;
            delete state[api];
            if (SUMMARY_OF[api]) summaryFail(SUMMARY_OF[api], api);
            panels.forEach(function (sel) { showPanelError($(sel), LABEL_OF[api] || "데이터", api); });
            renderDefs();
        });
    }

    $(document).on("click", "[data-ff-retry]", function () {
        load($(this).attr("data-ff-retry"));
    });

    // 기간을 바꾸면 기간을 따르는 API 만 다시 부른다 (혜택 누수 · 감면은 스냅샷이라 그대로)
    $("#ffPeriod").on("click", "button", function () {
        var next = $(this).attr("data-period");
        if (next === period) return;
        period = next;
        $("#ffPeriod button").removeClass("is-on");
        $(this).addClass("is-on");
        renderPeriodRange();
        Object.keys(API).forEach(function (api) { if (API[api].period) load(api); });
    });

    $("#ffBridgeMonth").on("change", function () {
        renderBridge();
        refreshInterp("monthly");
    });
    $("#ffSegMetric").on("click", "button", function () {
        segMetric = $(this).attr("data-metric");
        $("#ffSegMetric button").removeClass("is-on");
        $(this).addClass("is-on");
        if (state.segments) {
            renderSegments();
            refreshInterp("segments");
        }
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
