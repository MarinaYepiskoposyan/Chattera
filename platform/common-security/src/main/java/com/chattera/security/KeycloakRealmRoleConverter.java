package com.chattera.security;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps a Keycloak-issued JWT's {@code realm_access.roles} claim to Spring
 * Security {@link GrantedAuthority} instances, prefixed with {@code ROLE_}
 * per Spring convention. Resource-level ({@code resource_access}) roles are
 * not mapped - Sprint 1 has no per-client-role authorization requirement;
 * add that mapping if/when a service needs it rather than pre-building it.
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_CLAIM = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
        if (realmAccess == null) {
            return List.of();
        }
        Object roles = realmAccess.get(ROLES_CLAIM);
        if (!(roles instanceof Collection<?> roleCollection)) {
            return List.of();
        }
        return roleCollection.stream()
                .map(Object::toString)
                .map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role.toUpperCase(Locale.ROOT)))
                .collect(Collectors.toUnmodifiableSet());
    }
}
