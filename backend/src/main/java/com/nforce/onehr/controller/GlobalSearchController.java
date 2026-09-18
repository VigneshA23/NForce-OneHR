package com.nforce.onehr.controller;

import com.nforce.onehr.dto.search.SearchPageResponse;
import com.nforce.onehr.dto.search.SearchPreviewResponse;
import com.nforce.onehr.service.search.GlobalSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * Global search — no {@code @PreAuthorize} beyond the default "any authenticated user"
 * (see SecurityConfig): every module's own authorization is enforced inside its
 * {@link com.nforce.onehr.service.search.SearchProvider}, exactly matching that module's
 * existing endpoints, so this controller has nothing extra to guard.
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class GlobalSearchController {

    private final GlobalSearchService searchService;

    /** Header search dropdown — top matches per module. */
    @GetMapping
    public SearchPreviewResponse preview(@RequestParam String q, Principal principal) {
        return searchService.preview(principal.getName(), q);
    }

    /** Dedicated results page — one module's independently-paginated results. */
    @GetMapping("/{module}")
    public SearchPageResponse modulePage(
            @PathVariable String module,
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Principal principal) {
        return searchService.modulePage(principal.getName(), module, q, page, size);
    }
}
