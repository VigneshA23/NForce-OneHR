package com.nforce.onehr.controller;

import com.nforce.onehr.dto.KudosResponse;
import com.nforce.onehr.dto.SendKudosRequest;
import com.nforce.onehr.service.KudosService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/** "Appreciate your lead" / peer kudos (ONEHR-73), reachable from the My Team: Peers view. */
@RestController
@RequestMapping("/api/kudos")
@RequiredArgsConstructor
public class KudosController {

    private final KudosService kudosService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KudosResponse send(@Valid @RequestBody SendKudosRequest req, Principal principal) {
        return kudosService.send(req, principal.getName());
    }

    /** Kudos the caller has received — could back a "recognition" widget on the dashboard later. */
    @GetMapping("/received")
    public List<KudosResponse> received(Principal principal) {
        return kudosService.listReceived(principal.getName());
    }

    @GetMapping("/sent")
    public List<KudosResponse> sent(Principal principal) {
        return kudosService.listSent(principal.getName());
    }
}
