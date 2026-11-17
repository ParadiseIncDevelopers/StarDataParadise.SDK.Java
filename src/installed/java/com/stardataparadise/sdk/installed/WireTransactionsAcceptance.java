package com.stardataparadise.sdk.installed;

import com.stardataparadise.sdk.SdpConnection;
import com.stardataparadise.sdk.SdpException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Properties;

/** Installed JAR acceptance against the fork's loopback wire bridge. */
public final class WireTransactionsAcceptance {
    private static final String NOTE = "İstanbul'un notu \0";
    private static final BigDecimal AMOUNT = new BigDecimal("-12345678901234567890123456789012.3400");
    private static final byte[] PAYLOAD = HexFormat.of().parseHex("00017f80feff");
    private static final String UNKNOWN_TOKEN = "ffffffffffffffffffffffffffffffff";

    private WireTransactionsAcceptance() { }

    public static void main(String[] args) throws Exception {
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String password = input.readLine();
            require(password != null, "stdin credentials");
            if (args.length == 1 && "basic".equals(args[0])) {
                basic(password);
                System.out.println("SDP_JDBC_BASIC_OK");
                return;
            }
            try (var connection = connect("PORT", password);
                 var observer = connect("PORT", password)) {
                verifyCapabilities(connection);
                autoCommit(connection, observer);
                manual(connection, observer);
                verifyDisconnectRollback(password, observer);
                require(connection.transactionOutcome(UNKNOWN_TOKEN).status() == SdpConnection.OutcomeStatus.UNKNOWN,
                        "unknown outcome");
            }
            lostWrite(password, "EXECUTE_FAULT_PORT", 10, false);
            lostWrite(password, "COMMIT_FAULT_PORT", 11, true);
        }
        System.out.println("SDP_JDBC_TRANSACTIONS_OK");
    }

    private static void basic(String password) throws Exception {
        try (var connection = connect("PORT", password);
             var statement = connection.createStatement()) {
            var features = connection.capabilities();
            require(Boolean.TRUE.equals(features.get("wire_sql_basic_v1"))
                    && !Boolean.TRUE.equals(features.get("wire_sql_transactions_v1"))
                    && !Boolean.TRUE.equals(features.get("sql_crud_v1")), "basic capabilities");
            require(!connection.getMetaData().supportsTransactions()
                    && !connection.getMetaData().supportsSavepoints()
                    && connection.getTransactionIsolation() == Connection.TRANSACTION_NONE, "basic metadata");
            require(count(connection, 0) == 0, "basic read");
            expectUnsupported(() -> statement.executeUpdate("DELETE FROM jdbc_wire"));
            expectUnsupported(() -> connection.transactionOutcome(UNKNOWN_TOKEN));
            connection.setAutoCommit(false);
            expectUnsupported(() -> statement.executeQuery("SELECT id FROM jdbc_wire"));
            expectUnsupported(connection::setSavepoint);
            connection.setAutoCommit(true);
            require(connection.isValid(3), "basic connection after rejected writes");
        }
    }

    private static void verifyCapabilities(SdpConnection connection) throws SQLException {
        var features = connection.capabilities();
        require(Boolean.TRUE.equals(features.get("wire_sql_basic_v1"))
                && Boolean.TRUE.equals(features.get("wire_sql_transactions_v1"))
                && !Boolean.TRUE.equals(features.get("sql_crud_v1")), "scoped transaction capabilities");
        var metadata = connection.getMetaData();
        require(connection.isValid(3) && connection.getAutoCommit(), "connect with autocommit");
        require(metadata.supportsTransactions() && metadata.supportsSavepoints()
                && !metadata.supportsStoredProcedures()
                && metadata.supportsTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE)
                && !metadata.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED)
                && metadata.getDefaultTransactionIsolation() == Connection.TRANSACTION_SERIALIZABLE
                && connection.getTransactionIsolation() == Connection.TRANSACTION_SERIALIZABLE, "transaction metadata");
        try (var statement = connection.createStatement()) {
            expectUnsupported(() -> statement.executeUpdate("CREATE TABLE jdbc_unsupported (id BIGINT)"));
        }
    }

    private static void autoCommit(SdpConnection connection, Connection observer) throws SQLException {
        insert(connection, 1);
        try (var select = observer.prepareStatement("SELECT note, amount, payload FROM jdbc_wire WHERE id = ?")) {
            select.setLong(1, 1);
            try (var rows = select.executeQuery()) {
                require(rows.next() && NOTE.equals(rows.getString(1)) && AMOUNT.equals(rows.getBigDecimal(2))
                        && Arrays.equals(PAYLOAD, rows.getBytes(3)) && !rows.next(), "typed autocommit INSERT");
            }
        }
        try {
            insert(connection, 1);
            throw new AssertionError("duplicate key was accepted");
        } catch (SdpException expected) {
            require(!expected.isOutcomeUnknown() && expected.requestToken() == null, "duplicate key is definitive");
        }
        try (var update = connection.prepareStatement("UPDATE jdbc_wire SET note = ? WHERE id = ?")) {
            update.setNull(1, java.sql.Types.VARCHAR);
            update.setLong(2, 1);
            require(update.executeLargeUpdate() == 1, "autocommit UPDATE");
            update.setLong(2, 0);
            require(update.executeLargeUpdate() == 0, "zero-row UPDATE");
        }
        try (var statement = observer.createStatement();
             var rows = statement.executeQuery("SELECT note FROM jdbc_wire WHERE id = 1")) {
            require(rows.next() && rows.getString(1) == null && rows.wasNull() && !rows.next(), "autocommit NULL");
        }
        insert(connection, 12);
        try (var statement = connection.createStatement()) {
            require(!statement.execute("DELETE FROM jdbc_wire WHERE id = 12")
                    && statement.getUpdateCount() == 1, "autocommit DELETE via Statement.execute");
        }
        require(count(observer, 12) == 0, "observe autocommit DELETE");
    }

    private static void manual(SdpConnection connection, Connection observer) throws SQLException {
        connection.setAutoCommit(false);
        insert(connection, 2);
        var marker = connection.setSavepoint("keep");
        insert(connection, 3);
        require(count(connection, 3) == 1 && count(observer, 2) == 0, "manual transaction visibility");
        connection.rollback(marker);
        connection.releaseSavepoint(marker);
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id FROM jdbc_wire WHERE id = 2")) {
            require(rows.next() && rows.getLong(1) == 2, "manual SELECT");
            connection.commit();
            require(rows.isClosed(), "commit closes cursor");
        }
        require(!connection.getAutoCommit() && count(observer, 2) == 1 && count(observer, 3) == 0,
                "manual commit and savepoint rollback");
        try (var statement = connection.createStatement()) {
            require(statement.executeUpdate("DELETE FROM jdbc_wire WHERE id = 1") == 1, "manual DELETE");
        }
        connection.rollback();
        require(count(observer, 1) == 1, "manual rollback");
        insert(connection, 4);
        connection.setAutoCommit(true);
        require(connection.getAutoCommit() && count(observer, 4) == 1, "setAutoCommit commits pending writes");
        connection.setAutoCommit(false);
        insert(connection, 5);
        connection.resetSession();
        require(connection.getAutoCommit() && count(observer, 5) == 0, "reset rolls back");
    }

    private static void verifyDisconnectRollback(String password, Connection observer) throws SQLException {
        try (var pending = connect("PORT", password)) {
            pending.setAutoCommit(false);
            insert(pending, 6);
        }
        require(count(observer, 6) == 0, "disconnect rolls back");
    }

    private static void lostWrite(String password, String port, int id, boolean manual) throws SQLException {
        String token;
        try (var fault = connect(port, password)) {
            fault.setAutoCommit(!manual);
            try {
                insert(fault, id);
                if (manual) fault.commit();
                throw new AssertionError("lost write returned success");
            } catch (SdpException error) {
                require(error.isOutcomeUnknown() && !error.isRetryable() && error.requestToken() != null
                        && error.requestToken().matches("[0-9a-f]{32}") && fault.isClosed(), "uncertain write token");
                token = error.requestToken();
            }
        }
        try (var recovered = connect("PORT", password)) {
            var outcome = recovered.transactionOutcome(token);
            require(outcome.status() == SdpConnection.OutcomeStatus.COMMITTED && !outcome.lsn().isEmpty()
                    && count(recovered, id) == 1, "durable outcome and single committed write after reconnect");
        }
    }

    private static void insert(Connection connection, int id) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO jdbc_wire VALUES (?,?,?,?)")) {
            insert.setLong(1, id);
            insert.setString(2, NOTE);
            insert.setBigDecimal(3, AMOUNT);
            insert.setBytes(4, PAYLOAD);
            require(insert.executeUpdate() == 1, "INSERT count");
        }
    }

    private static long count(Connection connection, int id) throws SQLException {
        try (var select = connection.prepareStatement("SELECT COUNT(*) FROM jdbc_wire WHERE id = ?")) {
            select.setLong(1, id);
            try (var rows = select.executeQuery()) {
                require(rows.next(), "count result");
                long count = rows.getLong(1);
                require(!rows.next(), "count cardinality");
                return count;
            }
        }
    }

    private static SdpConnection connect(String port, String password) throws SQLException {
        var props = new Properties();
        props.setProperty("user", env("USER"));
        props.setProperty("password", password);
        props.setProperty("caFile", env("CA"));
        props.setProperty("tlsName", "localhost");
        props.setProperty("queryTimeoutMs", "5000");
        return DriverManager.getConnection("jdbc:sdp://127.0.0.1:" + env(port), props).unwrap(SdpConnection.class);
    }

    private static String env(String name) {
        String value = System.getenv("SDP_JDBC_" + name);
        require(value != null && !value.isBlank(), "environment " + name);
        return value;
    }

    private static void expectUnsupported(Action action) throws SQLException {
        try {
            action.run();
            throw new AssertionError("unsupported operation was accepted");
        } catch (SQLFeatureNotSupportedException expected) {
            require("0A000".equals(expected.getSQLState()), "unsupported SQL state");
        }
    }

    private static void require(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    @FunctionalInterface
    private interface Action { void run() throws SQLException; }
}
