package com.app.maria.global.jwt;

import com.app.maria.domain.admin.type.AdminRole;
import com.app.maria.global.config.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getSecret()));
    }

    public String createAccessToken(Long adminId, String loginId, String name, AdminRole role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtProperties.getExpirationMinute() * 60 * 1000);

        return Jwts.builder()
                .subject(String.valueOf(adminId))
                .claim("type", "access")
                .claim("loginId", loginId)
                .claim("name", name)
                .claim("role", role.name())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    public String createRefreshToken(Long adminId) {
        Date now = new Date();
        Date expiry =
                new Date(
                        now.getTime()
                                + jwtProperties.getRefreshExpirationDay() * 24 * 60 * 60 * 1000);

        return Jwts.builder()
                .subject(String.valueOf(adminId))
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
