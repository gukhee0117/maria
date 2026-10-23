package com.app.maria.global.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;

/** 1차 MyBatis의 기본 transactionManager를 유지하고 2차 JPA 트랜잭션을 별도로 연결한다. */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.app.maria.domain",
        transactionManagerRef = "jpaTransactionManager")
public class JpaConfig {
    @Bean(name = "jpaTransactionManager")
    public JpaTransactionManager jpaTransactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
