package com.chattera.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for common-security's shared JWT validation, bound from
 * {@code chattera.security.jwt.*}.
 */
@ConfigurationProperties(prefix = "chattera.security.jwt")
public class CommonSecurityProperties {

    /**
     * Client ids accepted in an access token's {@code azp} (authorized party)
     * claim. A token whose {@code azp} is not in this list is rejected by
     * {@link KeycloakAuthorizedPartyValidator}.
     *
     * <p>Defaults to the {@code chattera} realm's sanctioned clients
     * (see {@code infra/keycloak/realm-export/chattera-realm.json}).
     * {@code chattera-test-client} is included so the documented dev/test-only
     * headless smoke-test flow (see {@code infra/README.md}) works with zero
     * per-service config; it is contained to dev/test realms at the Keycloak
     * layer (it is never promoted to staging/production), so no
     * production-issued token can ever carry that {@code azp}. Environments
     * that want a narrower set can override this property.
     */
    private List<String> acceptedClientIds = List.of(
            "chattera-web", "chattera-mobile", "chattera-test-client");

    public List<String> getAcceptedClientIds() {
        return acceptedClientIds;
    }

    public void setAcceptedClientIds(List<String> acceptedClientIds) {
        this.acceptedClientIds = acceptedClientIds;
    }
}
