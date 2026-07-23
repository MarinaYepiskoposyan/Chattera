package com.chattera.profile.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.chattera.profile.domain.Profile;

public interface ProfileRepository extends JpaRepository<Profile, String> {
}
