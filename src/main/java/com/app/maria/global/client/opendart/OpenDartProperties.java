package com.app.maria.global.client.opendart;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "custom.opendart")
public class OpenDartProperties {
    private String url = "https://opendart.fss.or.kr/api";
    private String apiKey = "";

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
