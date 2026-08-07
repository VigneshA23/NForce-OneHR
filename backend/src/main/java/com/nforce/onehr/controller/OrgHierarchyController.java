package com.nforce.onehr.controller;

import com.nforce.onehr.dto.OrgContextDto;
import com.nforce.onehr.dto.OrgContextDto.PersonCard;
import com.nforce.onehr.service.OrgHierarchyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/org-hierarchy")
@RequiredArgsConstructor
public class OrgHierarchyController {

    private final OrgHierarchyService orgHierarchyService;

    /** All roles can view — read-only, no write capability. */
    @GetMapping("/context/{userId}")
    public OrgContextDto getContext(@PathVariable UUID userId) {
        return orgHierarchyService.getContext(userId);
    }

    @GetMapping("/roots")
    public List<PersonCard> getRoots() {
        return orgHierarchyService.getRoots();
    }
}
