package com.stardataparadise.sdk;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.TreeMap;

final class JdbcStatement implements InvocationHandler {
    private final JdbcSession session;
    private final SqlText prepared;
    private final boolean callable;
    private final Statement handle;
    private final ValueCodec.Binding[] bindings;
    private final TreeMap<Integer, ValueCodec.Binding> registered = new TreeMap<>();
    private boolean closed;
    private int queryTimeout;
    private int fetchSize = 128;
    private int maxRows;
    private long updateCount = -1;
    private ServerRows rows;
    private ResultSet result;
    private List<Object> outValues;
    private boolean wasNull;

    private JdbcStatement(JdbcSession session, SqlText prepared, boolean callable) {
        this.session = session;
        this.prepared = prepared;
        this.callable = callable;
        this.bindings = new ValueCodec.Binding[prepared == null ? 0 : prepared.parameters()];
        this.handle = callable ? JdbcProxy.make(CallableStatement.class, this)
                : prepared != null ? JdbcProxy.make(PreparedStatement.class, this)
                : JdbcProxy.make(Statement.class, this);
    }

    static Statement make(JdbcSession session, SqlText prepared, boolean callable) {
        return new JdbcStatement(session, prepared, callable).handle;
    }

    int queryTimeout() { return queryTimeout; }
    int fetchSize() { return fetchSize; }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
        var common = JdbcProxy.common(proxy, method, args, callable ? "CallableStatement" : "Statement");
        if (common != JdbcProxy.UNHANDLED) return common;
        String name = method.getName();
        if (name.equals("isClosed")) return closed || session.isClosed();
        if (name.equals("cancel")) { session.cancel(this); return null; }
        if (name.equals("close")) {
            if (!closed) {
                closed = true;
                cleanup(true);
            }
            return null;
        }
        if (closed) throw JdbcProxy.closed("Statement");
        session.requireOpen();
        return switch (name) {
            case "execute", "executeQuery", "executeUpdate", "executeLargeUpdate" -> run(name, args);
            case "getResultSet" -> result;
            case "getUpdateCount" -> narrow(updateCount);
            case "getLargeUpdateCount" -> updateCount;
            case "getMoreResults" -> {
                if (args != null && (Integer) args[0] != Statement.CLOSE_CURRENT_RESULT
                        && (Integer) args[0] != Statement.CLOSE_ALL_RESULTS)
                    throw ValueCodec.unsupported("keeping previous results");
                yield advance();
            }
            case "getMetaData" -> result == null ? null : result.getMetaData();
            case "getConnection" -> session.handle;
            case "getQueryTimeout" -> queryTimeout;
            case "setQueryTimeout" -> {
                int seconds = (Integer) args[0];
                if (seconds < 0 || seconds > 120) throw Wire.invalid("Invalid statement timeout");
                queryTimeout = seconds;
                yield null;
            }
            case "getFetchSize" -> fetchSize;
            case "setFetchSize" -> {
                int requested = (Integer) args[0];
                if (requested < 0 || requested > 1024) throw Wire.invalid("Invalid fetch size");
                fetchSize = requested == 0 ? 128 : requested;
                yield null;
            }
            case "getFetchDirection" -> ResultSet.FETCH_FORWARD;
            case "setFetchDirection" -> {
                if ((Integer) args[0] != ResultSet.FETCH_FORWARD) throw ValueCodec.unsupported(name);
                yield null;
            }
            case "getResultSetType" -> ResultSet.TYPE_FORWARD_ONLY;
            case "getResultSetConcurrency" -> ResultSet.CONCUR_READ_ONLY;
            case "getResultSetHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT;
            case "getMaxRows" -> maxRows;
            case "getLargeMaxRows" -> (long) maxRows;
            case "setMaxRows", "setLargeMaxRows" -> {
                long limit = ((Number) args[0]).longValue();
                if (limit < 0 || limit > 1_000_000) throw Wire.invalid("Invalid statement row limit");
                maxRows = (int) limit;
                yield null;
            }
            case "getMaxFieldSize" -> 0;
            case "setMaxFieldSize" -> {
                if ((Integer) args[0] != 0) throw ValueCodec.unsupported(name);
                yield null;
            }
            case "isPoolable", "isCloseOnCompletion" -> false;
            case "setPoolable" -> { if ((Boolean) args[0]) throw ValueCodec.unsupported(name); yield null; }
            case "getWarnings", "clearWarnings" -> null;
            case "getParameterMetaData" -> JdbcMetadata.parameters(this, bindings);
            case "clearParameters" -> {
                if (prepared == null) throw ValueCodec.unsupported(name);
                java.util.Arrays.fill(bindings, null);
                yield null;
            }
            case "registerOutParameter" -> { register(args); yield null; }
            case "wasNull" -> {
                if (!callable) throw ValueCodec.unsupported(name);
                yield wasNull;
            }
            default -> {
                if (prepared != null && bind(name, args)) yield null;
                if (callable && JdbcRows.isValueGetter(name)) yield output(name, args);
                throw ValueCodec.unsupported(name);
            }
        };
    }

    private Object run(String name, Object[] args) throws SQLException {
        SqlText sql;
        if (prepared != null) {
            if (args != null) throw ValueCodec.unsupported("replacing prepared SQL");
            sql = prepared;
        } else {
            if (args == null || args.length != 1 || !(args[0] instanceof String raw))
                throw ValueCodec.unsupported("generated key execution");
            sql = SqlText.parse(raw, false);
            if (sql.parameters() != 0) throw Wire.invalid("Parameter markers require a prepared statement");
        }
        if (name.equals("executeQuery") && sql.kind() == SqlText.Kind.WRITE)
            throw JdbcProxy.state("Use executeUpdate or execute for SQL writes");
        if ((name.equals("executeUpdate") || name.equals("executeLargeUpdate"))
                && (sql.kind() == SqlText.Kind.READ || sql.kind() == SqlText.Kind.ROUTINE))
            throw JdbcProxy.state("Use executeQuery or execute for SQL that can return rows");
        execute(sql);
        if (name.equals("execute")) return result != null;
        if (name.equals("executeQuery")) {
            if (result == null) throw JdbcProxy.state("Statement did not create a result set");
            return result;
        }
        if (result != null) { cleanup(false); throw JdbcProxy.state("Statement created a result set"); }
        if (name.equals("executeLargeUpdate")) return updateCount;
        return narrow(updateCount);
    }

    private void execute(SqlText sql) throws SQLException {
        cleanup(true);
        updateCount = -1;
        boolean own = false;
        String statementId = null;
        SQLException failure = null;
        String unverifiedReceipt = null;
        boolean writeAcknowledged = false;
        try {
            session.beforeStatement(sql);
            var types = new JsonObject();
            var values = new JsonObject();
            var outOnly = new java.util.HashSet<Integer>();
            for (int i = 0; i < bindings.length; i++) {
                var value = bindings[i];
                if (value == null && registered.containsKey(i + 1)) {
                    outOnly.add(i + 1);
                    continue;
                }
                if (value == null) throw Wire.invalid("A prepared parameter is unbound");
                String name = SqlText.parameterName(i + 1);
                types.add(name, value.declaration());
                values.add(name, value.value());
            }
            own = session.start(sql.kind(), this, queryTimeout);
            var plan = session.call("query.prepare", Wire.object("sql", sql.wireWithOutPlaceholders(outOnly),
                    "parameter_types", types),
                    this, queryTimeout, null);
            statementId = Wire.requiredString(plan, "statement_id");
            var body = Wire.object("statement_id", statementId, "parameters", values,
                    "row_limit", maxRows == 0 ? 1_000_000 : maxRows);
            String token = session.autoCommit() && sql.kind() == SqlText.Kind.WRITE ? Wire.token() : null;
            if (token != null) body.addProperty("request_token", token);
            var execution = session.call("execute", body, this, queryTimeout, token);
            if (token != null && session.durableWireTransactions()) {
                unverifiedReceipt = token;
                if (execution.has("result_id") || Wire.requiredString(execution, "lsn").isEmpty())
                    throw new IOException("Invalid write receipt");
            }
            if (execution.has("result_id")) {
                rows = new ServerRows(session, this, execution, own,
                        sql.kind() == SqlText.Kind.ROUTINE || sql.kind() == SqlText.Kind.READ && callable);
                result = JdbcRows.create(rows.part(), handle);
                if (!registered.isEmpty()) {
                    outValues = rows.outputRow();
                    if (outValues.size() != registered.size())
                        throw JdbcProxy.state("Register each procedure OUT or IN OUT parameter in declaration order");
                }
            } else {
                updateCount = Wire.requiredInteger(execution, "affected_rows");
                if (unverifiedReceipt != null && updateCount < 0) throw new IOException("Invalid update count");
                writeAcknowledged = unverifiedReceipt != null;
                if (!registered.isEmpty()) throw JdbcProxy.state("Procedure did not return registered OUT values");
                if (own) session.finishOwned(true, this, queryTimeout);
            }
        } catch (IOException | RuntimeException violation) {
            if (unverifiedReceipt != null && !writeAcknowledged) {
                session.closeWire();
                failure = Wire.malformed(unverifiedReceipt);
            } else {
                failure = Wire.malformed();
            }
        } catch (SQLException sqlFailure) {
            if (session.durableWireTransactions() && sqlFailure instanceof SdpException wireFailure
                    && wireFailure.isOutcomeUnknown()) session.closeWire();
            failure = sqlFailure;
        } finally {
            if (statementId != null && !session.isClosed()) {
                try { session.call("statement_close", Wire.object("statement_id", statementId), this,
                        queryTimeout, null); }
                catch (SQLException closing) {
                    // A cleanup failure must not mask a committed write and invite a duplicate.
                    if (writeAcknowledged) session.closeWire();
                    else if (failure == null) failure = closing;
                    else failure.addSuppressed(closing);
                }
            }
        }
        if (failure != null) {
            try {
                if (rows != null) cleanup(false);
                else if (own) session.finishOwned(false, this, queryTimeout);
            } catch (SQLException closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    private boolean advance() throws SQLException {
        updateCount = -1;
        if (rows == null) return false;
        var part = rows.advance();
        result = part == null ? null : JdbcRows.create(part, handle);
        return part != null;
    }

    private void cleanup(boolean success) throws SQLException {
        var active = rows;
        rows = null;
        result = null;
        outValues = null;
        if (active != null) {
            try { active.finish(success); } finally { active.invalidate(); }
        }
    }

    private boolean bind(String method, Object[] args) throws SQLException {
        if (args == null || args.length < 2 || !(args[0] instanceof Integer)) return false;
        Object raw = args[1];
        int jdbcType;
        Integer scale = null;
        switch (method) {
            case "setNull" -> {
                requireTwo(args);
                jdbcType = ValueCodec.typeNumber(raw);
                raw = null;
            }
            case "setObject" -> {
                if (args.length > 4) throw ValueCodec.unsupported("setObject overload");
                jdbcType = args.length >= 3 ? ValueCodec.typeNumber(args[2]) : Types.NULL;
                if (args.length == 4) scale = (Integer) args[3];
            }
            case "setBinaryStream", "setBlob" -> {
                if (raw != null && !(raw instanceof InputStream) || args.length > 3)
                    throw ValueCodec.unsupported(method + " overload");
                Long length = args.length == 3 ? ((Number) args[2]).longValue() : null;
                raw = ValueCodec.binary((InputStream) raw, length);
                jdbcType = Types.VARBINARY;
            }
            default -> {
                jdbcType = switch (method) {
                    case "setLong", "setInt", "setShort", "setByte" -> Types.BIGINT;
                    case "setBoolean" -> Types.BOOLEAN;
                    case "setBigDecimal" -> Types.DECIMAL;
                    case "setString", "setNString" -> Types.VARCHAR;
                    case "setBytes" -> Types.VARBINARY;
                    case "setDate" -> Types.DATE;
                    case "setTime" -> Types.TIME;
                    case "setTimestamp" -> Types.TIMESTAMP_WITH_TIMEZONE;
                    default -> Types.OTHER;
                };
                if (jdbcType == Types.OTHER) return false;
                requireTwo(args);
            }
        }
        int index = index(args[0]);
        bindings[index - 1] = ValueCodec.binding(raw, jdbcType, scale);
        return true;
    }

    private static void requireTwo(Object[] args) throws SQLException {
        if (args.length != 2) throw ValueCodec.unsupported("parameter overload");
    }

    private void register(Object[] args) throws SQLException {
        if (!callable || args.length < 2 || args.length > 3)
            throw ValueCodec.unsupported("OUT registration");
        int index = index(args[0]);
        if (args.length == 3 && !(args[2] instanceof Integer)) throw ValueCodec.unsupported("OUT type name");
        registered.put(index, ValueCodec.binding(null, ValueCodec.typeNumber(args[1]),
                args.length == 3 ? (Integer) args[2] : null));
    }

    private Object output(String method, Object[] args) throws SQLException {
        int index = index(args[0]);
        if (!registered.containsKey(index) || outValues == null)
            throw JdbcProxy.state("OUT parameter is unregistered or unavailable");
        Object value = outValues.get(registered.headMap(index, false).size());
        wasNull = value == null;
        return JdbcRows.convert(method, value, args);
    }

    private int index(Object position) throws SQLException {
        if (!(position instanceof Integer index)) throw ValueCodec.unsupported("named JDBC parameter");
        if (index < 1 || index > bindings.length) throw Wire.invalid("Parameter index is out of range");
        return index;
    }

    private static int narrow(long count) throws SQLException {
        if (count > Integer.MAX_VALUE) throw new SQLException("Use the JDBC large update count", "22003");
        return (int) count;
    }
}
