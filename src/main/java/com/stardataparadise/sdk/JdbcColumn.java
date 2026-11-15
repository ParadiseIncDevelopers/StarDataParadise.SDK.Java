package com.stardataparadise.sdk;

import com.google.gson.JsonArray;
import java.io.IOException;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

record JdbcColumn(String name, String type, int nullable) {
    JdbcColumn(String name, String type) { this(name, type, ResultSetMetaData.columnNullableUnknown); }

    static List<JdbcColumn> parse(JsonArray data) throws SQLException {
        List<JdbcColumn> result = new ArrayList<>(data.size());
        try {
            for (var value : data) {
                var descriptor = value.getAsJsonObject();
                result.add(new JdbcColumn(Wire.requiredString(descriptor, "name"),
                        Wire.requiredString(descriptor, "type")));
            }
            return List.copyOf(result);
        } catch (IOException | RuntimeException failure) {
            throw Wire.malformed();
        }
    }

    static ResultSetMetaData metadata(List<JdbcColumn> columns) {
        return JdbcProxy.make(ResultSetMetaData.class, (proxy, method, args) -> {
            var common = JdbcProxy.common(proxy, method, args, "ResultSetMetaData");
            if (common != JdbcProxy.UNHANDLED) return common;
            if (method.getName().equals("getColumnCount")) return columns.size();
            if (args == null || args.length != 1 || !(args[0] instanceof Integer index))
                throw ValueCodec.unsupported(method.getName());
            JdbcColumn column = at(columns, index);
            return switch (method.getName()) {
                case "getColumnName", "getColumnLabel" -> column.name;
                case "getColumnTypeName" -> column.type;
                case "getColumnType" -> ValueCodec.jdbcType(column.type);
                case "getColumnClassName" -> JdbcRows.javaClass(column.type).getName();
                case "getPrecision" -> column.precision();
                case "getScale" -> column.scale();
                case "getColumnDisplaySize" -> Math.max(column.precision(), column.name.length());
                case "isNullable" -> column.nullable;
                case "isSigned" -> ValueCodec.jdbcType(column.type) == Types.BIGINT
                        || ValueCodec.jdbcType(column.type) == Types.DECIMAL;
                case "isAutoIncrement", "isCurrency", "isWritable", "isDefinitelyWritable", "isSearchable" -> false;
                case "isCaseSensitive" -> ValueCodec.jdbcType(column.type) == Types.VARCHAR;
                case "isReadOnly" -> true;
                case "getCatalogName", "getSchemaName", "getTableName" -> "";
                default -> throw ValueCodec.unsupported(method.getName());
            };
        });
    }

    static JdbcColumn at(List<JdbcColumn> columns, int index) throws SQLException {
        if (index < 1 || index > columns.size()) throw Wire.invalid("Column index is out of range");
        return columns.get(index - 1);
    }

    int precision() {
        if (type.startsWith("DECIMAL(")) return Integer.parseInt(type.substring(8, type.indexOf(',')));
        return type.equals("BIGINT") ? 19 : type.equals("BOOLEAN") ? 1 : 0;
    }

    int scale() {
        if (type.startsWith("DECIMAL(")) return Integer.parseInt(type.substring(type.indexOf(',') + 1, type.length() - 1));
        return 0;
    }
}
