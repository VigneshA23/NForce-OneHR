package com.nforce.onehr.controller;

import com.nforce.onehr.dto.education.EmployeeEducationRequest;
import com.nforce.onehr.dto.education.EmployeeEducationResponse;
import com.nforce.onehr.service.EmployeeEducationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/profile/education")
@RequiredArgsConstructor
public class EmployeeEducationController {

    private final EmployeeEducationService service;

    @GetMapping
    public List<EmployeeEducationResponse> list(Authentication auth) {
        return service.listMine(auth.getName());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeEducationResponse create(@Valid @RequestBody EmployeeEducationRequest req, Authentication auth) {
        return service.create(auth.getName(), req);
    }

    @PutMapping("/{id}")
    public EmployeeEducationResponse update(@PathVariable UUID id,
                                             @Valid @RequestBody EmployeeEducationRequest req,
                                             Authentication auth) {
        return service.update(auth.getName(), id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id, Authentication auth) {
        service.delete(auth.getName(), id);
    }
}
