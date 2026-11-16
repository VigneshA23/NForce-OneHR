package com.nforce.onehr.controller;

import com.nforce.onehr.dto.allocation.ClientRequest;
import com.nforce.onehr.dto.allocation.ClientResponse;
import com.nforce.onehr.dto.allocation.ProjectRequest;
import com.nforce.onehr.dto.allocation.ProjectResponse;
import com.nforce.onehr.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * US-B01/US-B02/US-B03. No DELETE mapping by design — a project is closed through {@link #update}
 * with status CLOSED, so its historical allocations keep a valid FK.
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService service;

    /** Open to Managers too: they get only the projects they own (scoped in {@link ProjectService#list}). */
    @GetMapping
    @PreAuthorize("hasAuthority('projects:VIEW:TEAM')")
    public List<ProjectResponse> list(Principal principal) {
        return service.list(principal.getName());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('projects:CREATE:ALL')")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@Valid @RequestBody ProjectRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('projects:EDIT:ALL')")
    public ProjectResponse update(@PathVariable UUID id, @Valid @RequestBody ProjectRequest req) {
        return service.update(id, req);
    }

    @GetMapping("/eligible-owners")
    @PreAuthorize("hasAuthority('projects:CREATE:ALL')")
    public List<ProjectService.OwnerOption> eligibleOwners() {
        return service.eligibleOwners();
    }

    @GetMapping("/clients")
    @PreAuthorize("hasAuthority('projects:VIEW:TEAM')")
    public List<ClientResponse> clients() {
        return service.listClients();
    }

    @PostMapping("/clients")
    @PreAuthorize("hasAuthority('projects:CREATE:ALL')")
    @ResponseStatus(HttpStatus.CREATED)
    public ClientResponse createClient(@Valid @RequestBody ClientRequest req) {
        return service.createClient(req);
    }
}
