package com.stardataparadise.sdk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLType;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.regex.Pattern;

final class ValueCodec {
    private static final int BLOB_LIMIT = 512 * 1024;
    private static final int COMPOUND_LIMIT = 64 * 1024;
    private static final int CONTAINER_MEMBERS = 128;
    private static final Pattern COEFFICIENT = Pattern.compile("0|-?[1-9][0-9]{0,37}");

    private ValueCodec() { }

    record Binding(JsonObject declaration, JsonObject value) { }

    static final class Budget {
        int nodes;
        void visit(int depth) throws SQLException {
            if (depth > 8 || ++nodes > 2048) throw Wire.invalid("Compound value exceeds its limits");
        }
    }

    static Binding binding(Object value, int jdbcType, Integer scale) throws SQLException {
        try {
            int type = jdbcType == Types.NULL ? infer(value) : jdbcType;
            String name = sqlType(type);
            Integer decimalScale = null;
            if (name.equals("DECIMAL")) {
                BigDecimal decimal = value == null ? null : decimal(value);
                decimalScale = scale != null ? scale : decimal == null ? 0 : Math.max(0, decimal.scale());
                if (decimalScale < 0 || decimalScale > 38) throw Wire.invalid("DECIMAL scale must be 0-38");
                if (decimal != null) value = resize(decimal, decimalScale);
            } else if (value != null) {
                value = convertInput(value, name);
            }
            var declaration = decimalScale == null ? Wire.object("type", name)
                    : Wire.object("type", name, "precision", 38, "scale", decimalScale);
            var encoded = encode(value, 0, new Budget());
            if ((name.equals("RECORD") || name.equals("COLLECTION"))
                    && encoded.toString().getBytes(StandardCharsets.UTF_8).length > COMPOUND_LIMIT)
                throw Wire.invalid("Compound value exceeds its byte limit");
            return new Binding(declaration, encoded);
        } catch (ArithmeticException | IllegalArgumentException error) {
            throw new SQLException("Parameter value cannot be represented by the declared SQL type", "22003");
        }
    }

    private static BigDecimal resize(BigDecimal value, int scale) throws SQLException {
        long scaledPrecision = (long) value.precision() + scale - value.scale();
        if (scaledPrecision < 0 || value.signum() == 0) return BigDecimal.ZERO.setScale(scale);
        if (scaledPrecision > 38) throw Wire.invalid("DECIMAL precision exceeds 38");
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    static int typeNumber(Object value) throws SQLException {
        if (value instanceof SQLType type && type.getVendorTypeNumber() != null) return type.getVendorTypeNumber();
        if (value instanceof Integer number) return number;
        throw Wire.invalid("Invalid JDBC type");
    }

    static byte[] binary(InputStream input, Long length) throws SQLException {
        if (input == null) return null;
        if (length != null && (length < 0 || length > BLOB_LIMIT)) throw Wire.invalid("Invalid binary length");
        try {
            int limit = length == null ? BLOB_LIMIT + 1 : length.intValue();
            byte[] bytes = input.readNBytes(limit);
            if (length == null ? bytes.length > BLOB_LIMIT : bytes.length != limit)
                throw Wire.invalid("Binary input length is not valid");
            return bytes;
        } catch (IOException error) {
            throw new SQLException("Binary input could not be read", "22000");
        }
    }

    private static Object convertInput(Object value, String type) throws SQLException {
        return switch (type) {
            case "BIGINT" -> integer(value);
            case "BOOLEAN" -> value instanceof Boolean ? value : fail("Expected a Boolean");
            case "TEXT" -> value instanceof String ? value : fail("Expected text");
            case "BLOB" -> value instanceof byte[] ? value : fail("Expected binary bytes");
            case "DATE" -> value instanceof java.sql.Date date ? date.toLocalDate()
                    : value instanceof LocalDate ? value : fail("Expected a date");
            case "TIME" -> value instanceof java.sql.Time time ? time.toLocalTime()
                    : value instanceof LocalTime ? value : fail("Expected a local time");
            case "TIMESTAMP WITH TIME ZONE" -> {
                if (value instanceof SdpValues.Timestamp) yield value;
                if (value instanceof OffsetDateTime temporal) yield SdpValues.Timestamp.of(temporal);
                if (value instanceof java.sql.Timestamp legacy)
                    yield SdpValues.Timestamp.of(legacy.toInstant().atOffset(ZoneOffset.UTC));
                yield fail("Expected OffsetDateTime, SdpValues.Timestamp or a UTC JDBC Timestamp");
            }
            case "RECORD" -> value instanceof SdpValues.RecordValue ? value : fail("Expected a record");
            case "COLLECTION" -> value instanceof SdpValues.CollectionValue ? value : fail("Expected a collection");
            default -> throw unsupported("Parameter type");
        };
    }

    private static Object fail(String reason) throws SQLException { throw Wire.invalid(reason); }
    private static long integer(Object value) throws SQLException {
        if (value instanceof BigDecimal v) return v.longValueExact();
        if (value instanceof BigInteger v) return v.longValueExact();
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
            return ((Number) value).longValue();
        throw Wire.invalid("Expected a signed integral value");
    }

    private static BigDecimal decimal(Object value) throws SQLException {
        if (value instanceof BigDecimal v) return v;
        if (value instanceof BigInteger v) return new BigDecimal(v);
        return BigDecimal.valueOf(integer(value));
    }

    private static int infer(Object value) throws SQLException {
        if (value == null || value instanceof String) return Types.VARCHAR;
        if (value instanceof Boolean) return Types.BOOLEAN;
        if (value instanceof BigDecimal || value instanceof BigInteger) return Types.DECIMAL;
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
            return Types.BIGINT;
        if (value instanceof byte[]) return Types.VARBINARY;
        if (value instanceof LocalDate || value instanceof java.sql.Date) return Types.DATE;
        if (value instanceof LocalTime || value instanceof java.sql.Time) return Types.TIME;
        if (value instanceof OffsetDateTime || value instanceof SdpValues.Timestamp || value instanceof java.sql.Timestamp)
            return Types.TIMESTAMP_WITH_TIMEZONE;
        if (value instanceof SdpValues.RecordValue) return Types.STRUCT;
        if (value instanceof SdpValues.CollectionValue) return Types.ARRAY;
        throw unsupported("Java value type");
    }

    private static String sqlType(int type) throws SQLException {
        return switch (type) {
            case Types.BIT, Types.BOOLEAN -> "BOOLEAN";
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT -> "BIGINT";
            case Types.DECIMAL, Types.NUMERIC -> "DECIMAL";
            case Types.VARCHAR, Types.NVARCHAR, Types.CHAR, Types.NCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR -> "TEXT";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "BLOB";
            case Types.DATE -> "DATE";
            case Types.TIME -> "TIME";
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP WITH TIME ZONE";
            case Types.STRUCT -> "RECORD";
            case Types.ARRAY -> "COLLECTION";
            default -> throw unsupported("JDBC parameter type");
        };
    }

    static int jdbcType(String type) {
        return switch (type.toUpperCase(Locale.ROOT).split("\\(", 2)[0]) {
            case "BOOLEAN" -> Types.BOOLEAN;
            case "BIGINT" -> Types.BIGINT;
            case "DECIMAL" -> Types.DECIMAL;
            case "TEXT" -> Types.VARCHAR;
            case "BLOB" -> Types.VARBINARY;
            case "DATE" -> Types.DATE;
            case "TIME" -> Types.TIME;
            case "TIMESTAMP WITH TIME ZONE" -> Types.TIMESTAMP_WITH_TIMEZONE;
            case "RECORD" -> Types.STRUCT;
            case "COLLECTION" -> Types.ARRAY;
            default -> Types.OTHER;
        };
    }

    private static JsonObject encode(Object value, int depth, Budget budget) throws SQLException {
        budget.visit(depth);
        if (value == null) return Wire.object("t", "null");
        if (value instanceof String string) return Wire.object("t", "text", "v", string);
        if (value instanceof Boolean flag) return Wire.object("t", "bool", "v", flag);
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
            return Wire.object("t", "i64", "v", Long.toString(integer(value)));
        if (value instanceof BigDecimal || value instanceof BigInteger) {
            var number = decimal(value);
            if (number.scale() < 0) {
                if ((long) number.precision() - number.scale() > 38 && number.signum() != 0)
                    throw Wire.invalid("Decimal precision exceeds 38");
                number = resize(number, 0);
            }
            if (number.precision() > 38 || number.scale() > 38) throw Wire.invalid("Decimal exceeds wire limits");
            return Wire.object("t", "decimal", "unscaled", number.unscaledValue().toString(), "scale", number.scale());
        }
        if (value instanceof byte[] bytes) {
            if (bytes.length > BLOB_LIMIT) throw Wire.invalid("Binary exceeds its byte limit");
            return Wire.object("t", "binary", "hex", HexFormat.of().formatHex(bytes));
        }
        if (value instanceof LocalDate date)
            return Wire.object("t", depth == 0 ? "text" : "date", "v", date.toString());
        if (value instanceof LocalTime time)
            return Wire.object("t", depth == 0 ? "text" : "time",
                    "v", java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSSSSS", Locale.ROOT).format(time));
        if (value instanceof OffsetDateTime temporal) value = SdpValues.Timestamp.of(temporal);
        if (value instanceof SdpValues.Timestamp timestamp) {
            var result = Wire.object("t", "timestamp", "seconds", Long.toString(timestamp.seconds()),
                    "nanos", timestamp.nanos(), "offset_minutes", timestamp.offsetMinutes());
            if (timestamp.zone() != null) result.addProperty("zone", timestamp.zone());
            return result;
        }
        if (value instanceof SdpValues.RecordValue record) {
            var fields = new JsonArray();
            if (record.fields().size() > CONTAINER_MEMBERS) throw Wire.invalid("Too many record fields");
            for (var entry : record.fields().entrySet())
                fields.add(Wire.object("name", entry.getKey(), "value", encode(entry.getValue(), depth + 1, budget)));
            return Wire.object("t", "record", "fields", fields);
        }
        if (value instanceof SdpValues.CollectionValue collection) {
            var items = new JsonArray();
            if (collection.items().size() > CONTAINER_MEMBERS) throw Wire.invalid("Too many collection values");
            for (var item : collection.items()) items.add(encode(item, depth + 1, budget));
            return Wire.object("t", "collection", "items", items);
        }
        throw unsupported("Compound item value type");
    }

    static Object decode(JsonElement value, String sqlType) throws SQLException {
        try {
            return decode(value.getAsJsonObject(), sqlType, 0, new Budget());
        } catch (RuntimeException | IOException error) {
            throw Wire.malformed();
        }
    }

    private static Object decode(JsonObject json, String type, int depth, Budget budget) throws IOException, SQLException {
        budget.visit(depth);
        String tag = Wire.requiredString(json, "t");
        return switch (tag) {
            case "null" -> {
                if (json.size() != 1) throw new IOException("Malformed NULL");
                yield null;
            }
            case "i64" -> signed(Wire.requiredString(json, "v"));
            case "bool" -> Wire.requiredBoolean(json, "v");
            case "decimal" -> {
                String coefficient = Wire.requiredString(json, "unscaled");
                long scale = Wire.requiredInteger(json, "scale");
                if (scale < 0 || scale > 38 || !COEFFICIENT.matcher(coefficient).matches())
                    throw new IOException("Malformed decimal");
                yield new BigDecimal(new BigInteger(coefficient), (int) scale);
            }
            case "text" -> {
                String text = Wire.requiredString(json, "v");
                if (type.equals("DATE")) yield LocalDate.parse(text);
                if (type.equals("TIME")) yield LocalTime.parse(text);
                yield text;
            }
            case "date" -> LocalDate.parse(Wire.requiredString(json, "v"));
            case "time" -> LocalTime.parse(Wire.requiredString(json, "v"));
            case "binary" -> {
                String hex = Wire.requiredString(json, "hex");
                if (hex.length() > BLOB_LIMIT * 2 || (hex.length() & 1) != 0 || !hex.matches("[0-9a-f]*"))
                    throw new IOException("Malformed binary");
                yield HexFormat.of().parseHex(hex);
            }
            case "timestamp" -> new SdpValues.Timestamp(signed(Wire.requiredString(json, "seconds")),
                    Math.toIntExact(Wire.requiredInteger(json, "nanos")),
                    Math.toIntExact(Wire.requiredInteger(json, "offset_minutes")), Wire.optionalString(json, "zone"));
            case "record" -> {
                var fields = json.getAsJsonArray("fields");
                if (fields.size() > CONTAINER_MEMBERS) throw new IOException("Too many record fields");
                var output = new LinkedHashMap<String, Object>();
                for (var field : fields) {
                    var entry = field.getAsJsonObject();
                    String name = Wire.requiredString(entry, "name");
                    if (name.isEmpty() || output.containsKey(name)) throw new IOException("Malformed record");
                    output.put(name, decode(Wire.requiredObject(entry, "value"), "", depth + 1, budget));
                }
                yield new SdpValues.RecordValue(output);
            }
            case "collection" -> {
                var items = json.getAsJsonArray("items");
                if (items.size() > CONTAINER_MEMBERS) throw new IOException("Too many collection items");
                var output = new ArrayList<>();
                for (var item : items) output.add(decode(item.getAsJsonObject(), "", depth + 1, budget));
                yield new SdpValues.CollectionValue(output);
            }
            default -> throw new IOException("Unrecognized type tag");
        };
    }

    private static long signed(String value) throws IOException {
        if (!value.matches("0|-?[1-9][0-9]*")) throw new IOException("Malformed signed integer");
        return Long.parseLong(value);
    }

    static SQLFeatureNotSupportedException unsupported(String operation) {
        return new SQLFeatureNotSupportedException("Unsupported JDBC operation " + operation, "0A000");
    }
}
