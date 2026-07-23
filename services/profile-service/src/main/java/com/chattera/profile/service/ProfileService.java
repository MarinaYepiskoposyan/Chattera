package com.chattera.profile.service;

import java.time.Instant;

import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.chattera.profile.domain.Profile;
import com.chattera.profile.repository.ProfileRepository;
import com.chattera.profile.web.dto.UpdateProfileRequest;

/**
 * Owns just-in-time profile provisioning and profile reads/updates. JIT
 * provisioning: the first authenticated request from a {@code sub} with no
 * existing profile row creates one, seeded from the JWT's
 * {@code preferred_username} claim (falling back to {@code name}, then
 * null if neither is present - a reasonable default, not a hard
 * requirement per CHAT-103).
 */
@Service
public class ProfileService {

    private final ProfileRepository profileRepository;

    /**
     * Self-reference to this bean's own Spring proxy, used only to invoke
     * {@link #insertNewProfile(String, String)} through AOP. A plain {@code
     * this.insertNewProfile(...)} self-call would bypass the proxy and
     * silently no-op its {@code @Transactional(REQUIRES_NEW)} - Spring can
     * only apply transactional advice to calls that go through the proxy.
     * {@code @Lazy} breaks the circular-bean-creation issue that would
     * otherwise come from a bean depending on its own proxy.
     */
    private final ProfileService self;

    public ProfileService(ProfileRepository profileRepository, @Lazy ProfileService self) {
        this.profileRepository = profileRepository;
        this.self = self;
    }

    @Transactional
    public Profile getOrProvision(Jwt jwt) {
        String userId = jwt.getSubject();
        return profileRepository.findById(userId)
                .orElseGet(() -> provision(userId, jwt));
    }

    private Profile provision(String userId, Jwt jwt) {
        String defaultDisplayName = jwt.getClaimAsString("preferred_username");
        if (defaultDisplayName == null) {
            defaultDisplayName = jwt.getClaimAsString("name");
        }
        try {
            return self.insertNewProfile(userId, defaultDisplayName);
        } catch (DataIntegrityViolationException duplicateUserId) {
            // Another request for the same brand-new userId won the race (see
            // CHAT-103 review finding): both of us passed the findById check above
            // before either committed, and the other request's insert got there
            // first. insertNewProfile runs in its own REQUIRES_NEW transaction, so
            // only that nested transaction was rolled back by the primary-key
            // violation - this method's own (possibly ambient) transaction was
            // never touched by the failing statement and is safe to keep using, and
            // the winner's row is already committed and visible by now.
            return profileRepository.findById(userId).orElseThrow(() -> duplicateUserId);
        }
    }

    /**
     * Isolated in its own transaction so a duplicate-key failure here can be
     * caught and recovered from in {@link #provision(String, Jwt)} without
     * risking the caller's own transaction: on backends where a failed
     * statement poisons the rest of the enclosing transaction (e.g.
     * PostgreSQL aborts the whole transaction, not just the statement),
     * catching this same exception inside a single shared transaction would
     * leave that transaction unusable for the follow-up read.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Profile insertNewProfile(String userId, String displayName) {
        return profileRepository.save(new Profile(userId, displayName, null, null, Instant.now()));
    }

    @Transactional
    public Profile updateProfile(Jwt jwt, UpdateProfileRequest request) {
        Profile profile = getOrProvision(jwt);
        if (request.displayName() != null) {
            profile.setDisplayName(request.displayName());
        }
        if (request.avatarUrl() != null) {
            profile.setAvatarUrl(request.avatarUrl());
        }
        if (request.timezone() != null) {
            profile.setTimezone(request.timezone());
        }
        return profileRepository.save(profile);
    }
}
