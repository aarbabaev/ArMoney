package com.arman.bank.userservice.application;
import com.arman.bank.userservice.domain.Profile;
import java.util.Optional;
import java.util.UUID;
public final class ProfileService {
    private final ProfileStore store;
    public ProfileService(ProfileStore store) { this.store = store; }
    public void registerPhone(UUID identity, String phone) { store.registerPhone(identity, Profile.validatePhone(phone)); }
    public Profile save(UUID identity, String displayName) { return store.save(new Profile(UUID.randomUUID(), identity, displayName)); }
    public Profile save(UUID identity, String displayName, String email) { return store.save(new Profile(UUID.randomUUID(), identity, displayName), email); }
    public Optional<Profile> find(UUID identity) { return store.find(identity); }
    public Optional<Profile> changePhone(UUID identity, String phone) { return store.changePhone(identity, Profile.validatePhone(phone)); }
    public Optional<Profile> resolvePhone(UUID requester, String phone) { return store.resolvePhone(requester, Profile.validatePhone(phone)); }
}
