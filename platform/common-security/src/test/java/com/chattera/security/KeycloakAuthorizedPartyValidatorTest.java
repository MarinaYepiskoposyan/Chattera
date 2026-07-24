package com.chattera.security;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the shared {@code azp} allowlist validator. No Spring context
 * or Keycloak needed -- the validator is pure claim inspection.
 */
class KeycloakAuthorizedPartyValidatorTest {

    private final KeycloakAuthorizedPartyValidator validator =
            new KeycloakAuthorizedPartyValidator(List.of("chattera-web", "chattera-mobile", "chattera-test-client"));

    private static Jwt jwtWithAzp(String azp) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject("3a0205b3-67f5-45ed-aee7-b8a1b5101a68")
                .claim("aud", "account");
        if (azp != null) {
            builder.claim("azp", azp);
        }
        return builder.build();
    }

    @Test
    void acceptsTokenFromTheWebClient() {
        OAuth2TokenValidatorResult result = validator.validate(jwtWithAzp("chattera-web"));
        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void acceptsTokenFromTheDevTestClient() {
        OAuth2TokenValidatorResult result = validator.validate(jwtWithAzp("chattera-test-client"));
        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenFromAnUnknownClientInTheSameRealm() {
        OAuth2TokenValidatorResult result = validator.validate(jwtWithAzp("some-other-realm-client"));
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anySatisfy(error ->
                assertThat(error.getDescription()).contains("authorized party"));
    }

    @Test
    void rejectsTokenWithNoAzpClaim() {
        OAuth2TokenValidatorResult result = validator.validate(jwtWithAzp(null));
        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void rejectsAnEmptyAllowlistAtConstruction() {
        assertThatThrownBy(() -> new KeycloakAuthorizedPartyValidator(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
