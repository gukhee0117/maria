package com.app.maria.domain.notification.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/** Public seed contact fingerprints are lookup indexes, never authentication secrets. */
@Slf4j
final class SeedEmailVerificationExemptions {
    private final Map<Long, String> contacts;

    SeedEmailVerificationExemptions() {
        this(new ClassPathResource("notification/seed-email-exemptions.sha256"));
    }

    SeedEmailVerificationExemptions(Resource resource) {
        contacts = load(resource);
    }

    boolean containsCustomer(Long customerId) {
        return customerId != null && contacts.containsKey(customerId);
    }

    boolean matches(Long customerId, String loginId, String email) {
        if (!containsCustomer(customerId) || loginId == null || email == null || email.isBlank()) {
            return false;
        }
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    (customerId + "\n" + loginId + "\n" + email)
                                            .getBytes(StandardCharsets.UTF_8));
            return contacts.get(customerId).equals(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static Map<Long, String> load(Resource resource) {
        try (var reader =
                new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            Map<Long, String> rows = new HashMap<>();
            for (String line : reader.lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(":", -1);
                if (parts.length != 2
                        || !parts[1].matches("[0-9a-f]{64}")
                        || rows.putIfAbsent(Long.valueOf(parts[0]), parts[1]) != null) {
                    throw new IllegalArgumentException("Invalid seed contact index");
                }
            }
            return Map.copyOf(rows);
        } catch (IOException | IllegalArgumentException failure) {
            log.warn("NOTIFICATION_SEED_EMAIL_EXEMPTIONS_UNAVAILABLE");
            return Map.of();
        }
    }
}
