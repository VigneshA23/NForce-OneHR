package com.nforce.onehr.controller;

import com.nforce.onehr.dto.skills.EmployeeSkillRequest;
import com.nforce.onehr.dto.skills.EmployeeSkillResponse;
import com.nforce.onehr.service.EmployeeSkillService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/profile/skills")
@RequiredArgsConstructor
public class EmployeeSkillController {

    private final EmployeeSkillService service;

    @GetMapping
    public List<EmployeeSkillResponse> list(Authentication auth) {
        return service.listMine(auth.getName());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeSkillResponse create(@Valid @RequestBody EmployeeSkillRequest req, Authentication auth) {
        return service.create(auth.getName(), req);
    }

    @PutMapping("/{id}")
    public EmployeeSkillResponse update(@PathVariable UUID id,
                                         @Valid @RequestBody EmployeeSkillRequest req,
                                         Authentication auth) {
        return service.update(auth.getName(), id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id, Authentication auth) {
        service.delete(auth.getName(), id);
    }
}
