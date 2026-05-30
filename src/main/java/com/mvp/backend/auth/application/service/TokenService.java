package com.mvp.backend.auth.application.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.mvp.backend.auth.application.dto.AuthResponse;
import com.mvp.backend.auth.domain.model.UserRole;
import com.mvp.backend.config.AppSecurityProperties;

@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final AppSecurityProperties properties;

    public TokenService(JwtEncoder jwtEncoder, AppSecurityProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public AuthResponse issue(UUID userId, UserRole role) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(userId.toString())
                .claim("role", role.name())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new AuthResponse(userId, token, expiresAt, role);
    }
}
