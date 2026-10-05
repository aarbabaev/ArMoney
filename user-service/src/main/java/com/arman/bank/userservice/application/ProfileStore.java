package com.arman.bank.userservice.application;
import com.arman.bank.userservice.domain.Profile;
import java.util.Optional;
import java.util.UUID;
public interface ProfileStore {
    void registerPhone(UUID identityId, String phone);
    Profile save(Profile profile);
    Optional<Profile> find(UUID identityId);
    Optional<Profile> changePhone(UUID identityId, String phone);
    Optional<Profile> resolvePhone(UUID requester, String phone);
}
