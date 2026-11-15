package com.stardataparadise.sdk;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Public JDBC connection plus typed operations advertised by Server capabilities. */
public interface SdpConnection extends Connection {
    enum OutcomeStatus { COMMITTED, UNKNOWN }
    record Outcome(OutcomeStatus status, String lsn) { }
    record Column(String name, String type, int ordinal, boolean nullable) { }
    record CatalogObject(String id, int kind, List<String> qualifiedName, long schemaVersion, List<Column> columns) {
        public CatalogObject {
            qualifiedName = List.copyOf(qualifiedName);
            columns = List.copyOf(columns);
        }
    }
    record Prediction(JsonElement value, JsonObject attributes) {
        public Prediction { value = value.deepCopy(); attributes = attributes.deepCopy(); }
        @Override public JsonElement value() { return value.deepCopy(); }
        @Override public JsonObject attributes() { return attributes.deepCopy(); }
    }
    record Job(String id, String state, JsonObject details) {
        public Job { details = details.deepCopy(); }
        @Override public JsonObject details() { return details.deepCopy(); }
    }
    record History(String target, String revision, JsonObject details) {
        public History { details = details.deepCopy(); }
        @Override public JsonObject details() { return details.deepCopy(); }
    }
    record Decision(boolean allowed, String reason, JsonObject details) {
        public Decision { details = details.deepCopy(); }
        @Override public JsonObject details() { return details.deepCopy(); }
    }
    record AdminHealth(String instanceId, JsonObject checks) {
        public AdminHealth { checks = checks.deepCopy(); }
        @Override public JsonObject checks() { return checks.deepCopy(); }
    }

    String sessionId() throws SQLException;
    Map<String, Boolean> capabilities() throws SQLException;
    List<CatalogObject> catalogObjects() throws SQLException;
    void resetSession() throws SQLException;
    Outcome transactionOutcome(String token) throws SQLException;
    boolean cancelQuery(String sessionId, long requestId) throws SQLException;
    Prediction predict(String model, Map<String, ? extends Number> features) throws SQLException;
    Job jobStatus(String jobId) throws SQLException;
    History historyRead(String target, String revision, String rowId) throws SQLException;
    Decision explainDecision(String table, String event, String rowId) throws SQLException;
    AdminHealth adminHealth() throws SQLException;
}
