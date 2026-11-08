package com.app.maria.domain.notification.config;

import java.util.Properties;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;

@Configuration
@EnableAsync
public class NotificationMailConfig {
    @Bean
    public ThreadPoolTaskExecutor settlementMailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("settlement-mail-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        return executor;
    }

    @Bean
    public ThreadPoolTaskExecutor notificationStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("notification-stream-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    @Bean
    @ConditionalOnProperty(name = "SMTP_ENABLED", havingValue = "true")
    public JavaMailSender notificationMailSender(
            @Value("${SMTP_HOST:}") String host,
            @Value("${SMTP_PORT:587}") int port,
            @Value("${SMTP_USERNAME:}") String username,
            @Value("${SMTP_PASSWORD:}") String password,
            @Value("${SMTP_FROM:}") String from,
            @Value("${SMTP_AUTH:true}") boolean auth,
            @Value("${SMTP_STARTTLS:true}") boolean starttls,
            @Value("${SMTP_SSL:false}") boolean ssl) {
        if (!StringUtils.hasText(host)
                || !StringUtils.hasText(from)
                || (auth && (!StringUtils.hasText(username) || !StringUtils.hasText(password)))) {
            throw new IllegalStateException(
                    "SMTP_ENABLED=true이면 SMTP_HOST, SMTP_FROM 및 SMTP 인증 정보를 설정해야 합니다.");
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding("UTF-8");
        Properties properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth", String.valueOf(auth));
        properties.setProperty("mail.smtp.starttls.enable", String.valueOf(starttls));
        properties.setProperty("mail.smtp.starttls.required", String.valueOf(starttls));
        properties.setProperty("mail.smtp.ssl.enable", String.valueOf(ssl));
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "5000");
        properties.setProperty("mail.smtp.writetimeout", "5000");
        return sender;
    }
}
