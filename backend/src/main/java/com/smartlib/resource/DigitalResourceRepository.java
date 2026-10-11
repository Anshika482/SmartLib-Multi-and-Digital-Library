package com.smartlib.resource;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DigitalResourceRepository extends JpaRepository<DigitalResource, Long> {

    Optional<DigitalResource> findByIdAndLibraryId(Long id, Long libraryId);
}
