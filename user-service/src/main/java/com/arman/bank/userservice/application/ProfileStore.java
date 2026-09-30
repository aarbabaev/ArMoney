package com.arman.bank.userservice.application;
import com.arman.bank.userservice.domain.Profile;
import java.util.Optional;
import java.util.UUID;
public interface ProfileStore {
    Profile save(Profile profile);
    Optional<Profile> find(UUID identityId);
}
