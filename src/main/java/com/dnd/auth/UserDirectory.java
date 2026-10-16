package com.dnd.auth;

import java.util.Optional;

/** Where accounts are looked up and saved. */
public interface UserDirectory {
    Optional<User> findByEmail(String email);

    Optional<User> findByUsername(String username);

    /** Finds by email OR username - used at login time since either may be entered. */
    default Optional<User> findByEmailOrUsername(String identifier) {
        Optional<User> byName = findByUsername(identifier);
        return byName.isPresent() ? byName : findByEmail(identifier);
    }

    /** Inserts a new user or replaces the existing one with the same username. */
    void save(User user);
}
