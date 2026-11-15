package com.stardataparadise.sdk;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lossless SQL value containers for JDBC getObject and setObject. */
public final class SdpValues {
    private SdpValues() { }

    public record Timestamp(long seconds, int nanos, int offsetMinutes, String zone) {
        public Timestamp {
            if (nanos < 0 || nanos > 999_999_999 || offsetMinutes < -840 || offsetMinutes > 840)
                throw new IllegalArgumentException("Invalid timestamp nanos or offset");
            if (zone != null && (zone.isEmpty() || zone.length() > 255))
                throw new IllegalArgumentException("Invalid timestamp zone");
        }

        public static Timestamp of(OffsetDateTime value) {
            var offset = value.getOffset().getTotalSeconds();
            if (offset % 60 != 0) throw new IllegalArgumentException("Timestamp requires minute offset");
            return new Timestamp(value.toEpochSecond(), value.getNano(), offset / 60, null);
        }

        public OffsetDateTime toOffsetDateTime() {
            return OffsetDateTime.ofInstant(Instant.ofEpochSecond(seconds, nanos),
                    ZoneOffset.ofTotalSeconds(offsetMinutes * 60));
        }
    }

    public record RecordValue(Map<String, Object> fields) {
        public RecordValue { fields = freezeFields(fields); }
        @Override public Map<String, Object> fields() { return freezeFields(fields); }
    }

    public record CollectionValue(List<Object> items) {
        public CollectionValue { items = freezeItems(items); }
        @Override public List<Object> items() { return freezeItems(items); }
    }

    private static Map<String, Object> freezeFields(Map<String, Object> source) {
        var output = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (key == null || key.isEmpty()) throw new IllegalArgumentException("Record field name is empty");
            output.put(key, copy(value));
        });
        return Collections.unmodifiableMap(output);
    }

    private static List<Object> freezeItems(List<Object> source) {
        var output = new ArrayList<Object>(source.size());
        source.forEach(value -> output.add(copy(value)));
        return Collections.unmodifiableList(output);
    }

    static Object copy(Object value) { return value instanceof byte[] bytes ? bytes.clone() : value; }
}
