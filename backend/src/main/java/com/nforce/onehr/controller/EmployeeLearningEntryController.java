package com.nforce.onehr.controller;

import com.nforce.onehr.dto.learning.EmployeeLearningEntryRequest;
import com.nforce.onehr.dto.learning.EmployeeLearningEntryResponse;
import com.nforce.onehr.service.EmployeeLearningEntryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// "What I learned" entries — self-service only for now (ONEHR profile growth tracking, phase 1).
// Manager/lead visibility into this is an explicit later phase, not implemented here.
@RestController
@RequestMapping("/api/profile/learning")
@RequiredArgsConstructor
public class EmployeeLearningEntryController {

    private final EmployeeLearningEntryService service;

    @GetMapping
    public List<EmployeeLearningEntryResponse> list(Authentication auth) {
        return service.listMine(auth.getName());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeLearningEntryResponse create(@Valid @RequestBody EmployeeLearningEntryRequest req, Authentication auth) {
        return service.create(auth.getName(), req);
    }

    @PutMapping("/{id}")
    public EmployeeLearningEntryResponse update(@PathVariable UUID id,
                                                 @Valid @RequestBody EmployeeLearningEntryRequest req,
                                                 Authentication auth) {
        return service.update(auth.getName(), id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id, Authentication auth) {
        service.delete(auth.getName(), id);
    }
}
