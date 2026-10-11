package com.smartlib.library;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "libraries")
@Getter
@Setter
public class Library {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;
    private String slug;
    private String name;
    private String tagline;
    private String description;
    private String addressLine;
    private String city;
    private String state;
    private String postalCode;
    private String publicEmail;
    private String publicPhone;
    private String website;
    private String openingHours;
    private String timezone;
    private String imageKey;

    @Enumerated(EnumType.STRING)
    private LibraryStatus status;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (timezone == null) {
            timezone = "Asia/Kolkata";
        }
        if (status == null) {
            status = LibraryStatus.ACTIVE;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isActive() {
        return status == LibraryStatus.ACTIVE;
    }
}
