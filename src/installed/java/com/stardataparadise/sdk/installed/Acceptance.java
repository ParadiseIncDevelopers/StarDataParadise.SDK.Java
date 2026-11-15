package com.stardataparadise.sdk.installed;

import com.google.gson.JsonParser;
import com.stardataparadise.sdk.SdkVersion;
import com.stardataparadise.sdk.SdpConnection;
import com.stardataparadise.sdk.SdpException;
import com.stardataparadise.sdk.SdpValues;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Acceptance {
    private static final BigInteger COEFFICIENT = new BigInteger("-123456789012345678901234567890123400");
    private static final BigDecimal AMOUNT = new BigDecimal(COEFFICIENT, 4);
    private static final byte[] PAYLOAD = HexFormat.of().parseHex("00017f80feff");
    private static final String NOTE = "İstanbul'un notu; a\0b";
    private static final String BOUND = "x'); DELETE FROM sdk_seed; -- İ\0z";

    private Acceptance() { }

    public static void main(String[] args) throws Exception {
        try (var secrets = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String owner = secrets.readLine();
            String guest = secrets.readLine();
            require(owner != null && guest != null, "stdin credentials");
            golden();
            try (var admin = connect(env("PORT"), env("USER"), owner)) {
                require(admin.isValid(3) && admin.getAutoCommit(), "JDBC connection");
                seededRows(admin);
                streamRows(admin);
                writesAndSavepoints(admin);
                procedures(admin);
                extensions(admin);
                expectUnsupported(() -> admin.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY));
                try (var statement = admin.createStatement()) {
                    expectUnsupported(statement::executeBatch);
                }
            }
            authorization(guest, owner);
            cancelled(owner);
            lostCommit(owner);
        }
        System.out.println("SDP_MANAGED_OK");
    }

    private static void golden() throws Exception {
        require("0.2.0".equals(SdkVersion.implementationVersion()), "installed JAR version");
        require(SdkVersion.CONTRACT_SHA256.equals(env("CONTRACT_SHA256")), "installed wire digest");
        try (var file = Files.newBufferedReader(Path.of(env("GOLDEN")))) {
            var decimal = JsonParser.parseReader(file).getAsJsonObject().getAsJsonObject("body")
                    .getAsJsonArray("values").get(0).getAsJsonObject();
            require(COEFFICIENT.equals(decimal.get("unscaled").getAsBigInteger())
                    && decimal.get("scale").getAsInt() == 4, "wire golden decimal");
        }
    }

    private static void seededRows(Connection connection) throws Exception {
        try (var prepared = connection.prepareStatement("SELECT id,note,amount,stamp,payload FROM sdk_seed WHERE id >= ? ORDER BY id")) {
            prepared.setLong(1, 1);
            try (var rows = prepared.executeQuery()) {
                require(rows.getMetaData().getColumnCount() == 5 && rows.next() && rows.getLong("id") == 1
                        && BigInteger.ONE.equals(rows.getObject("id", BigInteger.class)), "seed columns");
                require(NOTE.equals(rows.getString("note")) && AMOUNT.equals(rows.getBigDecimal("amount"))
                        && COEFFICIENT.equals(rows.getBigDecimal("amount").unscaledValue()), "decimal/text round trip");
                var timestamp = rows.getObject("stamp", SdpValues.Timestamp.class);
                require(timestamp.seconds() == -1 && timestamp.nanos() == 999_999_999 && timestamp.offsetMinutes() == 180,
                        "timestamp fields");
                require(OffsetDateTime.parse("1970-01-01T02:59:59.999999999+03:00")
                        .equals(rows.getObject("stamp", OffsetDateTime.class)), "timestamp offset");
                try (var binary = rows.getBinaryStream("payload")) {
                    require(Arrays.equals(PAYLOAD, binary.readAllBytes()), "binary stream");
                }
                require(rows.next() && rows.getLong("id") == 2, "second row");
                for (int column = 2; column <= 5; column++)
                    require(rows.getObject(column) == null && rows.wasNull(), "typed NULL");
                require(!rows.next(), "seed cardinality");
            }
        }
        try (var tables = connection.getMetaData().getTables(null, "public", "sdk_seed", new String[]{"TABLE"})) {
            require(tables.next() && "sdk_seed".equals(tables.getString("TABLE_NAME")) && !tables.next(), "JDBC table metadata");
        }
        try (var columns = connection.getMetaData().getColumns(null, "public", "sdk_seed", "amount")) {
            require(columns.next() && columns.getInt("DATA_TYPE") == Types.DECIMAL
                    && columns.getInt("ORDINAL_POSITION") == 3, "JDBC column metadata");
        }
    }

    private static void streamRows(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.setFetchSize(17);
            try (var rows = statement.executeQuery("SELECT id, outcome_value FROM sdk_bulk ORDER BY id")) {
                int count = 0;
                while (rows.next()) {
                    count++;
                    require(rows.getLong(1) == count && rows.getLong(2) == 3L * count + 7, "paged rows order");
                }
                require(count == Integer.parseInt(env("BULK_ROWS")), "paged rows count");
            }
        }
    }

    private static void writesAndSavepoints(SdpConnection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE jdbc_write (id BIGINT PRIMARY KEY, note TEXT, amount DECIMAL(38,4), payload BLOB)");
        }
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE jdbc_local_catalog (id BIGINT)");
        }
        require(connection.catalogObjects().stream().anyMatch(Acceptance::isLocalCatalog),
                "catalog uses active JDBC transaction");
        connection.rollback();
        require(connection.catalogObjects().stream().noneMatch(Acceptance::isLocalCatalog),
                "rolled-back DDL removed from catalog");
        connection.rollback();
        nestedSavepoints(connection);
        insert(connection, 1);
        var savepoint = connection.setSavepoint("jdbc_keep");
        insert(connection, 2);
        connection.rollback(savepoint);
        connection.releaseSavepoint(savepoint);
        connection.commit();
        require(count(connection, "jdbc_write") == 1, "savepoint leaves exactly one row");
        connection.setAutoCommit(true);
        try (var query = connection.prepareStatement("SELECT note,amount,payload FROM jdbc_write WHERE id=?")) {
            query.setLong(1, 1);
            try (var result = query.executeQuery()) {
                require(result.next() && BOUND.equals(result.getString(1)) && AMOUNT.equals(result.getBigDecimal(2))
                        && Arrays.equals(PAYLOAD, result.getBytes(3)) && !result.next(), "safe bind and binary stream upload");
            }
        }
        try (var update = connection.prepareStatement("UPDATE jdbc_write SET note=? WHERE id=?")) {
            update.setNull(1, Types.VARCHAR);
            update.setLong(2, 1);
            require(update.executeLargeUpdate() == 1, "autocommit update");
        }
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            require(statement.executeUpdate("DELETE FROM jdbc_write WHERE id=1") == 1, "transaction delete");
        }
        connection.rollback();
        require(count(connection, "jdbc_write") == 1, "rollback restored delete");
        connection.rollback();
        insert(connection, 3);
        connection.resetSession();
        require(connection.getAutoCommit() && count(connection, "jdbc_write") == 1, "session reset rolled back");
    }

    private static boolean isLocalCatalog(SdpConnection.CatalogObject object) {
        return object.kind() == 3 && object.qualifiedName().size() == 3
                && "jdbc_local_catalog".equals(object.qualifiedName().get(2));
    }

    private static void nestedSavepoints(Connection connection) throws SQLException {
        var outer = connection.setSavepoint();
        var released = connection.setSavepoint("jdbc_nested");
        connection.releaseSavepoint(outer);
        var replacement = connection.setSavepoint("jdbc_nested");
        try {
            connection.rollback(released);
            throw new AssertionError("released savepoint could affect the replacement");
        } catch (SQLException expected) {
            require("25000".equals(expected.getSQLState()), "invalidated savepoint state");
        }
        connection.rollback(replacement);
        connection.releaseSavepoint(replacement);
    }

    private static void procedures(Connection connection) throws Exception {
        try (var call = connection.prepareCall("{call sdk_list(?,?)}")) {
            call.setLong(1, 1);
            call.registerOutParameter(2, Types.BIGINT);
            require(call.execute() && call.getLong(2) == 1 && !call.wasNull(), "registered procedure OUT");
            require(call.getMoreResults(), "first REF CURSOR");
            try (var found = call.getResultSet()) {
                require(found.next() && found.getLong(1) == 1 && !found.next(), "found cursor row");
            }
            require(call.getMoreResults(), "second REF CURSOR");
            try (var all = call.getResultSet()) {
                require(all.next() && all.getLong(1) == 1 && all.next() && all.getLong(1) == 2 && !all.next(),
                        "all cursor rows");
            }
            require(!call.getMoreResults(), "procedure result cursor exhausted");
        }
        try (var call = connection.prepareCall("CALL sdk_echo(?,?)")) {
            call.setObject(1, new SdpValues.RecordValue(Map.of("note", NOTE, "amount", AMOUNT)));
            call.setObject(2, new SdpValues.CollectionValue(Arrays.asList(9L, null, PAYLOAD)));
            call.registerOutParameter(1, Types.STRUCT);
            call.registerOutParameter(2, Types.ARRAY);
            require(call.execute(), "compound procedure execute");
            var record = call.getObject(1, SdpValues.RecordValue.class);
            var items = call.getObject(2, SdpValues.CollectionValue.class).items();
            require(record.fields().equals(Map.of("note", NOTE, "amount", AMOUNT))
                    && items.size() == 3 && items.get(0).equals(9L) && items.get(1) == null
                    && Arrays.equals(PAYLOAD, (byte[]) items.get(2)), "compound IN OUT round trip");
        }
    }

    private static void extensions(SdpConnection connection) throws Exception {
        var history = connection.historyRead("sdk_seed", env("SEED_LSN"), null);
        var rows = history.details().getAsJsonArray("rows");
        require(rows.size() == 2, "History revision rows");
        String rowId = rows.get(0).getAsJsonObject().get("row_id").getAsString();
        require(connection.explainDecision("sdk_seed", "DELETE", rowId).allowed(), "Decision inspection");
        require("succeeded".equals(connection.jobStatus(env("JOB_ID")).state()), "typed job status");
        require(Math.abs(connection.predict(env("MODEL"), Map.of("reading", 17)).value().getAsDouble() - 58) < .0001,
                "typed model prediction");
        require(connection.adminHealth().checks().has("storage"), "typed administrator health");
    }

    private static void authorization(String guestPassword, String ownerPassword) throws Exception {
        try (var guest = connect(env("PORT"), env("GUEST"), guestPassword)) {
            require(count(guest, "sdk_seed") == 2, "guest SELECT grant");
            expectCode(() -> guest.historyRead("sdk_seed", env("SEED_LSN"), null), 1006);
            expectCode(() -> guest.predict(env("MODEL"), Map.of("reading", 17)), 1006);
        }
        var options = properties(env("USER"), ownerPassword);
        options.setProperty("tlsName", "wrong.invalid");
        expectCode(() -> DriverManager.getConnection(url(env("PORT")), options), 1011);
        expectCode(() -> connect(env("PORT"), env("USER"), "invalid-test-credential"), 1005);
    }

    private static void cancelled(String ownerPassword) throws Exception {
        try (var holder = connect(env("PORT"), env("USER"), ownerPassword);
             var victim = connect(env("PORT"), env("USER"), ownerPassword);
             var statement = victim.createStatement()) {
            holder.setAutoCommit(false);
            require(count(holder, "sdk_seed") == 2, "holder acquired transaction");
            var executor = Executors.newSingleThreadExecutor();
            try {
                var blocked = executor.submit(() -> {
                    try (var ignored = statement.executeQuery("SELECT id FROM sdk_seed")) { return null; }
                    catch (SQLException cancelled) { return cancelled; }
                });
                TimeUnit.MILLISECONDS.sleep(100);
                require(!blocked.isDone(), "victim waits for holder lock");
                for (int attempt = 0; attempt < 20 && !blocked.isDone(); attempt++) {
                    statement.cancel();
                    TimeUnit.MILLISECONDS.sleep(20);
                }
                var cancellation = blocked.get(5, TimeUnit.SECONDS);
                require(cancellation != null && cancellation.getErrorCode() == 1009 && victim.isClosed(),
                        "statement cancellation ended the in-flight socket");
                try (var timed = connect(env("PORT"), env("USER"), ownerPassword);
                     var query = timed.createStatement()) {
                    query.setQueryTimeout(1);
                    expectCode(() -> query.executeQuery("SELECT id FROM sdk_seed"), 1008);
                    require(timed.isClosed(), "request timeout ended the in-flight socket");
                }
                holder.rollback();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static void lostCommit(String ownerPassword) throws Exception {
        String token;
        try (var fault = connect(env("FAULT_PORT"), env("USER"), ownerPassword)) {
            fault.setAutoCommit(false);
            insert(fault, 4);
            try {
                fault.commit();
                throw new AssertionError("lost commit returned success");
            } catch (SdpException error) {
                require(error.isOutcomeUnknown() && !error.isRetryable()
                        && error.requestToken().matches("[0-9a-f]{32}"), "ambiguous commit token");
                token = error.requestToken();
            }
        }
        try (var normal = connect(env("PORT"), env("USER"), ownerPassword)) {
            require(normal.transactionOutcome(token).status() == SdpConnection.OutcomeStatus.COMMITTED,
                    "durable outcome lookup");
            require(count(normal, "jdbc_write") == 2, "lost COMMIT made exactly one write");
        }
    }

    private static void insert(Connection connection, int id) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO jdbc_write VALUES (?,?,?,?)")) {
            insert.setObject(1, BigInteger.valueOf(id), Types.BIGINT);
            insert.setString(2, BOUND);
            insert.setObject(3, AMOUNT);
            insert.setBinaryStream(4, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length);
            require(insert.executeUpdate() == 1, "prepared INSERT cardinality");
        }
    }

    private static long count(Connection connection, String controlledTable) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + controlledTable)) {
            require(rows.next(), "count result");
            return rows.getLong(1);
        }
    }

    private static SdpConnection connect(String port, String user, String password) throws SQLException {
        return DriverManager.getConnection(url(port), properties(user, password)).unwrap(SdpConnection.class);
    }

    private static String url(String port) { return "jdbc:sdp://" + env("HOST") + ":" + port; }

    private static Properties properties(String user, String password) {
        var properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("caFile", env("CA"));
        properties.setProperty("tlsName", env("TLS_NAME"));
        return properties;
    }

    private static void expectCode(SqlAction action, int expected) throws SQLException {
        try { action.perform(); }
        catch (SdpException denied) {
            require(denied.getErrorCode() == expected, "expected SQL error " + expected + ", received " + denied.getErrorCode());
            return;
        }
        throw new AssertionError("missing expected SQL error " + expected);
    }

    private static void expectUnsupported(SqlAction action) throws SQLException {
        try { action.perform(); }
        catch (SQLFeatureNotSupportedException error) {
            require("0A000".equals(error.getSQLState()), "JDBC unsupported SQLState");
            return;
        }
        throw new AssertionError("expected an unsupported JDBC operation");
    }

    private static String env(String name) {
        String value = System.getenv("SDP_SDK_" + name);
        require(value != null && !value.isBlank(), "fixture variable " + name);
        return value;
    }

    private static void require(boolean condition, String subject) {
        if (!condition) throw new AssertionError(subject);
    }

    private interface SqlAction { void perform() throws SQLException; }
}
