package com.app.maria.domain.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * D2(현금 직접입금 차단): account.amount를 SET 절에서 변경하는 <update>가 아래 4개 외에 새로 생기면 실패한다. 이 4개는 전부 실제
 * 매도/환전/인출/주문 체결 레코드에서 계산된 값만 반영하며, 임의 금액을 받는 입금 경로는 없다. 새 write path를 추가하려면 이 목록에 의도적으로 추가해야
 * 한다(실수로 생긴 직접입금 경로 차단).
 */
class AccountAmountWritePathTest {

    private static final Set<String> ALLOWED_ACCOUNT_AMOUNT_WRITERS =
            Set.of(
                    "updateProvisionalAmount",
                    "replaceAccountAmount",
                    "deductAccountAmount",
                    // 패키지 주문 체결: 금액을 받지 않고 이미 기록된 체결 주문(domestic_order.amount)에서만 읽는다
                    "applyOrderAmount");

    private static final Pattern TARGETS_ACCOUNT_TABLE =
            Pattern.compile("(?i)\\bupdate\\s+account\\b");
    private static final Pattern SETS_AMOUNT_COLUMN =
            Pattern.compile("(?is)\\bset\\b(?:(?!\\bwhere\\b).)*\\bamount\\b\\s*=");

    private static final Pattern INSERT_INTO_ACCOUNT =
            Pattern.compile(
                    "(?i)insert\\s+into\\s+account\\s*\\(([^)]*)\\)\\s*values\\s*\\(([^)]*)\\)");
    private static final Pattern PARAMETER_PLACEHOLDER = Pattern.compile("^#\\{.*}$");

    @Test
    @DisplayName("account.amount를 SET 절에서 변경하는 <update>는 정해진 4개(가환전/확정산/인출 차감/주문 체결)뿐이다")
    void onlyKnownWritersModifyAccountAmount() throws Exception {
        Set<String> actualWriters = new HashSet<>();

        for (Resource resource : findMapperXmlResources()) {
            Document document = parse(resource);
            NodeList updates = document.getElementsByTagName("update");
            for (int i = 0; i < updates.getLength(); i++) {
                Element update = (Element) updates.item(i);
                String sql = update.getTextContent();
                if (TARGETS_ACCOUNT_TABLE.matcher(sql).find()
                        && SETS_AMOUNT_COLUMN.matcher(sql).find()) {
                    actualWriters.add(update.getAttribute("id"));
                }
            }
        }

        assertThat(actualWriters)
                .containsExactlyInAnyOrderElementsOf(ALLOWED_ACCOUNT_AMOUNT_WRITERS);
    }

    @Test
    @DisplayName("account를 생성하는 <insert>는 amount 컬럼에 파라미터를 바인딩하지 않고 리터럴만 쓴다")
    void noInsertBindsParameterToAccountAmount() throws Exception {
        for (Resource resource : findMapperXmlResources()) {
            Document document = parse(resource);
            NodeList inserts = document.getElementsByTagName("insert");
            for (int i = 0; i < inserts.getLength(); i++) {
                Element insert = (Element) inserts.item(i);
                String sql = insert.getTextContent();
                String amountValue = extractAmountValueToken(sql);
                if (amountValue == null) {
                    continue;
                }
                assertThat(PARAMETER_PLACEHOLDER.matcher(amountValue).matches())
                        .as(
                                "%s의 INSERT가 amount 컬럼에 파라미터(%s)를 바인딩하고 있습니다. "
                                        + "account 생성 시 amount는 반드시 리터럴(0 등)이어야 합니다.",
                                insert.getAttribute("id"), amountValue)
                        .isFalse();
            }
        }
    }

    // account 테이블 INSERT의 컬럼 목록과 VALUES 목록을 같은 순서로 매칭해 amount 위치의 실제 값을 찾는다
    private String extractAmountValueToken(String sql) {
        java.util.regex.Matcher matcher = INSERT_INTO_ACCOUNT.matcher(sql);
        if (!matcher.find()) {
            return null;
        }
        String[] columns = matcher.group(1).split(",");
        String[] values = matcher.group(2).split(",");
        for (int i = 0; i < columns.length && i < values.length; i++) {
            if (columns[i].trim().equalsIgnoreCase("amount")) {
                return values[i].trim();
            }
        }
        return null;
    }

    private Resource[] findMapperXmlResources() throws IOException {
        return new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mappers/**/*.xml");
    }

    private Document parse(Resource resource) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        // MyBatis DTD를 네트워크로 받으러 가지 않도록 빈 entity로 대체
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        try (InputStream inputStream = resource.getInputStream()) {
            return builder.parse(inputStream);
        }
    }
}
