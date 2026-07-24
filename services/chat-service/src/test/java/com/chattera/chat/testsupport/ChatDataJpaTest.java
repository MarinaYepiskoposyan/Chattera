package com.chattera.chat.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code @DataJpaTest}, with chat-service's Postgres-only schema targeting
 * (see application.yml's {@code spring.flyway.schemas} /
 * {@code spring.jpa.properties.hibernate.default_schema} - both exist only
 * so chat-service's Flyway history and tables don't collide with
 * profile-service's inside the shared dev `chattera` database) reset back
 * to unset. The embedded H2 database {@code @DataJpaTest} substitutes is
 * private to each test anyway, so there's nothing to isolate from, and H2's
 * default schema is {@code PUBLIC}, not {@code chat_service}.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.schemas=",
        "spring.jpa.properties.hibernate.default_schema="
})
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ChatDataJpaTest {
}
