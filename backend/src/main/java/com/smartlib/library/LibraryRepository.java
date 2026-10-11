package com.smartlib.library;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LibraryRepository extends JpaRepository<Library, Long> {

    Optional<Library> findBySlug(String slug);

    boolean existsByCode(String code);

    boolean existsBySlug(String slug);

    List<Library> findByStatusOrderByNameAsc(LibraryStatus status);

    List<Library> findAllByOrderByNameAsc();
}
