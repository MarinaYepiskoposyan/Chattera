package com.chattera.profile.repository;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.chattera.profile.domain.Profile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real V1__create_profiles_table.sql Flyway migration against
 * an embedded H2 database (auto-substituted by @DataJpaTest), with Hibernate
 * ddl-auto left at "validate" (as configured in application.yml) so this
 * also confirms the entity mapping matches the migration. Lightweight
 * stand-in for full Postgres integration coverage - not the final test
 * architecture.
 */
@DataJpaTest
class ProfileRepositoryTest {

    @Autowired
    private ProfileRepository profileRepository;

    @Test
    void savesAndReadsBackAProfileByUserId() {
        Profile saved = profileRepository.save(
                new Profile("keycloak-sub-1", "Ada", "https://example.com/a.png", "UTC", Instant.now()));

        Optional<Profile> found = profileRepository.findById(saved.getUserId());

        assertThat(found).isPresent();
        assertThat(found.get().getDisplayName()).isEqualTo("Ada");
        assertThat(found.get().getAvatarUrl()).isEqualTo("https://example.com/a.png");
        assertThat(found.get().getTimezone()).isEqualTo("UTC");
    }

    @Test
    void unknownUserIdIsAbsent() {
        assertThat(profileRepository.findById("does-not-exist")).isEmpty();
    }
}
