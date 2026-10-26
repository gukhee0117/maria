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

/** Thymeleaf로 HTML을 만든 뒤 PDF로 변환한다. 한글은 resources/fonts의 나눔고딕을 PDF에 넣는다. */
@Component
@RequiredArgsConstructor
public class TaxReliefCertPdfRenderer {

    private static final String TEMPLATE = "document/tax-relief-cert";
    private static final String FONT_FAMILY = "NanumGothic";

    private final SpringTemplateEngine templateEngine;

    public byte[] render(TaxReliefCertView view) {
        Context context = new Context(Locale.KOREA);
        context.setVariable("v", view);
        String html = templateEngine.process(TEMPLATE, context);

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
            throw new AppException(ErrorType.DOCUMENT_RENDER_FAILED, view.getIssueNo(), e);
        }
    }

    private java.io.InputStream fontStream(String fileName) {
        return getClass().getResourceAsStream("/fonts/" + fileName);
    }
}
