package com.huawei.opsfactory.businessintelligence.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared date parsing utilities for BI services.
 *
 * <p>Keeps a bounded, thread-safe LRU cache of parsed strings to avoid repeated
 * parsing of the same values across large datasets. Supports ISO, common datetime
 * patterns, plain dates, and Excel serial numbers.
 */
final class BiDateUtils {

    private BiDateUtils() {}

    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
        DateTimeFormatter.ISO_DATE_TIME,
        DateTimeFormatter.ofPattern("M/d/yyyy H:mm"),
        DateTimeFormatter.ofPattern("M/d/yyyy H:mm:ss"),
        DateTimeFormatter.ofPattern("M/d/yyyy h:mm:ss a", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    );

    private static final int CACHE_CAPACITY = 10_000;
    private static final Map<String, LocalDateTime> DATE_CACHE = Collections.synchronizedMap(
        new LinkedHashMap<String, LocalDateTime>(CACHE_CAPACITY, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, LocalDateTime> eldest) {
                return size() > CACHE_CAPACITY;
            }
        }
    );

    static LocalDateTime parseDate(String value) {
        String normalized = clean(value);
        if (normalized.isBlank()) {
            return null;
        }
        synchronized (DATE_CACHE) {
            if (DATE_CACHE.containsKey(normalized)) {
                return DATE_CACHE.get(normalized);
            }
        }
        LocalDateTime result = parseDateInternal(normalized);
        if (result != null) {
            synchronized (DATE_CACHE) {
                DATE_CACHE.put(normalized, result);
            }
        } else {
            synchronized (DATE_CACHE) {
                DATE_CACHE.put(normalized, null);
            }
        }
        return result;
    }

    private static LocalDateTime parseDateInternal(String normalized) {
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        try {
            return LocalDate.parse(normalized).atStartOfDay();
        } catch (DateTimeParseException ignored) {
        }
        try {
            double excelDate = Double.parseDouble(normalized);
            return LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(Math.round((excelDate - 25569) * 86400000L)),
                ZoneOffset.UTC);
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
