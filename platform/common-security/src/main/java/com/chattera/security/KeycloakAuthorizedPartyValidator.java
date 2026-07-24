package com.chattera.security;

import java.util.Collection;
import java.util.Set;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.Assert;

/**
 * Rejects any Keycloak access token whose authorized party ({@code azp}
 * claim) is not one of Chattera's sanctioned OIDC clients.
 *
 * <p><strong>Why {@code azp} and not {@code aud}?</strong> Keycloak, in its
 * default configuration (which is what the {@code chattera} realm uses -- no
 * per-service "Audience" protocol mappers are configured), populates the
 * {@code aud} claim with {@code "account"} for every client in the realm, not
 * with the requesting client's id. The claim that actually identifies which
 * client a token was issued to is {@code azp} (RFC 7519 / OIDC "authorized
 * party"). A per-service {@code aud} would require adding audience mappers to
 * the realm and registering each resource server as a Keycloak client --
 * over-engineering for Sprint 1's single trust domain (every Chattera service
 * trusts the same realm and the same frontend client set). Gating on
 * {@code azp} against an allowlist directly rejects the threat the code review
 * flagged: a token minted for a different, lower-trust client in the realm.
 *
 * <p>Fail-closed: a token with a missing {@code azp}, or an {@code azp} not in
 * the accepted set, is rejected.
 */
public final class KeycloakAuthorizedPartyValidator implements OAuth2TokenValidator<Jwt> {

    static final String AZP_CLAIM = "azp";

    private final Set<String> acceptedClientIds;
    private final OAuth2Error error;

    public KeycloakAuthorizedPartyValidator(Collection<String> acceptedClientIds) {
        Assert.notEmpty(acceptedClientIds, "acceptedClientIds must not be empty");
        this.acceptedClientIds = Set.copyOf(acceptedClientIds);
        this.error = new OAuth2Error(
                OAuth2ErrorCodes.INVALID_TOKEN,
                "The token's authorized party (azp) is not an accepted Chattera client",
                null);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String azp = token.getClaimAsString(AZP_CLAIM);
        if (azp != null && acceptedClientIds.contains(azp)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(error);
    }

    Set<String> acceptedClientIds() {
        return acceptedClientIds;
    }
}
