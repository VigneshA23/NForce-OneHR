package com.nforce.onehr.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * Office-network check for punches: resolves the caller's real public IP and tests it against a
 * Location's configured office IPs/CIDRs. Record-only for now — callers store the result, nothing
 * is blocked.
 */
@Component
public class OfficeNetwork {

    // IpAddressMatcher resolves anything that isn't an IP literal via DNS, so only literals get through.
    private static final java.util.regex.Pattern IP_OR_CIDR = java.util.regex.Pattern.compile("[0-9A-Fa-f:.]+(/\\d{1,3})?");

    private final int trustedProxyHops;
    private final boolean devPublicIpLookup;
    private volatile String cachedPublicIp;
    private volatile long cachedAt;

    public OfficeNetwork(int trustedProxyHops) {
        this(trustedProxyHops, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OfficeNetwork(@Value("${app.attendance.trusted-proxy-hops:0}") int trustedProxyHops,
                         @Value("${app.attendance.dev-public-ip-lookup:false}") boolean devPublicIpLookup) {
        this.trustedProxyHops = trustedProxyHops;
        this.devPublicIpLookup = devPublicIpLookup;
    }

    /**
     * The client IP as seen by the outermost proxy we trust. Each trusted proxy appends the address
     * it received the request from, so with N trusted proxies the client is the N-th entry from the
     * right of X-Forwarded-For. Anything further left is client-supplied and spoofable, so it is
     * never read. With 0 hops the header is ignored entirely.
     */
    public String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        String ip;
        if (trustedProxyHops <= 0 || xff == null || xff.isBlank()) {
            ip = request.getRemoteAddr();
        } else {
            String[] hops = xff.split(",");
            ip = hops[Math.max(0, hops.length - trustedProxyHops)].trim();
        }
        return devPublicIpLookup && isLoopback(ip) ? machinePublicIp(ip) : ip;
    }

    /** How the client IP was derived — shown on an off-network refusal so a proxy mismatch is visible. */
    public String networkTrace(HttpServletRequest request) {
        return "remote=" + request.getRemoteAddr()
                + ", X-Forwarded-For=" + request.getHeader("X-Forwarded-For")
                + ", X-Real-IP=" + request.getHeader("X-Real-IP")
                + ", trusted hops=" + trustedProxyHops;
    }

    private static boolean isLoopback(String ip) {
        return ip != null && (ip.startsWith("127.") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1"));
    }

    /**
     * Local dev only (app.attendance.dev-public-ip-lookup): the backend and browser share this
     * machine, so every request arrives from loopback. Resolve the machine's real public IP so
     * switching Wi-Fi / mobile hotspot behaves like it will in production.
     * ponytail: calls api.ipify.org with a 15s cache; never enable in deployed environments.
     */
    private String machinePublicIp(String fallback) {
        if (cachedPublicIp != null && System.currentTimeMillis() - cachedAt < 15_000) return cachedPublicIp;
        try {
            var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://api.ipify.org"))
                    .timeout(java.time.Duration.ofSeconds(3)).build();
            String ip = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body().trim();
            if (!IP_OR_CIDR.matcher(ip).matches()) return fallback;
            cachedPublicIp = ip;
            cachedAt = System.currentTimeMillis();
            return ip;
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * True/false when the office's network is configured; null when it isn't (or the IP is
     * unknown), so an unconfigured office is "not judged" rather than "not in office".
     */
    public static Boolean isInOffice(String ip, String officeCidrs) {
        if (ip == null || !IP_OR_CIDR.matcher(ip).matches() || officeCidrs == null || officeCidrs.isBlank()) {
            return null;
        }
        for (String cidr : officeCidrs.split(",")) {
            String c = cidr.trim();
            if (!IP_OR_CIDR.matcher(c).matches()) continue;
            try {
                if (new IpAddressMatcher(c).matches(ip)) return true;
            } catch (IllegalArgumentException ignored) {
                // A malformed entry or IPv4/IPv6 mix just doesn't match; validation happens on save.
            }
        }
        return false;
    }

    /** Throws with a readable message if any entry isn't a valid IP or CIDR. Returns the trimmed list. */
    public static String normalizeCidrs(String officeCidrs) {
        if (officeCidrs == null || officeCidrs.isBlank()) return null;
        StringBuilder out = new StringBuilder();
        for (String cidr : officeCidrs.split("[,\\s]+")) {
            if (cidr.isEmpty()) continue;
            try {
                if (!IP_OR_CIDR.matcher(cidr).matches()) throw new IllegalArgumentException();
                new IpAddressMatcher(cidr);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'" + cidr + "' is not a valid IP address or CIDR range (e.g. 203.0.113.10 or 203.0.113.0/24)");
            }
            if (out.length() > 0) out.append(", ");
            out.append(cidr);
        }
        return out.length() == 0 ? null : out.toString();
    }
}
