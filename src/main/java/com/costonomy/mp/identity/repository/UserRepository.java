package com.costonomy.mp.identity.repository;

import com.costonomy.mp.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** {@code phone} must already be E.164-normalised. */
    Optional<User> findByPhone(String phone);

    boolean existsByPhone(String phone);
}
