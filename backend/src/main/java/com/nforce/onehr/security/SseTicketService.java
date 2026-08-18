package com.nforce.onehr.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-use, short-lived tickets for opening the SSE connection at GET /api/auth/events. Native
 * browser EventSource can't set an Authorization header, so the JWT itself must never appear in
 * that URL/query string — the client instead calls POST /api/auth/events/ticket (normal Bearer
 * auth) to mint one of these opaque, unguessable tickets, then opens the SSE connection with it.
 * Each ticket is redeemed at most once and expires quickly either way.
 */
@Component
public class SseTicketService {

    private static final long TTL_MS = 30_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Ticket(UUID userId, long expiresAtMs) {}

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public String issue(UUID userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(ticket, new Ticket(userId, System.currentTimeMillis() + TTL_MS));
        return ticket;
    }

    /** Redeems (and always removes) a ticket. Returns null if it's unknown, already used, or expired. */
    public UUID redeem(String ticket) {
        Ticket t = tickets.remove(ticket);
        if (t == null || System.currentTimeMillis() > t.expiresAtMs()) {
            return null;
        }
        return t.userId();
    }
}
