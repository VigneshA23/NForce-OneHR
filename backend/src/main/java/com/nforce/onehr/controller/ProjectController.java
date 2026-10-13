package com.nforce.onehr.controller;

import com.nforce.onehr.dto.allocation.CreateProjectRequest;
import com.nforce.onehr.dto.allocation.ProjectResponse;
import com.nforce.onehr.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HR_ADMIN', 'SUPER_ADMIN')")
public class ProjectController {

    private final ProjectService service;

    @GetMapping
    public List<ProjectResponse> list() {
        return service.listActive();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest req) {
        return service.create(req);
    }
}
