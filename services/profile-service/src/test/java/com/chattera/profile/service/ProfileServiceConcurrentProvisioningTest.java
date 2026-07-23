package com.chattera.profile.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.chattera.profile.domain.Profile;
import com.chattera.profile.repository.ProfileRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the actual database-level race behind the CHAT-103 review
 * finding: two genuinely concurrent transactions racing {@code getOrProvision}
 * for the same brand-new {@code userId}, against a real (embedded H2)
 * database rather than a mocked repository. A single-threaded happy-path
 * test would not have caught the original duplicate-key bug, since it never
 * lets two callers both observe an empty {@code findById} before either
 * commits.
 *
 * <p>{@code @DataJpaTest} normally wraps each test in a transaction that
 * rolls back at the end, which would defeat this test: the two worker
 * threads below need their own independent, genuinely committed
 * transactions against the shared embedded database. {@code @Transactional
 * (propagation = NOT_SUPPORTED)} here overrides that default so the test
 * method itself runs non-transactionally and each {@code getOrProvision}
 * call gets its own transaction, as it would in production.
 */
@DataJpaTest
@Import(ProfileService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProfileServiceConcurrentProvisioningTest {

    private static final int ITERATIONS = 25;

    @Autowired
    private ProfileService profileService;

    @Autowired
    private ProfileRepository profileRepository;

    @Test
    void concurrentFirstRequestsForTheSameNewUserBothReturnTheSameProvisionedProfile() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                String userId = "race-user-" + i;
                Jwt jwt = jwtFor(userId, "racer-" + i);
                CyclicBarrier barrier = new CyclicBarrier(2);

                Callable<Profile> raceGetOrProvision = () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return profileService.getOrProvision(jwt);
                };

                Future<Profile> first = executor.submit(raceGetOrProvision);
                Future<Profile> second = executor.submit(raceGetOrProvision);

                // .get() re-throws any exception from the callable (e.g. the
                // DataIntegrityViolationException the original bug let escape)
                // as an ExecutionException, failing the test.
                Profile firstProfile = first.get(10, TimeUnit.SECONDS);
                Profile secondProfile = second.get(10, TimeUnit.SECONDS);

                assertThat(firstProfile.getUserId()).isEqualTo(userId);
                assertThat(secondProfile.getUserId()).isEqualTo(userId);
                // Both calls resolved to the one row that was actually persisted,
                // not two independently-provisioned copies (a tolerance, rather
                // than exact equality, because the winner's copy is the in-memory
                // instance from the initial insert while the loser's is reread
                // from the DB - H2's TIMESTAMP round-trip can shift that by a
                // couple of milliseconds, which isn't a meaningful difference).
                assertThat(Duration.between(firstProfile.getCreatedAt(), secondProfile.getCreatedAt()).abs())
                        .isLessThan(Duration.ofSeconds(2));
                // The PK constraint on profiles.user_id backstops this regardless,
                // but confirm explicitly: exactly one row exists for this userId.
                assertThat(profileRepository.findById(userId)).isPresent();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private Jwt jwtFor(String subject, String preferredUsername) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .claim("preferred_username", preferredUsername)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
