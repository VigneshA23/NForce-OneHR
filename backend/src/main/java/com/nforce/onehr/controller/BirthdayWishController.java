package com.nforce.onehr.controller;

import com.nforce.onehr.dto.BirthdayWishResponse;
import com.nforce.onehr.dto.SendBirthdayWishRequest;
import com.nforce.onehr.service.BirthdayWishService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/** "Send Wishes" on the dashboard's Birthdays widget — see BirthdayWishService for why this is a
 * separate feature from Kudos rather than reusing it. */
@RestController
@RequestMapping("/api/birthday-wishes")
@RequiredArgsConstructor
public class BirthdayWishController {

    private final BirthdayWishService birthdayWishService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BirthdayWishResponse send(@Valid @RequestBody SendBirthdayWishRequest req, Principal principal) {
        return birthdayWishService.send(req, principal.getName());
    }

    /** The caller's own wishes received today — backs the birthday celebration card's message
     * list and "N wishes received" count. */
    @GetMapping("/received-today")
    public List<BirthdayWishResponse> receivedToday(Principal principal) {
        return birthdayWishService.listReceivedToday(principal.getName());
    }
}
