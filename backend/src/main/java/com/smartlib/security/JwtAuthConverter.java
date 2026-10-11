package com.smartlib.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/** Turns a validated JWT into a SmartLib principal, re-checking account state against the database. */
@Component
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final PrincipalService principals;

    public JwtAuthConverter(PrincipalService principals) {
        this.principals = principals;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (RuntimeException e) {
            throw new InvalidBearerTokenException("Invalid token subject");
        }
        SmartLibPrincipal principal = principals.load(userId)
                .orElseThrow(() -> new InvalidBearerTokenException("Unknown account"));
        Object ver = jwt.getClaims().get("ver");
        if (!(ver instanceof Number n) || n.intValue() != principal.tokenVersion()) {
            throw new InvalidBearerTokenException("Session has been revoked");
        }
        if (!principal.canAuthenticate()) {
            throw new InvalidBearerTokenException("Account is not active");
        }
        return new SmartLibAuthentication(principal, jwt);
    }
}
