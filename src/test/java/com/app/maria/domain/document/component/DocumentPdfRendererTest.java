package com.app.maria.domain.document.component;

import static com.app.maria.domain.tax.fixture.TaxFixtures.allSeedRules;
import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.tax.dto.TaxBreakdownDTO;
import com.app.maria.domain.tax.dto.TaxCalculationDTO;
import com.app.maria.domain.tax.dto.TaxExternalTradeDetailDTO;
import com.app.maria.domain.tax.dto.TaxPeriodBreakdownDTO;
import com.app.maria.domain.tax.type.TaxBasisType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class DocumentPdfRendererTest {

    private final TaxReliefCertViewAssembler assembler = new TaxReliefCertViewAssembler();
    private final DocumentPdfRenderer renderer = new DocumentPdfRenderer(engine());

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    /** CLAUDE.md §3 검증 예시. 조정비율은 계산기가 소수 4자리로 반올림한 값(0.7442)을 쓴다. */
    private TaxReliefCertView view(BigDecimal adjustRatio, BigDecimal externalAmount) {
        return view(2026, adjustRatio, externalAmount);
    }

    private TaxReliefCertView view(int taxYear, BigDecimal adjustRatio, BigDecimal externalAmount) {
        AccountDTO account =
                AccountDTO.builder()
                        .accountId(1L)
                        .accountNo("1234567890")
                        .openedAt(LocalDateTime.of(2026, 3, 24, 9, 0))
                        .limitAmount(new BigDecimal("50000000"))
                        .build();
        TaxCalculationDTO calc =
                TaxCalculationDTO.builder()
                        .calcId(10L)
                        .accountId(1L)
                        .basisType(TaxBasisType.FINAL_REPORT)
                        .weightedSell(new BigDecimal("43000000.00"))
                        .originalGainAmount(new BigDecimal("32000000.00"))
                        .weightedGain(new BigDecimal("27800000.00"))
                        .weightedExternalAmount(externalAmount)
                        .adjustRatio(adjustRatio)
                        .finalDeduction(new BigDecimal("20688760.00"))
                        .finalTax(new BigDecimal("1938473.00"))
                        .build();
        TaxBreakdownDTO breakdown =
                TaxBreakdownDTO.of(
                        List.of(
                                period(1, 5, "1.0000", "30000000", "20000000"),
                                period(6, 7, "0.8000", "10000000", "6000000"),
                                period(8, 12, "0.5000", "10000000", "6000000")),
                        List.of(),
                        List.of());
        return assembler.assemble(
                taxYear,
                "RIA-2027-ABCD2345",
                LocalDateTime.of(2027, 4, 1, 10, 0),
                "홍길동",
                account,
                calc,
                breakdown,
                allSeedRules());
    }

    private TaxPeriodBreakdownDTO period(
            int fromMonth, int toMonth, String weight, String sell, String gain) {
        return TaxPeriodBreakdownDTO.builder()
                .validFrom(LocalDate.of(2026, fromMonth, 1))
                .validTo(LocalDate.of(2026, toMonth, 28))
                .weight(new BigDecimal(weight))
                .sellAmount(new BigDecimal(sell))
                .gainAmount(new BigDecimal(gain))
                .externalNetBuyAmount(BigDecimal.ZERO)
                .build();
    }

    @Test
    @DisplayName("조립: 금액은 천 단위 쉼표, 비율은 %, 구간 표는 가중치를 곱해 보여준다")
    void assemble_formatsValues() {
        TaxReliefCertView v = view(new BigDecimal("0.7442"), new BigDecimal("11000000.00"));

        assertThat(v.getTotalWeightedGain()).isEqualTo("27,800,000");
        assertThat(v.getAdjustRatio()).isEqualTo("74.42%");
        assertThat(v.getFinalTax()).isEqualTo("1,938,473원");
        assertThat(v.getBasicDeduction()).isEqualTo("2,500,000원");
        assertThat(v.getTaxRate()).isEqualTo("22%");
        assertThat(v.getPeriods()).hasSize(3);
        assertThat(v.getPeriods().get(1).getLabel()).isEqualTo("6~7월");
        assertThat(v.getPeriods().get(1).getWeight()).isEqualTo("80%");
        assertThat(v.getPeriods().get(1).getWeightedSell()).isEqualTo("8,000,000");
        assertThat(v.getTotalSell()).isEqualTo("50,000,000");
        assertThat(v.isReducedByExternal()).isTrue();
    }

    @Test
    @DisplayName("PDF: 한글과 금액이 들어간 PDF가 만들어진다")
    void render_producesPdfWithKoreanText() throws Exception {
        byte[] pdf = renderer.render(view(new BigDecimal("0.7442"), new BigDecimal("11000000.00")));

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (PDDocument document = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("RIA-2027-ABCD2345")
                    .contains("과세특례 증빙서")
                    .contains("2026년 귀속")
                    .contains("홍길동")
                    .contains("1,938,473원")
                    .contains("RIA 외 계좌의 해외주식 순매수");
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("PDF: 귀속연도는 값으로 넘긴 연도가 찍힌다")
    void render_usesGivenTaxYear() throws Exception {
        byte[] pdf = renderer.render(view(2030, new BigDecimal("1.0000"), BigDecimal.ZERO));

        try (PDDocument document = PDDocument.load(pdf)) {
            assertThat(new PDFTextStripper().getText(document))
                    .contains("2030년 귀속")
                    .doesNotContain("2026년 귀속");
        }
    }

    @Test
    @DisplayName("PDF: 조정비율이 100%면 순매수 안내 문장이 없다")
    void render_noExternalNotice_whenRatioIsOne() throws Exception {
        byte[] pdf = renderer.render(view(new BigDecimal("1.0000"), BigDecimal.ZERO));

        try (PDDocument document = PDDocument.load(pdf)) {
            assertThat(new PDFTextStripper().getText(document))
                    .doesNotContain("RIA 외 계좌의 해외주식 순매수");
        }
    }

    @Test
    @DisplayName("순매수 내역서: 구간별 가중 순매수와 건별 내역이 PDF에 찍힌다")
    void renderNetPurchaseStatement_listsPeriodsAndTrades() throws Exception {
        TaxCalculationDTO calc =
                TaxCalculationDTO.builder()
                        .weightedExternalAmount(new BigDecimal("11000000.00"))
                        .build();
        TaxBreakdownDTO breakdown =
                TaxBreakdownDTO.of(
                        List.of(
                                externalPeriod(1, 5, "1.0000", "0"),
                                externalPeriod(6, 7, "0.8000", "20000000"),
                                externalPeriod(8, 12, "0.5000", "-10000000")),
                        List.of(),
                        List.of(
                                TaxExternalTradeDetailDTO.builder()
                                        .productLabel("해외주식형펀드A")
                                        .tradeDate(LocalDate.of(2026, 6, 10))
                                        .netBuyAmount(new BigDecimal("20000000"))
                                        .build(),
                                TaxExternalTradeDetailDTO.builder()
                                        .productLabel("AAPL")
                                        .tradeDate(LocalDate.of(2026, 9, 3))
                                        .netBuyAmount(new BigDecimal("-10000000"))
                                        .build()));
        AccountDTO account = AccountDTO.builder().accountNo("1234567890").build();

        NetPurchaseStatementView v =
                new NetPurchaseStatementViewAssembler()
                        .assemble(
                                2026,
                                "RIA-2027-ABCD2345",
                                LocalDateTime.of(2027, 4, 1, 10, 0),
                                "홍길동",
                                account,
                                calc,
                                breakdown);
        assertThat(v.getPeriods().get(1).getWeightedNetBuy()).isEqualTo("16,000,000");
        assertThat(v.getTotalNetBuy()).isEqualTo("10,000,000");
        assertThat(v.getTotalWeightedNetBuy()).isEqualTo("11,000,000");

        byte[] pdf = renderer.render(v);
        try (PDDocument document = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("RIA 외 계좌 순매수 내역서")
                    .contains("RIA-2027-ABCD2345")
                    .contains("해외주식형펀드A")
                    .contains("-10,000,000")
                    .doesNotContain("과세특례 증빙서");
        }
    }

    @Test
    @DisplayName("순매수 내역서: 가중 순매수가 음수면 0원으로 반영된다는 안내가 찍힌다")
    void renderNetPurchaseStatement_negativeIsClampedNotice() throws Exception {
        NetPurchaseStatementView v =
                new NetPurchaseStatementViewAssembler()
                        .assemble(
                                2026,
                                "RIA-2027-ABCD2345",
                                LocalDateTime.of(2027, 4, 1, 10, 0),
                                "홍길동",
                                AccountDTO.builder().accountNo("1234567890").build(),
                                TaxCalculationDTO.builder()
                                        .weightedExternalAmount(BigDecimal.ZERO)
                                        .build(),
                                TaxBreakdownDTO.of(
                                        List.of(externalPeriod(8, 12, "0.5000", "-10000000")),
                                        List.of(),
                                        List.of()));

        assertThat(v.isClampedToZero()).isTrue();
        try (PDDocument document = PDDocument.load(renderer.render(v))) {
            assertThat(new PDFTextStripper().getText(document)).contains("0원으로 반영됩니다");
        }
    }

    @Test
    @DisplayName("순매수 내역서: 거래가 없으면 없다는 문구가 찍힌다")
    void renderNetPurchaseStatement_noTrades() throws Exception {
        NetPurchaseStatementView v =
                new NetPurchaseStatementViewAssembler()
                        .assemble(
                                2026,
                                "RIA-2027-ABCD2345",
                                LocalDateTime.of(2027, 4, 1, 10, 0),
                                "홍길동",
                                AccountDTO.builder().accountNo("1234567890").build(),
                                TaxCalculationDTO.builder()
                                        .weightedExternalAmount(BigDecimal.ZERO)
                                        .build(),
                                TaxBreakdownDTO.of(
                                        List.of(externalPeriod(1, 5, "1.0000", "0")),
                                        List.of(),
                                        List.of()));

        try (PDDocument document = PDDocument.load(renderer.render(v))) {
            assertThat(new PDFTextStripper().getText(document)).contains("순매수 거래가 없습니다");
        }
    }

    private TaxPeriodBreakdownDTO externalPeriod(
            int fromMonth, int toMonth, String weight, String externalNetBuy) {
        return TaxPeriodBreakdownDTO.builder()
                .validFrom(LocalDate.of(2026, fromMonth, 1))
                .validTo(LocalDate.of(2026, toMonth, 28))
                .weight(new BigDecimal(weight))
                .sellAmount(BigDecimal.ZERO)
                .gainAmount(BigDecimal.ZERO)
                .externalNetBuyAmount(new BigDecimal(externalNetBuy))
                .build();
    }
}
