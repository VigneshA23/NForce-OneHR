package com.nforce.onehr.controller;

import com.nforce.onehr.dto.FenceModuleRequest;
import com.nforce.onehr.dto.ModuleFenceResponse;
import com.nforce.onehr.service.AccountOwnerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * Account Owner only — not gated by hasAuthority(...), since "Account Owner" is a standalone
 * flag on the actor, independent of the role_permissions engine (see AccountOwnerService's own
 * Javadoc for why). {@code isAuthenticated()} here is just the outer floor; the real check is
 * the service's own {@code requireAccountOwner}.
 */
@RestController
@RequestMapping("/api/account-owner/fences")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AccountOwnerController {

    private final AccountOwnerService accountOwnerService;

    @GetMapping
    public List<ModuleFenceResponse> list(Principal principal) {
        return accountOwnerService.listFences(principal.getName()).stream()
                .map(ModuleFenceResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModuleFenceResponse fence(@Valid @RequestBody FenceModuleRequest req, Principal principal) {
        return ModuleFenceResponse.from(
                accountOwnerService.fenceModule(req.getModule(), req.getAction(), principal.getName()));
    }

    @DeleteMapping("/{moduleCode}/{action}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfence(@PathVariable String moduleCode, @PathVariable String action, Principal principal) {
        accountOwnerService.unfenceModule(moduleCode, action, principal.getName());
    }
}
