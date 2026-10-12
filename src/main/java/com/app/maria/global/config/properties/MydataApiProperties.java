package com.app.maria.global.config.properties;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "custom.mydata")
@Validated
public class MydataApiProperties {

    private String url;
    @NotBlank private String ownBrokerName;
}
