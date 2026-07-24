package com.chattera.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Auto-configures the {@link JwtDecoder} every Chattera resource server uses,
 * adding {@link KeycloakAuthorizedPartyValidator} on top of Spring Security's
 * default signature/issuer/expiry checks. Registering it here -- rather than
 * in each service's {@code SecurityConfig} -- means every service that depends
 * on common-security and sets {@code issuer-uri} inherits {@code azp}
 * validation automatically, the same way {@link KeycloakRealmRoleConverter} is
 * shared today.
 *
 * <p>Runs {@code before} Spring Boot's own resource-server auto-configuration
 * so this decoder (with the extra validator) is the one that gets built;
 * Boot's default decoder backs off via its own {@code @ConditionalOnMissingBean}.
 * A service can still fully override this by declaring its own
 * {@link JwtDecoder} bean.
 */
@AutoConfiguration(before = OAuth2ResourceServerAutoConfiguration.class)
@ConditionalOnClass(NimbusJwtDecoder.class)
@ConditionalOnProperty(prefix = "spring.security.oauth2.resourceserver.jwt", name = "issuer-uri")
@EnableConfigurationProperties(CommonSecurityProperties.class)
public class JwtDecoderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            CommonSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new KeycloakAuthorizedPartyValidator(properties.getAcceptedClientIds()));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
