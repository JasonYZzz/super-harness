package io.superharness.api;

import io.superharness.persistence.DocumentLibraries;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
class DocumentApi {
    private final DocumentLibraries books;

    DocumentApi(DocumentLibraries books) {
        this.books = books;
    }

    record Library(@NotBlank @Size(max = 120) String name) {}

    record Document(
            @NotBlank @Size(max = 180) String name, @NotBlank @Size(max = 65536) String content) {}

    record Selection(UUID libraryId) {}

    @GetMapping("/libraries")
    Object list(@AuthenticationPrincipal Jwt jwt) {
        return books.list(Identity.principal(jwt));
    }

    @PostMapping("/libraries")
    Object create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Library body) {
        return Map.of("id", books.create(Identity.principal(jwt), body.name()));
    }

    @DeleteMapping("/libraries/{id}")
    void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        books.remove(Identity.principal(jwt), id);
    }

    @GetMapping("/libraries/{id}/documents")
    Object documents(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return books.documents(Identity.principal(jwt), id);
    }

    @PostMapping("/libraries/{id}/documents")
    Object add(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody Document body) {
        return Map.of("id", books.add(Identity.principal(jwt), id, body.name(), body.content()));
    }

    @DeleteMapping("/libraries/{id}/documents/{doc}")
    void removeDoc(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID doc) {
        books.removeDoc(Identity.principal(jwt), id, doc);
    }

    @GetMapping("/libraries/{id}/search")
    Object search(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam String q) {
        return books.search(Identity.principal(jwt), id, q);
    }

    @PutMapping("/conversations/{id}/library")
    void attach(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody Selection body) {
        books.attach(Identity.principal(jwt), id, body.libraryId());
    }
}
