package com.smartlib.security;

import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smartlib.library.LibraryRepository;
import com.smartlib.user.StaffAssignment;
import com.smartlib.user.StaffAssignmentRepository;
import com.smartlib.user.User;
import com.smartlib.user.UserRepository;

/**
 * Loads the caller's current role/status/library from the database with a short cache. Any change to an account
 * (suspension, approval, password change) evicts the entry so it takes effect immediately.
 */
@Service
public class PrincipalService {

    private final UserRepository users;
    private final StaffAssignmentRepository staff;
    private final LibraryRepository libraries;
    private final Cache<Long, Optional<SmartLibPrincipal>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(20_000)
            .build();

    public PrincipalService(UserRepository users, StaffAssignmentRepository staff, LibraryRepository libraries) {
        this.users = users;
        this.staff = staff;
        this.libraries = libraries;
    }

    public Optional<SmartLibPrincipal> load(long userId) {
        return cache.get(userId, this::fetch);
    }

    public void evict(long userId) {
        cache.invalidate(userId);
    }

    public void evictAll() {
        cache.invalidateAll();
    }

    public SmartLibPrincipal toPrincipal(User user) {
        StaffAssignment assignment = staff.findByUserId(user.getId()).orElse(null);
        Long libraryId = assignment == null ? null : assignment.getLibraryId();
        String libraryName = libraryId == null ? null : libraries.findById(libraryId).map(l -> l.getName()).orElse(null);
        return new SmartLibPrincipal(user.getId(), user.getEmail(), user.getFullName(), user.getRole(), user.getStatus(),
                user.isEmailVerified(), libraryId, libraryName,
                assignment == null ? null : assignment.getApprovalStatus(), user.getTokenVersion());
    }

    private Optional<SmartLibPrincipal> fetch(Long userId) {
        return users.findById(userId).map(this::toPrincipal);
    }
}
