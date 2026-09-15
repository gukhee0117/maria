package com.app.maria.domain.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D2(현금 직접입금 차단): account.amount를 실제로 바꾸는 3개 매퍼 메서드
 * (updateProvisionalAmount/replaceAccountAmount/deductAccountAmount)는 각각 정해진 서비스 하나에서만 호출되어야 한다.
 * AccountMapper/KrwExchangeMapper는 여러 서비스에 주입돼 있어서, 서비스 계층을 건너뛰고 매퍼를 직접 호출하는 새 코드가 생기면 이 테스트가 잡아낸다.
 */
class AccountAmountUpdateCallerTest {

    private static final Map<String, Set<String>> ALLOWED_CALLERS_BY_METHOD =
            Map.of(
                    "updateProvisionalAmount", Set.of("AccountTransactionalServiceImpl"),
                    "replaceAccountAmount", Set.of("SettlementTransactionExecutor"),
                    "deductAccountAmount", Set.of("WithdrawalProcessor"));

    @Test
    @DisplayName("account.amount를 바꾸는 매퍼 메서드는 정해진 클래스에서만 호출된다")
    void onlyKnownClassesCallAccountAmountMapperMethods() throws IOException {
        Map<String, Set<String>> actualCallersByMethod = new HashMap<>();
        for (String methodName : ALLOWED_CALLERS_BY_METHOD.keySet()) {
            actualCallersByMethod.put(methodName, new HashSet<>());
        }

        Path mainSourceRoot = Path.of("src/main/java");
        try (Stream<Path> paths = Files.walk(mainSourceRoot)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .forEach(
                            path -> {
                                String content = readQuietly(path);
                                String className = className(path);
                                for (String methodName : ALLOWED_CALLERS_BY_METHOD.keySet()) {
                                    Pattern callSite =
                                            Pattern.compile("\\w+\\." + methodName + "\\(");
                                    if (callSite.matcher(content).find()) {
                                        actualCallersByMethod.get(methodName).add(className);
                                    }
                                }
                            });
        }

        for (String methodName : ALLOWED_CALLERS_BY_METHOD.keySet()) {
            assertThat(actualCallersByMethod.get(methodName))
                    .as("%s의 호출자", methodName)
                    .containsExactlyInAnyOrderElementsOf(ALLOWED_CALLERS_BY_METHOD.get(methodName));
        }
    }

    private String readQuietly(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String className(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.substring(0, fileName.length() - ".java".length());
    }
}
