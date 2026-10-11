package com.smartlib.catalogue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.Setter;

/** Bibliographic record shared by every library that holds the title. Availability lives on holdings/copies. */
@Entity
@Table(name = "books")
@Getter
@Setter
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String isbn13;
    private String isbn10;
    private String title;
    private String subtitle;
    private String description;
    private String publisher;
    private Integer publishedYear;
    private String language;
    private Integer pageCount;
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    private CoverSource coverSource = CoverSource.UNCHECKED;

    private String coverKey;
    private String coverExternalRef;
    private Instant coverCheckedAt;
    private String searchText;
    private Long createdByLibraryId;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "book_authors", joinColumns = @JoinColumn(name = "book_id"), inverseJoinColumns = @JoinColumn(name = "author_id"))
    @OrderColumn(name = "position")
    private List<Author> authors = new ArrayList<>();

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
