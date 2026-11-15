package com.stardataparadise.sdk;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

final class JdbcRows implements InvocationHandler {
    interface Source {
        List<JdbcColumn> columns();
        List<Object> next() throws SQLException;
        void close() throws SQLException;
        boolean isClosed();
    }

    private final Source source;
    private final Statement statement;
    private List<Object> row;
    private int rowNumber;
    private boolean wasNull;

    private JdbcRows(Source source, Statement statement) {
        this.source = source;
        this.statement = statement;
    }

    static ResultSet create(Source source, Statement statement) {
        return JdbcProxy.make(ResultSet.class, new JdbcRows(source, statement));
    }

    static ResultSet local(List<JdbcColumn> columns, List<List<Object>> rows) {
        return create(new Source() {
            private boolean closed;
            private int index;
            public List<JdbcColumn> columns() { return columns; }
            public List<Object> next() { return index == rows.size() ? null : rows.get(index++); }
            public void close() { closed = true; }
            public boolean isClosed() { return closed; }
        }, null);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
        var common = JdbcProxy.common(proxy, method, args, "ResultSet");
        if (common != JdbcProxy.UNHANDLED) return common;
        String name = method.getName();
        if (name.equals("isClosed")) return source.isClosed();
        if (name.equals("close")) { source.close(); row = null; return null; }
        if (source.isClosed()) throw JdbcProxy.closed("Result set");
        return switch (name) {
            case "next" -> {
                row = source.next();
                if (row != null) ++rowNumber;
                yield row != null;
            }
            case "getRow" -> row == null ? 0 : rowNumber;
            case "wasNull" -> wasNull;
            case "findColumn" -> position(args[0]);
            case "getMetaData" -> JdbcColumn.metadata(source.columns());
            case "getStatement" -> statement;
            case "getType" -> ResultSet.TYPE_FORWARD_ONLY;
            case "getConcurrency" -> ResultSet.CONCUR_READ_ONLY;
            case "getHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT;
            case "getFetchDirection" -> ResultSet.FETCH_FORWARD;
            case "getWarnings", "clearWarnings" -> null;
            default -> {
                if (isValueGetter(name) && args != null && args.length > 0) {
                    if (row == null) throw new SQLException("Result set is not positioned on a row", "24000");
                    Object value = row.get(position(args[0]) - 1);
                    wasNull = value == null;
                    yield convert(name, value, args);
                }
                throw ValueCodec.unsupported(name);
            }
        };
    }

    static boolean isValueGetter(String name) {
        return switch (name) {
            case "getObject", "getString", "getNString", "getLong", "getInt", "getShort", "getByte",
                 "getBoolean", "getDouble", "getFloat", "getBigDecimal", "getBytes", "getBinaryStream",
                 "getCharacterStream", "getNCharacterStream", "getDate", "getTime", "getTimestamp" -> true;
            default -> false;
        };
    }

    private int position(Object key) throws SQLException {
        if (key instanceof Integer index) {
            JdbcColumn.at(source.columns(), index);
            return index;
        }
        if (key instanceof String label)
            for (int i = 0; i < source.columns().size(); i++)
                if (source.columns().get(i).name().equalsIgnoreCase(label)) return i + 1;
        throw new SQLException("Column label not found", "42S22");
    }

    static Class<?> javaClass(String type) {
        return switch (ValueCodec.jdbcType(type)) {
            case Types.BIGINT -> Long.class;
            case Types.BOOLEAN -> Boolean.class;
            case Types.DECIMAL -> BigDecimal.class;
            case Types.VARBINARY -> byte[].class;
            case Types.DATE -> LocalDate.class;
            case Types.TIME -> LocalTime.class;
            case Types.TIMESTAMP_WITH_TIMEZONE -> OffsetDateTime.class;
            case Types.STRUCT -> SdpValues.RecordValue.class;
            case Types.ARRAY -> SdpValues.CollectionValue.class;
            default -> String.class;
        };
    }

    static Object convert(String method, Object value, Object[] args) throws SQLException {
        if (args.length != 1 && !(method.equals("getObject") && args.length == 2 && args[1] instanceof Class<?>))
            throw ValueCodec.unsupported(method + " overload");
        try {
            return switch (method) {
                case "getObject" -> {
                    if (args.length == 1) yield value instanceof SdpValues.Timestamp stamp
                            ? stamp.toOffsetDateTime() : SdpValues.copy(value);
                    yield typed(value, (Class<?>) args[1]);
                }
                case "getString", "getNString" -> value == null ? null : text(value);
                case "getBoolean" -> value == null ? false : booleanValue(value);
                case "getBigDecimal" -> value == null ? null : number(value);
                case "getLong" -> value == null ? 0L : number(value).longValueExact();
                case "getInt" -> value == null ? 0 : number(value).intValueExact();
                case "getShort" -> value == null ? (short) 0 : number(value).shortValueExact();
                case "getByte" -> value == null ? (byte) 0 : number(value).byteValueExact();
                case "getDouble" -> value == null ? 0d : number(value).doubleValue();
                case "getFloat" -> value == null ? 0f : number(value).floatValue();
                case "getBytes" -> value == null ? null : ((byte[]) value).clone();
                case "getBinaryStream" -> value == null ? null : new ByteArrayInputStream(((byte[]) value).clone());
                case "getCharacterStream", "getNCharacterStream" -> value == null ? null : new StringReader(text(value));
                case "getDate" -> value == null ? null : java.sql.Date.valueOf((LocalDate) value);
                case "getTime" -> value == null ? null : java.sql.Time.valueOf((LocalTime) value);
                case "getTimestamp" -> value == null ? null : java.sql.Timestamp.from(timestamp(value).toInstant());
                default -> throw ValueCodec.unsupported(method);
            };
        } catch (RuntimeException failure) {
            throw new SQLException("Column cannot be converted to the requested JDBC type", "22005");
        }
    }

    private static Object typed(Object value, Class<?> type) {
        if (value == null || type.isInstance(value)) return SdpValues.copy(value);
        if (type == String.class) return text(value);
        if (type == Long.class || type == long.class) return number(value).longValueExact();
        if (type == Integer.class || type == int.class) return number(value).intValueExact();
        if (type == Boolean.class || type == boolean.class) return booleanValue(value);
        if (type == BigDecimal.class) return number(value);
        if (type == java.math.BigInteger.class) return number(value).toBigIntegerExact();
        if (type == OffsetDateTime.class) return timestamp(value);
        if (type == java.sql.Timestamp.class) return java.sql.Timestamp.from(timestamp(value).toInstant());
        if (type == java.sql.Date.class) return java.sql.Date.valueOf((LocalDate) value);
        if (type == java.sql.Time.class) return java.sql.Time.valueOf((LocalTime) value);
        throw new IllegalArgumentException("unsupported conversion");
    }

    private static OffsetDateTime timestamp(Object value) {
        return ((SdpValues.Timestamp) value).toOffsetDateTime();
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) return java.util.HexFormat.of().formatHex(bytes);
        if (value instanceof SdpValues.Timestamp stamp) return stamp.toOffsetDateTime().toString();
        return value.toString();
    }

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal exact) return exact;
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)
            return BigDecimal.valueOf(((Number) value).longValue());
        if (value instanceof Boolean flag) return flag ? BigDecimal.ONE : BigDecimal.ZERO;
        if (value instanceof String text) return new BigDecimal(text);
        throw new IllegalArgumentException("expected number");
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")))
            return Boolean.parseBoolean(text);
        return number(value).signum() != 0;
    }
}
