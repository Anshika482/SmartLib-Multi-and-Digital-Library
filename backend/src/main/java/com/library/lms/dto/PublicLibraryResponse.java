package com.library.lms.dto;

/**
 * A library as an unauthenticated visitor sees it.
 *
 * <p>The name of the institution and how many titles it has catalogued. The
 * entity holds nothing else but an id and a creation time, and neither is any
 * of a visitor's business.</p>
 *
 * <p>The id is here because registration needs it: a visitor picks a library
 * from this list and the id is what identifies their choice. It opens nothing
 * on its own - every endpoint that takes a library id derives the caller's
 * library from their account instead, and the one that takes this id
 * (registration) creates a member or an application and nothing else.
 *
 * @param titleCount a real count from the catalogue, never an estimate
 */
public record PublicLibraryResponse(Long id, String name, long titleCount) {
}
