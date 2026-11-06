package com.nforce.onehr.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class OfficeNetworkTest {

    @Test
    void inOfficeMatchesConfiguredIpsAndRanges() {
        String cidrs = "203.0.113.10, 198.51.100.0/28";
        assertEquals(Boolean.TRUE, OfficeNetwork.isInOffice("203.0.113.10", cidrs));
        assertEquals(Boolean.TRUE, OfficeNetwork.isInOffice("198.51.100.7", cidrs));
        assertEquals(Boolean.FALSE, OfficeNetwork.isInOffice("198.51.100.20", cidrs));
        assertEquals(Boolean.FALSE, OfficeNetwork.isInOffice("2001:db8::1", cidrs)); // IPv6 vs IPv4 list
        assertNull(OfficeNetwork.isInOffice("203.0.113.10", null));   // office not configured
        assertNull(OfficeNetwork.isInOffice("203.0.113.10", "  "));
        assertNull(OfficeNetwork.isInOffice("evil.example.com", cidrs)); // never DNS-resolved
    }

    @Test
    void normalizeValidatesAndTidies() {
        assertEquals("203.0.113.10, 198.51.100.0/28", OfficeNetwork.normalizeCidrs(" 203.0.113.10 ,198.51.100.0/28 "));
        assertNull(OfficeNetwork.normalizeCidrs(""));
        assertThrows(IllegalArgumentException.class, () -> OfficeNetwork.normalizeCidrs("office.example.com"));
        assertThrows(IllegalArgumentException.class, () -> OfficeNetwork.normalizeCidrs("300.1.1.1"));
    }

    @Test
    void clientIpIgnoresSpoofableHeaderEntries() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.5");
        req.addHeader("X-Forwarded-For", "203.0.113.10, 49.36.1.2, 76.76.21.9"); // spoofed, client, vercel

        assertEquals("10.0.0.5", new OfficeNetwork(0).clientIp(req));     // header ignored
        assertEquals("76.76.21.9", new OfficeNetwork(1).clientIp(req));
        assertEquals("49.36.1.2", new OfficeNetwork(2).clientIp(req));     // never the spoofed first entry
    }
}
