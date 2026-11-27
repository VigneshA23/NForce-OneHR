package com.nforce.onehr.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * LV-07: the dates a leave request is charged for — its working days and, under the sandwich rule, the weekly
 * offs / holidays counted with them — stored on the request ({@code leave_requests.day_counts}). Balances split
 * per leave year and apply carry-forward cut-offs from these dates, since a range may now include days that
 * aren't counted. Requests made before V323 have none and every date in their range counted.
 */
public record LeaveDayCounts(NavigableMap<LocalDate, BigDecimal> working, NavigableMap<LocalDate, BigDecimal> sandwiched) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public LeaveDayCounts {
        working = Collections.unmodifiableNavigableMap(new TreeMap<>(working));
        sandwiched = Collections.unmodifiableNavigableMap(new TreeMap<>(sandwiched));
    }

    public BigDecimal workingTotal() {
        return working.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal sandwichedTotal() {
        return sandwiched.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal total() {
        return workingTotal().add(sandwichedTotal());
    }

    /** Every charged date with its days. */
    public NavigableMap<LocalDate, BigDecimal> all() {
        NavigableMap<LocalDate, BigDecimal> out = new TreeMap<>(working);
        sandwiched.forEach((d, v) -> out.merge(d, v, BigDecimal::add));
        return out;
    }

    /** Days per leave year. */
    public Map<Integer, BigDecimal> byYear() {
        Map<Integer, BigDecimal> out = new TreeMap<>();
        all().forEach((d, v) -> out.merge(LeaveBalanceService.leaveYearOf(d), v, BigDecimal::add));
        return out;
    }

    /** Days in {@code year} dated on/before {@code cutoff} (null = all of that year). */
    public BigDecimal onOrBefore(int year, LocalDate cutoff) {
        return all().entrySet().stream()
                .filter(e -> LeaveBalanceService.leaveYearOf(e.getKey()) == year)
                .filter(e -> cutoff == null || !e.getKey().isAfter(cutoff))
                .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public String toJson() {
        Map<String, Map<String, BigDecimal>> out = new LinkedHashMap<>();
        out.put("working", keyed(working));
        out.put("sandwiched", keyed(sandwiched));
        try {
            return JSON.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Null for null/blank (a request made before V323). */
    public static LeaveDayCounts parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, Map<String, BigDecimal>> in = JSON.readValue(json, new TypeReference<>() {});
            return new LeaveDayCounts(dated(in.get("working")), dated(in.get("sandwiched")));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable leave day counts: " + json, e);
        }
    }

    private static Map<String, BigDecimal> keyed(Map<LocalDate, BigDecimal> m) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        m.forEach((d, v) -> out.put(d.toString(), v.stripTrailingZeros()));
        return out;
    }

    private static NavigableMap<LocalDate, BigDecimal> dated(Map<String, BigDecimal> m) {
        NavigableMap<LocalDate, BigDecimal> out = new TreeMap<>();
        if (m != null) m.forEach((d, v) -> out.put(LocalDate.parse(d), v));
        return out;
    }
}
