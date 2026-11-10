package com.app.maria.domain.document.component;

import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

/** 증빙서류 종류별 Thymeleaf 템플릿으로 HTML을 만든 뒤 PDF로 변환한다. 한글은 resources/fonts의 나눔고딕을 PDF에 넣는다. */
@Component
@RequiredArgsConstructor
public class DocumentPdfRenderer {

    private static final String TAX_RELIEF_CERT_TEMPLATE = "document/tax-relief-cert";
    private static final String NET_PURCHASE_STATEMENT_TEMPLATE = "document/net-purchase-statement";
    private static final String FONT_FAMILY = "NanumGothic";

    private final SpringTemplateEngine templateEngine;

    public byte[] render(TaxReliefCertView view) {
        return render(TAX_RELIEF_CERT_TEMPLATE, view, view.getIssueNo());
    }

    public byte[] render(NetPurchaseStatementView view) {
        return render(NET_PURCHASE_STATEMENT_TEMPLATE, view, view.getIssueNo());
    }

    private byte[] render(String template, Object view, String issueNo) {
        Context context = new Context(Locale.KOREA);
        context.setVariable("v", view);
        String html = templateEngine.process(template, context);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(
                    () -> fontStream("NanumGothic-Regular.ttf"),
                    FONT_FAMILY,
                    400,
                    FontStyle.NORMAL,
                    true);
            builder.useFont(
                    () -> fontStream("NanumGothic-Bold.ttf"),
                    FONT_FAMILY,
                    700,
                    FontStyle.NORMAL,
                    true);
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new AppException(ErrorType.DOCUMENT_RENDER_FAILED, issueNo, e);
        }
    }

    private java.io.InputStream fontStream(String fileName) {
        return getClass().getResourceAsStream("/fonts/" + fileName);
    }
}
