package com.app.maria.global.config.properties;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** ci_hash 계산용 공유 PEPPER. 시드 생성기·myData·증권사와 같은 값이어야 한다. */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "app.ci")
public class CiProperties {
    @NotBlank private String pepper;
}
