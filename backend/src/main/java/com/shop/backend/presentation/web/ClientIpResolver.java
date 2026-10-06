package com.shop.backend.presentation.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Client IP from X-Forwarded-For, taking the entry {@code trustedProxyHops} positions from the right of all
 * header lines read as one comma-separated list: the proxy in front of us (Cloud Run) appends the real client IP,
 * while entries further left are client-supplied and spoofable, so the leftmost entry is never trusted.
 *
 * <p>The chosen value must be an IP literal (parsed by hand, never resolved via DNS), otherwise the socket address
 * is used. IPv4-mapped IPv6 becomes the IPv4 string and any other IPv6 becomes its /64 prefix, so one household
 * cannot rotate addresses inside a /64 to escape the limit or create unlimited keys.
 */
@Component
public class ClientIpResolver {

    private static final int MAX_RAW_LENGTH = 64;

    private final int trustedProxyHops;

    public ClientIpResolver(@Value("${rate-limit.trusted-proxy-hops}") int trustedProxyHops) {
        this.trustedProxyHops = trustedProxyHops;
    }

    public String resolve(HttpServletRequest request) {
        String forwarded = fromForwardedFor(request);
        return forwarded != null ? forwarded : fromRemoteAddr(request.getRemoteAddr());
    }

    private String fromForwardedFor(HttpServletRequest request) {
        Enumeration<String> lines = request.getHeaders("X-Forwarded-For");
        if (trustedProxyHops < 1 || lines == null) {
            return null;
        }
        List<String> entries = new ArrayList<>();
        for (String line : Collections.list(lines)) {
            if (line != null && !line.isBlank()) {
                // -1 keeps trailing empty entries so positions stay intact
                entries.addAll(List.of(line.split(",", -1)));
            }
        }
        if (entries.size() < trustedProxyHops) {
            return null;
        }
        String raw = entries.get(entries.size() - trustedProxyHops).trim();
        return raw.length() > MAX_RAW_LENGTH ? null : normalize(raw);
    }

    private static String fromRemoteAddr(String remote) {
        if (remote == null) {
            return "unknown";
        }
        int zone = remote.indexOf('%');
        String addr = zone >= 0 ? remote.substring(0, zone) : remote;
        String normalized = normalize(addr);
        if (normalized != null) {
            return normalized;
        }
        return remote.length() > MAX_RAW_LENGTH ? remote.substring(0, MAX_RAW_LENGTH) : remote;
    }

    /** Canonical key for an IP literal, or null if it is not one. */
    static String normalize(String s) {
        if (s.indexOf(':') < 0) {
            int[] v4 = parseIpv4(s);
            return v4 == null ? null : v4[0] + "." + v4[1] + "." + v4[2] + "." + v4[3];
        }
        int[] g = parseIpv6(s);
        if (g == null) {
            return null;
        }
        boolean mapped = g[0] == 0 && g[1] == 0 && g[2] == 0 && g[3] == 0 && g[4] == 0 && g[5] == 0xffff;
        if (mapped) {
            return (g[6] >> 8) + "." + (g[6] & 0xff) + "." + (g[7] >> 8) + "." + (g[7] & 0xff);
        }
        return String.format("%x:%x:%x:%x::/64", g[0], g[1], g[2], g[3]);
    }

    private static int[] parseIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) {
                return null;
            }
            int v = 0;
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                v = v * 10 + (c - '0');
            }
            if (v > 255) {
                return null;
            }
            out[i] = v;
        }
        return out;
    }

    /** Eight 16-bit groups, or null. Hand-written so no hostname can ever reach a DNS lookup. */
    private static int[] parseIpv6(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c == ':' || c == '.' || Character.digit(c, 16) >= 0 && c < 128)) {
                return null;
            }
        }
        int dbl = s.indexOf("::");
        if (dbl != s.lastIndexOf("::")) {
            return null;
        }
        List<Integer> head = groups(dbl < 0 ? s : s.substring(0, dbl), dbl < 0);
        List<Integer> tail = dbl < 0 ? List.of() : groups(s.substring(dbl + 2), true);
        if (head == null || tail == null) {
            return null;
        }
        int total = head.size() + tail.size();
        if (dbl < 0 ? total != 8 : total > 7) {
            return null;
        }
        int[] out = new int[8];
        for (int i = 0; i < head.size(); i++) {
            out[i] = head.get(i);
        }
        for (int i = 0; i < tail.size(); i++) {
            out[8 - tail.size() + i] = tail.get(i);
        }
        return out;
    }

    /** Colon-separated hex groups; an IPv4 dotted tail is allowed only as the last token when {@code last}. */
    private static List<Integer> groups(String part, boolean last) {
        List<Integer> out = new ArrayList<>();
        if (part.isEmpty()) {
            return out;
        }
        String[] tokens = part.split(":", -1);
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.indexOf('.') >= 0) {
                int[] v4 = i == tokens.length - 1 && last ? parseIpv4(t) : null;
                if (v4 == null) {
                    return null;
                }
                out.add(v4[0] << 8 | v4[1]);
                out.add(v4[2] << 8 | v4[3]);
            } else {
                if (t.isEmpty() || t.length() > 4) {
                    return null;
                }
                out.add(Integer.parseInt(t, 16));
            }
        }
        return out;
    }
}
