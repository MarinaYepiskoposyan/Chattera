package com.chattera.security;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers CHAT-26: {@code role.toUpperCase()} with no {@link Locale} mis-maps
 * roles under locales like Turkish, where {@code "admin".toUpperCase()}
 * produces a dotted-I variant instead of plain {@code "ADMIN"}, silently
 * breaking {@code hasRole("ADMIN")} checks everywhere this shared converter
 * is used.
 */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(Locale.US);
    }

    @Test
    void mapsRolesToPrefixedUppercaseAuthoritiesUnderTheDefaultLocale() {
        Locale.setDefault(Locale.US);

        Collection<GrantedAuthority> authorities = converter.convert(jwtWithRealmRoles("admin", "user"));

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void mapsRolesToPlainAsciiUppercaseEvenUnderTurkishLocale() {
        // Turkish-locale toUpperCase("admin") without Locale.ROOT yields
        // "ADMİN" (dotted capital I), which would never match hasRole("ADMIN").
        Locale.setDefault(Locale.of("tr", "TR"));

        Collection<GrantedAuthority> authorities = converter.convert(jwtWithRealmRoles("admin"));

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void returnsNoAuthoritiesWhenRealmAccessClaimIsAbsent() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "user-1")
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(60))
                .build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    private static Jwt jwtWithRealmRoles(String... roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "user-1")
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(60))
                .build();
    }
}
