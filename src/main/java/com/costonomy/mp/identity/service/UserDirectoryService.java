package com.costonomy.mp.identity.service;

import com.costonomy.mp.identity.domain.User;
import com.costonomy.mp.identity.domain.UserStatus;
import com.costonomy.mp.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The identity module's interface to other modules.
 *
 * <p>Other modules need to look users up and invite them by phone; they must not
 * reach into {@link UserRepository} to do it. That rule is what keeps the modular
 * monolith honest — a repository imported across a module boundary is the first
 * step to two modules disagreeing about what a user is.
 */
@Service
@RequiredArgsConstructor
public class UserDirectoryService {

    private final UserRepository userRepository;

    /**
     * Find a user by phone, creating a placeholder if they have never signed in.
     *
     * <p>This is how invitation works throughout the marketplace: a restaurant
     * owner adds a purchase manager by phone number before that person has ever
     * opened the app. The row is created unverified — {@code phoneVerifiedAt} stays
     * null until they complete an OTP — so an invitation cannot be mistaken for a
     * confirmed identity.
     */
    @Transactional
    public User findOrInviteByPhone(String rawPhone, String country, String name) {
        String phone = PhoneNumbers.normalize(rawPhone, country);

        return userRepository.findByPhone(phone).orElseGet(() -> {
            var user = new User();
            user.setPhone(phone);
            user.setName(name);
            user.setStatus(UserStatus.ACTIVE);
            try {
                return userRepository.saveAndFlush(user);
            } catch (DataIntegrityViolationException ex) {
                // Two invitations for the same new number raced; the unique index
                // on users.phone decided it.
                return userRepository.findByPhone(phone).orElseThrow(() -> ex);
            }
        });
    }

    @Transactional(readOnly = true)
    public Map<Long, User> byIds(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }
}
