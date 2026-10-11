package com.smartlib.security;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public class SmartLibAuthentication extends AbstractAuthenticationToken {

    private final SmartLibPrincipal principal;
    private final transient Jwt jwt;

    public SmartLibAuthentication(SmartLibPrincipal principal, Jwt jwt) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
        this.principal = principal;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

    @Override
    public SmartLibPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return String.valueOf(principal.userId());
    }
}
