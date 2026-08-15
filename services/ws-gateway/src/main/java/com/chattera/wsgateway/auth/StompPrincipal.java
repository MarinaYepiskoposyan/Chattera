package com.chattera.wsgateway.auth;

import java.security.Principal;

/**
 * The STOMP session principal, resolved from the Keycloak access token's
 * {@code sub} claim at CONNECT ({@link StompAuthChannelInterceptor}) - never
 * from anything client-supplied afterwards.
 */
public record StompPrincipal(String name) implements Principal {

    @Override
    public String getName() {
        return name;
    }
}
