package com.nforce.onehr.controller;

import com.nforce.onehr.dto.certificates.EmployeeCertificateRequest;
import com.nforce.onehr.dto.certificates.EmployeeCertificateResponse;
import com.nforce.onehr.service.EmployeeCertificateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/profile/certificates")
@RequiredArgsConstructor
public class EmployeeCertificateController {

    private final EmployeeCertificateService service;

    @GetMapping
    public List<EmployeeCertificateResponse> list(Authentication auth) {
        return service.listMine(auth.getName());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeCertificateResponse create(@Valid @RequestBody EmployeeCertificateRequest req, Authentication auth) {
        return service.create(auth.getName(), req);
    }

    @PutMapping("/{id}")
    public EmployeeCertificateResponse update(@PathVariable UUID id,
                                               @Valid @RequestBody EmployeeCertificateRequest req,
                                               Authentication auth) {
        return service.update(auth.getName(), id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id, Authentication auth) {
        service.delete(auth.getName(), id);
    }
}
