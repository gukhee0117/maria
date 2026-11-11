package com.app.maria.domain.notification.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;

/** Existing seeded accounts are exempt; newly registered accounts still require verification. */
final class SeedEmailVerificationExemptions {
    private static final Set<String> CONTACTS = load();

    private SeedEmailVerificationExemptions() {}

    static boolean matches(Long customerId, String loginId) {
        if (customerId == null || loginId == null || loginId.isBlank()) return false;
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest((customerId + "\n" + loginId).getBytes(StandardCharsets.UTF_8));
            return CONTACTS.contains(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static Set<String> load() {
        try (var reader =
                new BufferedReader(
                        new InputStreamReader(
                                new ClassPathResource("notification/seed-email-exemptions.sha256")
                                        .getInputStream(),
                                StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> line.matches("[0-9a-f]{64}"))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException failure) {
            throw new IllegalStateException("Seed email exceptions could not be loaded", failure);
        }
    }
}
