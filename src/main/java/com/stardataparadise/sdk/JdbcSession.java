package com.stardataparadise.sdk;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

final class JdbcSession implements InvocationHandler {
    private static final String FULL_SQL = "sql_crud_v1";
    private static final String DURABLE_TRANSACTIONS = "wire_sql_transactions_v1";
    private final Wire wire;
    final SdpConnection handle;
    final String url;
    final String user;
    final String serverVersion;
    private final String sessionId;
    private final Map<String, Boolean> capabilities;
    private final int baseTimeout;
    private volatile int networkTimeout;
    private boolean autoCommit = true;
    private boolean transaction;
    private long transactionGeneration;
    private int nextSavepoint;
    private final List<Save> savepoints = new ArrayList<>();
    private ServerRows cursor;

    private record Save(JdbcSession owner, long generation, int id, String label, String wireName) implements Savepoint {
        @Override public int getSavepointId() throws SQLException {
            if (label != null) throw JdbcProxy.state("Named savepoint has no numeric id");
            return id;
        }
        @Override public String getSavepointName() throws SQLException {
            if (label == null) throw JdbcProxy.state("Unnamed savepoint has no JDBC name");
            return label;
        }
    }

    private JdbcSession(Wire wire, String url, String user, String version, String sessionId,
                        Map<String, Boolean> capabilities, int baseTimeout) {
        this.wire = wire;
        this.url = url;
        this.user = user;
        this.serverVersion = version;
        this.sessionId = sessionId;
        this.capabilities = Map.copyOf(capabilities);
        this.baseTimeout = baseTimeout;
        this.handle = JdbcProxy.make(SdpConnection.class, this);
    }

    static SdpConnection connect(Wire wire, String url, String user, String password,
                                 int connectTimeout, int queryTimeout) throws SQLException {
        var hello = wire.request("hello", Wire.object("client", "StarDataParadise JDBC 0.3.0-preview.1"),
                wire, connectTimeout, null);
        var auth = Wire.object("username", user, "password", password);
        try {
            var reply = wire.request("auth", auth, wire, connectTimeout, null);
            if (!Wire.requiredBoolean(reply, "authenticated")) throw Wire.malformed();
        } catch (IOException e) {
            throw Wire.malformed();
        } finally {
            auth.remove("password");
        }
        try {
            if (Wire.requiredInteger(hello, "protocol_version") != 1) throw Wire.malformed();
            String id = Wire.requiredString(hello, "session_id");
            String version = Wire.requiredString(hello, "product_version");
            if (!id.matches("[0-9a-f]{32}") || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
                throw Wire.malformed();
            var features = Wire.requiredObject(wire.request("capabilities", new JsonObject(), wire,
                    connectTimeout, null), "features");
            Map<String, Boolean> flags = new LinkedHashMap<>();
            for (var name : features.keySet()) flags.put(name, Wire.requiredBoolean(features, name));
            return new JdbcSession(wire, url, user, version, id, flags, queryTimeout).handle;
        } catch (IOException e) {
            throw Wire.malformed();
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
        var common = JdbcProxy.common(proxy, method, args, "Connection");
        if (common != JdbcProxy.UNHANDLED) return common;
        String name = method.getName();
        if (name.equals("close")) { wire.close(); return null; }
        if (name.equals("isClosed")) return wire.isClosed();
        if (name.equals("setClientInfo"))
            throw new java.sql.SQLClientInfoException("Client information is unsupported", null);
        if (name.equals("abort")) {
            if (!(args[0] instanceof Executor executor)) throw Wire.invalid("Abort requires an executor");
            executor.execute(wire::close);
            return null;
        }
        if (name.equals("isValid")) {
            int timeout = (Integer) args[0];
            if (timeout < 0) throw Wire.invalid("Invalid validation timeout");
            if (wire.isClosed()) return false;
            try { call("health", new JsonObject(), this, timeout, null); return true; }
            catch (SQLException invalid) { return false; }
        }
        requireOpen();
        return switch (name) {
            case "createStatement" -> {
                resultOptions(args, 0);
                yield JdbcStatement.make(this, null, false);
            }
            case "prepareStatement", "prepareCall" -> {
                if (args.length == 2) throw ValueCodec.unsupported("generated keys");
                resultOptions(args, 1);
                boolean routine = name.equals("prepareCall");
                yield JdbcStatement.make(this, SqlText.parse((String) args[0], routine), routine);
            }
            case "nativeSQL" -> SqlText.parse((String) args[0], false).wire();
            case "getAutoCommit" -> autoCommit;
            case "setAutoCommit" -> {
                boolean next = (Boolean) args[0];
                if (next != autoCommit && transaction) commit(this, 0);
                autoCommit = next;
                yield null;
            }
            case "commit" -> {
                if (autoCommit) throw JdbcProxy.state("Commit requires manual transaction mode");
                if (transaction) commit(this, 0);
                yield null;
            }
            case "rollback" -> {
                if (autoCommit) throw JdbcProxy.state("Rollback requires manual transaction mode");
                if (args == null) { if (transaction) rollback(this, 0); }
                else changeSavepoint("tx_rollback_to", args[0], false);
                yield null;
            }
            case "setSavepoint" -> savepoint(args == null ? null : (String) args[0]);
            case "releaseSavepoint" -> { changeSavepoint("tx_release", args[0], true); yield null; }
            case "getMetaData" -> JdbcMetadata.database(this);
            case "isReadOnly" -> false;
            case "setReadOnly" -> { if ((Boolean) args[0]) throw ValueCodec.unsupported(name); yield null; }
            case "getCatalog" -> "main";
            case "setCatalog" -> { if (!"main".equals(args[0])) throw ValueCodec.unsupported(name); yield null; }
            case "getSchema" -> "public";
            case "setSchema" -> { if (!"public".equals(args[0])) throw ValueCodec.unsupported(name); yield null; }
            case "getTransactionIsolation" -> transactionIsolation();
            case "setTransactionIsolation" -> {
                if (!supportsIsolation((Integer) args[0])) throw ValueCodec.unsupported(name);
                yield null;
            }
            case "getHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT;
            case "setHoldability" -> {
                if ((Integer) args[0] != ResultSet.CLOSE_CURSORS_AT_COMMIT) throw ValueCodec.unsupported(name);
                yield null;
            }
            case "getNetworkTimeout" -> networkTimeout;
            case "setNetworkTimeout" -> {
                if (!(args[0] instanceof Executor) || (Integer) args[1] < 0 || (Integer) args[1] > 120_000)
                    throw Wire.invalid("Invalid network timeout");
                networkTimeout = (Integer) args[1];
                yield null;
            }
            case "getWarnings" -> (SQLWarning) null;
            case "clearWarnings", "beginRequest" -> null;
            case "resetSession", "endRequest" -> { reset(); yield null; }
            case "sessionId" -> sessionId;
            case "capabilities" -> capabilities;
            case "catalogObjects" -> catalog();
            case "transactionOutcome" -> outcome((String) args[0]);
            case "cancelQuery" -> cancelQuery((String) args[0], (Long) args[1]);
            case "predict" -> predict((String) args[0], args[1]);
            case "jobStatus" -> job((String) args[0]);
            case "historyRead" -> history((String) args[0], (String) args[1], (String) args[2]);
            case "explainDecision" -> decision((String) args[0], (String) args[1], (String) args[2]);
            case "adminHealth" -> adminHealth();
            default -> throw ValueCodec.unsupported(name);
        };
    }

    private static void resultOptions(Object[] args, int start) throws SQLException {
        if (args == null || args.length == start) return;
        if (args.length < start + 2 || (Integer) args[start] != ResultSet.TYPE_FORWARD_ONLY
                || (Integer) args[start + 1] != ResultSet.CONCUR_READ_ONLY
                || args.length > start + 2 && (Integer) args[start + 2] != ResultSet.CLOSE_CURSORS_AT_COMMIT)
            throw ValueCodec.unsupported("result set mode");
    }

    void requireOpen() throws SQLException { if (wire.isClosed()) throw JdbcProxy.closed("Connection"); }
    boolean isClosed() { return wire.isClosed(); }
    void cancel(Object owner) { wire.cancel(owner); }
    void closeWire() { wire.close(); }

    boolean supportsFullSql() { return capabilities.getOrDefault(FULL_SQL, false); }
    boolean durableWireTransactions() { return capabilities.getOrDefault(DURABLE_TRANSACTIONS, false); }
    boolean supportsTransactions() { return supportsFullSql() || durableWireTransactions(); }
    int transactionIsolation() {
        return supportsTransactions() ? Connection.TRANSACTION_SERIALIZABLE : Connection.TRANSACTION_NONE;
    }
    boolean supportsIsolation(int level) {
        return supportsTransactions() && level == Connection.TRANSACTION_SERIALIZABLE;
    }

    JsonObject call(String op, JsonObject body, Object owner, int seconds, String token) throws SQLException {
        int timeout = networkTimeout > 0 ? networkTimeout : baseTimeout;
        if (seconds > 0) timeout = (int) Math.min(timeout, 1000L * seconds);
        return wire.request(op, body, owner, timeout, token);
    }

    private JsonObject simple(String op, JsonObject body, String capability) throws SQLException {
        if (!capabilities.getOrDefault(capability, false))
            throw ValueCodec.unsupported("missing Server capability " + capability);
        return call(op, body, this, 0, null);
    }

    void beforeStatement(SqlText sql) throws SQLException {
        requireOpen();
        if (cursor != null) throw JdbcProxy.state("Close the current result before executing another statement");
        if (supportsFullSql()) return;
        if (durableWireTransactions() && ("SELECT".equals(sql.verb()) || sql.isBasicDml())) return;
        if (sql.kind() == SqlText.Kind.READ && autoCommit && capabilities.getOrDefault("wire_sql_basic_v1", false))
            return;
        throw ValueCodec.unsupported("Server SQL");
    }

    boolean start(SqlText.Kind kind, Object owner, int timeout) throws SQLException {
        if (!autoCommit || kind == SqlText.Kind.ROUTINE) {
            if (!transaction) begin(owner, timeout);
            return autoCommit;
        }
        return false;
    }

    boolean autoCommit() { return autoCommit; }

    private void begin(Object owner, int timeout) throws SQLException {
        call("tx_begin", new JsonObject(), owner, timeout, null);
        transaction = true;
        transactionGeneration++;
    }

    void register(ServerRows active) {
        cursor = active;
    }

    void release(ServerRows active) {
        if (cursor == active) cursor = null;
    }

    void finishOwned(boolean success, Object owner, int timeout) throws SQLException {
        if (!transaction || wire.isClosed()) return;
        if (success) commit(owner, timeout);
        else rollback(owner, timeout);
    }

    private void commit(Object owner, int timeout) throws SQLException {
        closeCursor(true);
        if (!transaction) return;
        String token = Wire.token();
        try {
            var reply = call("tx_commit", Wire.object("request_token", token), owner, timeout, token);
            if (durableWireTransactions()
                    && (!"committed".equals(Wire.requiredString(reply, "status"))
                        || Wire.requiredString(reply, "lsn").isEmpty()))
                throw new IOException("Invalid commit receipt");
        } catch (IOException invalidReceipt) {
            closeWire();
            throw Wire.malformed(token);
        } catch (SdpException failedCommit) {
            if (durableWireTransactions()) {
                if (failedCommit.isOutcomeUnknown()) closeWire();
                else if (!isClosed()) {
                    try {
                        call("tx_rollback", new JsonObject(), owner, timeout, null);
                    } catch (SQLException failedRollback) {
                        failedCommit.addSuppressed(failedRollback);
                        closeWire();
                    }
                }
            }
            throw failedCommit;
        } finally {
            clearTransaction();
        }
    }

    private void rollback(Object owner, int timeout) throws SQLException {
        closeCursor(false);
        if (!transaction) return;
        try {
            call("tx_rollback", new JsonObject(), owner, timeout, null);
        } finally {
            clearTransaction();
        }
    }

    private void clearTransaction() {
        transaction = false;
        transactionGeneration++;
        savepoints.clear();
    }

    private void closeCursor(boolean success) throws SQLException {
        var active = cursor;
        if (active != null) {
            try { active.finish(success); } finally { active.invalidate(); }
        }
    }

    private void reset() throws SQLException {
        call("session_reset", new JsonObject(), this, 0, null);
        if (cursor != null) cursor.invalidate();
        cursor = null;
        clearTransaction();
        autoCommit = true;
        networkTimeout = 0;
    }

    private Savepoint savepoint(String label) throws SQLException {
        if (autoCommit) throw JdbcProxy.state("Savepoint requires manual transaction mode");
        if (!supportsTransactions()) throw ValueCodec.unsupported("Server transactions");
        if (cursor != null) throw JdbcProxy.state("Close the current result before creating a savepoint");
        if (label != null && !label.matches("[a-zA-Z0-9_]{1,64}")) throw Wire.invalid("Invalid savepoint name");
        if (!transaction) begin(this, 0);
        int id = ++nextSavepoint;
        String name = label == null ? "jdbc_" + id : label;
        call("tx_savepoint", Wire.object("name", name), this, 0, null);
        var marker = new Save(this, transactionGeneration, id, label, name);
        savepoints.add(marker);
        return marker;
    }

    private void changeSavepoint(String op, Object candidate, boolean release) throws SQLException {
        if (autoCommit || !transaction || !(candidate instanceof Save save)
                || save.owner != this || save.generation != transactionGeneration || !savepoints.contains(save))
            throw JdbcProxy.state("Savepoint is not active on this connection");
        closeCursor(false);
        int position = savepoints.indexOf(save);
        call(op, Wire.object("name", save.wireName), this, 0, null);
        savepoints.subList(release ? position : position + 1, savepoints.size()).clear();
    }

    private SdpConnection.Outcome outcome(String token) throws SQLException {
        if (token == null || !token.matches("[0-9a-f]{32}")) throw Wire.invalid("Invalid outcome token");
        var result = simple("tx_outcome", Wire.object("request_token", token),
                supportsFullSql() ? FULL_SQL : DURABLE_TRANSACTIONS);
        try {
            return switch (Wire.requiredString(result, "status")) {
                case "committed" -> new SdpConnection.Outcome(SdpConnection.OutcomeStatus.COMMITTED,
                        Wire.requiredString(result, "lsn"));
                case "unknown" -> new SdpConnection.Outcome(SdpConnection.OutcomeStatus.UNKNOWN, null);
                default -> throw Wire.malformed();
            };
        } catch (IOException e) { throw Wire.malformed(); }
    }

    private boolean cancelQuery(String session, long requestId) throws SQLException {
        if (session == null || !session.matches("[0-9a-f]{32}") || requestId < 0 || requestId > 0xffff_ffffL)
            throw Wire.invalid("Invalid control request");
        var result = simple("query_cancel", Wire.object("session_id", session, "request_id", requestId),
                "sql_security_v1");
        try { return Wire.requiredBoolean(result, "accepted"); }
        catch (IOException e) { throw Wire.malformed(); }
    }

    List<SdpConnection.CatalogObject> catalog() throws SQLException {
        if (!autoCommit && !transaction) begin(this, 0);
        var reply = simple("metadata", new JsonObject(), "sql_crud_v1");
        List<SdpConnection.CatalogObject> objects = new ArrayList<>();
        try {
            for (var item : reply.getAsJsonArray("objects")) {
                var element = item.getAsJsonObject();
                List<String> names = new ArrayList<>();
                element.getAsJsonArray("qualified_name").forEach(name -> names.add(name.getAsString()));
                List<SdpConnection.Column> columns = new ArrayList<>();
                for (var column : element.getAsJsonArray("columns")) {
                    var value = column.getAsJsonObject();
                    columns.add(new SdpConnection.Column(Wire.requiredString(value, "name"),
                            Wire.requiredString(value, "type"), Math.toIntExact(Wire.requiredInteger(value, "ordinal")),
                            Wire.requiredBoolean(value, "nullable")));
                }
                objects.add(new SdpConnection.CatalogObject(Wire.requiredString(element, "id"),
                        Math.toIntExact(Wire.requiredInteger(element, "kind")), names,
                        Wire.requiredInteger(element, "schema_version"), columns));
            }
            return List.copyOf(objects);
        } catch (IOException | RuntimeException e) { throw Wire.malformed(); }
    }

    private SdpConnection.Prediction predict(String model, Object input) throws SQLException {
        if (model == null || model.isEmpty() || !(input instanceof Map<?, ?> values))
            throw Wire.invalid("Model name and features are required");
        var features = new JsonObject();
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof Number value)
                    || !Double.isFinite(value.doubleValue()))
                throw Wire.invalid("Invalid numeric feature");
            features.addProperty(key, value);
        }
        var result = simple("model_predict", Wire.object("name", model, "features", features), "supervised_ml_v1");
        if (!result.has("prediction")) throw Wire.malformed();
        return new SdpConnection.Prediction(result.get("prediction"), result);
    }

    private SdpConnection.Job job(String id) throws SQLException {
        var result = simple("job_status", Wire.object("job_id", id), "jobs_v1");
        try { return new SdpConnection.Job(Wire.requiredString(result, "job_id"),
                Wire.requiredString(result, "state"), result); }
        catch (IOException e) { throw Wire.malformed(); }
    }

    private SdpConnection.History history(String target, String revision, String rowId) throws SQLException {
        var body = Wire.object("target", target, "revision", revision);
        if (rowId != null) body.addProperty("row_id", rowId);
        return new SdpConnection.History(target, revision, simple("history_read", body, "history_v1"));
    }

    private SdpConnection.Decision decision(String table, String event, String rowId) throws SQLException {
        if (event == null || !List.of("INSERT", "UPDATE", "DELETE").contains(event.toUpperCase(Locale.ROOT)))
            throw Wire.invalid("Unknown Decision event");
        var body = Wire.object("target", table, "event", event.toLowerCase(Locale.ROOT));
        if (rowId != null) body.addProperty("row_id", rowId);
        var result = simple("decision_explain", body, "decision_explain_v1");
        try { return new SdpConnection.Decision(Wire.requiredBoolean(result, "allowed"),
                Wire.optionalString(result, "reason"), result); }
        catch (IOException e) { throw Wire.malformed(); }
    }

    private SdpConnection.AdminHealth adminHealth() throws SQLException {
        var result = simple("admin_health", new JsonObject(), "health_center_v1");
        try { return new SdpConnection.AdminHealth(Wire.requiredString(result, "instance_id"),
                Wire.requiredObject(result, "checks")); }
        catch (IOException e) { throw Wire.malformed(); }
    }
}
