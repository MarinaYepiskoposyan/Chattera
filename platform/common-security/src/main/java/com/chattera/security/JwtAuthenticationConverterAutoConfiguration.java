package com.chattera.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Auto-configures a {@link JwtAuthenticationConverter} wired with
 * {@link KeycloakRealmRoleConverter} for any Chattera service that depends on
 * this module and {@code spring-boot-starter-oauth2-resource-server}. A
 * service can override this by declaring its own
 * {@code Converter<Jwt, AbstractAuthenticationToken>} bean.
 */
@AutoConfiguration
@ConditionalOnClass(JwtAuthenticationToken.class)
public class JwtAuthenticationConverterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }
}
