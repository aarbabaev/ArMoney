package com.arman.bank.userservice.application;
import com.arman.bank.userservice.domain.Profile;
import java.util.Optional;
import java.util.UUID;
public final class ProfileService {
    private final ProfileStore store;
    public ProfileService(ProfileStore store) { this.store = store; }
    public Profile save(UUID identity, String displayName) { return store.save(new Profile(UUID.randomUUID(), identity, displayName)); }
    public Optional<Profile> find(UUID identity) { return store.find(identity); }
}
