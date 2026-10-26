package com.app.maria.global.config.properties;

import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Component
@Setter
@ConfigurationProperties(prefix = "custom.document")
public class DocumentProperties {
    /** 증빙서류를 발급할 수 있는 첫 업무일. 확정신고(2027-05)보다 한 달 앞서 연다. */
    private LocalDate issueFrom = LocalDate.of(2027, 4, 1);
}
