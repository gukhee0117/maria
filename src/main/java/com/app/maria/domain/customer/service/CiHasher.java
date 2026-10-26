package com.app.maria.domain.customer.service;

import com.app.maria.global.config.properties.CiProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ci_hash = HMAC-SHA256(주민번호 숫자 13자리(Resident Registration Number), 공유 PEPPER). 시드 생성기·myData·증권사와
 * 같은 산식이다.
 */
@Component
@RequiredArgsConstructor
public class CiHasher {
    private final CiProperties properties;

    public String of(String rrn) {
        String digits = rrn.replaceAll("\\D", "");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            properties.getPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(digits.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ci_hash 생성 실패", e);
        }
    }
}
