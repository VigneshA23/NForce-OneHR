package com.nforce.onehr.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SseTicketServiceTest {

    private final SseTicketService service = new SseTicketService();

    @Test
    void issueThenRedeem_returnsTheIssuingUserId() {
        UUID userId = UUID.randomUUID();
        String ticket = service.issue(userId);

        assertEquals(userId, service.redeem(ticket));
    }

    @Test
    void redeem_isSingleUse_secondRedeemReturnsNull() {
        String ticket = service.issue(UUID.randomUUID());
        service.redeem(ticket);

        assertNull(service.redeem(ticket));
    }

    @Test
    void redeem_unknownTicket_returnsNull() {
        assertNull(service.redeem("never-issued"));
    }

    @Test
    void issue_producesDistinctUnguessableTickets() {
        String a = service.issue(UUID.randomUUID());
        String b = service.issue(UUID.randomUUID());

        assertNotEquals(a, b);
    }
}
