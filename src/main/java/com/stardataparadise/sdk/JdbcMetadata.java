package com.stardataparadise.sdk;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ParameterMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

final class JdbcMetadata {
    private JdbcMetadata() { }

    static DatabaseMetaData database(JdbcSession session) {
        return JdbcProxy.make(DatabaseMetaData.class, (proxy, method, args) -> {
            var common = JdbcProxy.common(proxy, method, args, "DatabaseMetaData");
            if (common != JdbcProxy.UNHANDLED) return common;
            session.requireOpen();
            String name = method.getName();
            return switch (name) {
                case "getURL" -> session.url;
                case "getUserName" -> session.user;
                case "getConnection" -> session.handle;
                case "getDatabaseProductName" -> "StarDataParadise";
                case "getDatabaseProductVersion" -> session.serverVersion;
                case "getDatabaseMajorVersion" -> version(session.serverVersion, 0);
                case "getDatabaseMinorVersion" -> version(session.serverVersion, 1);
                case "getDriverName" -> "StarDataParadise JDBC";
                case "getDriverVersion" -> "0.2.0";
                case "getDriverMajorVersion" -> 0;
                case "getDriverMinorVersion" -> 2;
                case "getJDBCMajorVersion" -> 4;
                case "getJDBCMinorVersion" -> 3;
                case "getSQLStateType" -> DatabaseMetaData.sqlStateSQL;
                case "getIdentifierQuoteString" -> "\"";
                case "getSearchStringEscape" -> "\\";
                case "getCatalogSeparator" -> ".";
                case "getCatalogTerm" -> "database";
                case "getSchemaTerm" -> "schema";
                case "getProcedureTerm" -> "procedure";
                case "getDefaultTransactionIsolation" -> Connection.TRANSACTION_SERIALIZABLE;
                case "supportsTransactions", "supportsSavepoints", "supportsMultipleResultSets",
                     "supportsStoredProcedures", "nullsAreSortedAtEnd", "storesLowerCaseIdentifiers",
                     "storesMixedCaseQuotedIdentifiers" -> true;
                case "supportsBatchUpdates", "supportsGetGeneratedKeys", "supportsMultipleOpenResults",
                     "supportsOpenCursorsAcrossCommit", "supportsOpenCursorsAcrossRollback", "isReadOnly" -> false;
                case "supportsTransactionIsolationLevel" -> (Integer) args[0] == Connection.TRANSACTION_SERIALIZABLE;
                case "supportsResultSetType" -> (Integer) args[0] == ResultSet.TYPE_FORWARD_ONLY;
                case "supportsResultSetConcurrency" -> (Integer) args[0] == ResultSet.TYPE_FORWARD_ONLY
                        && (Integer) args[1] == ResultSet.CONCUR_READ_ONLY;
                case "supportsResultSetHoldability" -> (Integer) args[0] == ResultSet.CLOSE_CURSORS_AT_COMMIT;
                case "getResultSetHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT;
                case "getCatalogs" -> catalogs(session);
                case "getSchemas" -> schemas(session, args);
                case "getTables" -> tables(session, args);
                case "getColumns" -> columns(session, args);
                case "getTableTypes" -> JdbcRows.local(headers("TABLE_TYPE"), List.of(List.of("TABLE"), List.of("VIEW")));
                default -> throw ValueCodec.unsupported(name);
            };
        });
    }

    static ParameterMetaData parameters(JdbcStatement statement, ValueCodec.Binding[] bindings) {
        return JdbcProxy.make(ParameterMetaData.class, (proxy, method, args) -> {
            var common = JdbcProxy.common(proxy, method, args, "ParameterMetaData");
            if (common != JdbcProxy.UNHANDLED) return common;
            if (method.getName().equals("getParameterCount")) return bindings.length;
            if (args == null || args.length != 1 || !(args[0] instanceof Integer i) || i < 1 || i > bindings.length)
                throw Wire.invalid("Parameter index is out of range");
            var binding = bindings[i - 1];
            String type = binding == null ? "UNKNOWN" : binding.declaration().get("type").getAsString();
            int jdbc = ValueCodec.jdbcType(type);
            return switch (method.getName()) {
                case "getParameterType" -> jdbc;
                case "getParameterTypeName" -> type;
                case "getParameterClassName" -> binding == null ? Object.class.getName() : JdbcRows.javaClass(type).getName();
                case "getParameterMode" -> ParameterMetaData.parameterModeUnknown;
                case "isNullable" -> ParameterMetaData.parameterNullableUnknown;
                case "isSigned" -> jdbc == Types.BIGINT || jdbc == Types.DECIMAL;
                case "getPrecision" -> type.equals("DECIMAL") ? binding.declaration().get("precision").getAsInt()
                        : type.equals("BIGINT") ? 19 : 0;
                case "getScale" -> type.equals("DECIMAL") ? binding.declaration().get("scale").getAsInt() : 0;
                default -> throw ValueCodec.unsupported(method.getName());
            };
        });
    }

    private static ResultSet catalogs(JdbcSession session) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        for (var object : session.catalog()) if (object.kind() == 1)
            rows.add(List.of(object.qualifiedName().get(0)));
        return JdbcRows.local(headers("TABLE_CAT"), rows);
    }

    private static ResultSet schemas(JdbcSession session, Object[] args) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        String catalog = args == null ? null : (String) args[0];
        String schema = args == null ? null : (String) args[1];
        for (var object : session.catalog()) {
            if (object.kind() != 2 || object.qualifiedName().size() != 2) continue;
            var name = object.qualifiedName();
            if (exact(catalog, name.get(0)) && pattern(schema, name.get(1))) rows.add(List.of(name.get(1), name.get(0)));
        }
        return JdbcRows.local(headers("TABLE_SCHEM", "TABLE_CATALOG"), rows);
    }

    private static ResultSet tables(JdbcSession session, Object[] args) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        for (var object : session.catalog()) {
            String type = object.kind() == 3 ? "TABLE" : object.kind() == 5 ? "VIEW" : null;
            if (type == null || !filter(object, args)) continue;
            if (args[3] != null && !Arrays.asList((String[]) args[3]).contains(type)) continue;
            var name = object.qualifiedName();
            rows.add(Arrays.asList(name.get(0), name.get(1), name.get(2), type, null, null, null, null, null, null));
        }
        return JdbcRows.local(headers("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
                "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION"), rows);
    }

    private static ResultSet columns(JdbcSession session, Object[] args) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        for (var object : session.catalog()) {
            if (object.kind() != 3 && object.kind() != 5 || !filter(object, args)) continue;
            var name = object.qualifiedName();
            for (var column : object.columns()) {
                if (!pattern((String) args[3], column.name())) continue;
                int nullable = column.nullable() ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls;
                var descriptor = new JdbcColumn(column.name(), column.type(), nullable);
                rows.add(Arrays.asList(name.get(0), name.get(1), name.get(2), column.name(),
                        ValueCodec.jdbcType(column.type()), column.type(), descriptor.precision(), null,
                        descriptor.scale(), 10, nullable, null, null, null, null, null,
                        column.ordinal(), column.nullable() ? "YES" : "NO", null, null, null, null, "", ""));
            }
        }
        return JdbcRows.local(headers("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE#",
                "TYPE_NAME", "COLUMN_SIZE#", "BUFFER_LENGTH#", "DECIMAL_DIGITS#", "NUM_PREC_RADIX#",
                "NULLABLE#", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE#", "SQL_DATETIME_SUB#", "CHAR_OCTET_LENGTH#",
                "ORDINAL_POSITION#", "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE",
                "SOURCE_DATA_TYPE#", "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN"), rows);
    }

    private static boolean filter(SdpConnection.CatalogObject object, Object[] args) {
        var name = object.qualifiedName();
        return name.size() == 3 && exact((String) args[0], name.get(0))
                && pattern((String) args[1], name.get(1)) && pattern((String) args[2], name.get(2));
    }

    private static boolean exact(String filter, String text) { return filter == null || filter.equals(text); }

    private static boolean pattern(String filter, String value) {
        if (filter == null) return true;
        StringBuilder regex = new StringBuilder("(?s)");
        for (int i = 0; i < filter.length(); i++) {
            char ch = filter.charAt(i);
            if (ch == '\\' && i + 1 < filter.length()) regex.append(Pattern.quote(filter.substring(++i, i + 1)));
            else if (ch == '%') regex.append(".*");
            else if (ch == '_') regex.append('.');
            else regex.append(Pattern.quote(String.valueOf(ch)));
        }
        return value.matches(regex.toString());
    }

    private static List<JdbcColumn> headers(String... names) {
        return Arrays.stream(names).map(name -> name.endsWith("#")
                ? new JdbcColumn(name.substring(0, name.length() - 1), "BIGINT")
                : new JdbcColumn(name, "TEXT")).toList();
    }

    private static int version(String value, int position) { return Integer.parseInt(value.split("\\.")[position]); }
}
