package com.agentflow.identity;

import com.agentflow.shared.config.AgentFlowProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final AgentFlowProperties properties;
    private final SecretKey key;
    public JwtService(AgentFlowProperties properties) {
        this.properties = properties;
        byte[] secret = properties.security().jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        this.key = Keys.hmacShaKeyFor(secret);
    }
    public String issue(UUID userId, String email, boolean superAdmin) {
        Instant now = Instant.now();
        return Jwts.builder().issuer(properties.security().issuer()).subject(userId.toString())
                .claim("token_use", "access").claim("email", email).claim("superAdmin", superAdmin).issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.security().accessTokenTtl()))).signWith(key).compact();
    }
    public CurrentUser verify(String token) {
        var claims = Jwts.parser().verifyWith(key).requireIssuer(properties.security().issuer())
                .build().parseSignedClaims(token).getPayload();
        if (claims.getExpiration() == null || claims.getIssuedAt() == null
                || !"access".equals(claims.get("token_use", String.class))
                || claims.getIssuedAt().toInstant().isAfter(Instant.now().plusSeconds(30))) {
            throw new io.jsonwebtoken.JwtException("Invalid access-token claims");
        }
        return new CurrentUser(UUID.fromString(claims.getSubject()), claims.get("email", String.class),
                Boolean.TRUE.equals(claims.get("superAdmin", Boolean.class)));
    }
}
