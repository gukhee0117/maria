package com.app.maria.global.config.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MydataApiPropertiesTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void ownBrokerName이_없으면_기동에_실패한다() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void ownBrokerName이_있으면_기동한다() {
        runner.withPropertyValues("custom.mydata.own-broker-name=리턴증권")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @EnableConfigurationProperties(MydataApiProperties.class)
    static class Config {}
}
