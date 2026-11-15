package com.stardataparadise.sdk;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

final class ServerRows {
    private final JdbcSession session;
    private final JdbcStatement owner;
    private final String id;
    private final boolean ownedTransaction;
    private final boolean canHaveMoreSets;
    private boolean serverEnd;
    private boolean settled;
    private Part part;

    final class Part implements JdbcRows.Source {
        private final int set;
        private final Deque<List<Object>> queue = new ArrayDeque<>();
        private List<JdbcColumn> columns;
        private String name;
        private int batchIndex;
        private boolean end;
        private boolean closed;

        Part(int set, List<JdbcColumn> columns) {
            this.set = set;
            this.columns = columns;
        }

        public List<JdbcColumn> columns() { return columns; }

        public List<Object> next() throws SQLException {
            if (closed) throw JdbcProxy.closed("Result set");
            if (settled && queue.isEmpty()) return null;
            if (!queue.isEmpty()) return queue.removeFirst();
            if (!end) fetch(this);
            if (!queue.isEmpty()) return queue.removeFirst();
            if (serverEnd) finish(true);
            return null;
        }

        @Override public void close() throws SQLException {
            if (closed) return;
            closed = true;
            queue.clear();
            if (!canHaveMoreSets && !settled) finish(true);
        }

        @Override public boolean isClosed() { return closed || session.isClosed(); }
    }

    ServerRows(JdbcSession session, JdbcStatement owner, JsonObject execution, boolean ownedTransaction,
               boolean canHaveMoreSets) throws SQLException {
        this.session = session;
        this.owner = owner;
        this.ownedTransaction = ownedTransaction;
        this.canHaveMoreSets = canHaveMoreSets;
        try {
            this.id = Wire.requiredString(execution, "result_id");
            this.part = new Part(0, JdbcColumn.parse(execution.getAsJsonArray("columns")));
        } catch (IOException | RuntimeException failure) {
            throw Wire.malformed();
        }
        session.register(this);
    }

    Part part() { return part; }

    List<Object> outputRow() throws SQLException {
        if (part.set != 0 || part.closed) throw JdbcProxy.state("Callable OUT values are unavailable");
        if (part.batchIndex == 0) fetch(part);
        if (!"out".equals(part.name) || part.queue.isEmpty())
            throw JdbcProxy.state("Procedure did not return registered OUT values");
        return new ArrayList<>(part.queue.peekFirst());
    }

    Part advance() throws SQLException {
        part.closed = true;
        part.queue.clear();
        if (settled) return null;
        while (!part.end) {
            fetch(part);
            part.queue.clear();
        }
        if (serverEnd) { finish(true); return null; }
        part = new Part(part.set + 1, List.of());
        fetch(part);
        return part;
    }

    private void fetch(Part current) throws SQLException {
        try {
            var reply = session.call("fetch", Wire.object("result_id", id, "batch_rows", owner.fetchSize()),
                    owner, owner.queryTimeout(), null);
            var batch = Wire.requiredObject(reply, "batch");
            if (Wire.requiredInteger(batch, "result_set") != current.set
                    || Wire.requiredInteger(batch, "batch_index") != current.batchIndex)
                throw Wire.malformed();
            if (current.batchIndex++ == 0) {
                List<JdbcColumn> stated = JdbcColumn.parse(batch.getAsJsonArray("columns"));
                if (current.set == 0 && !current.columns.equals(stated)) throw Wire.malformed();
                current.columns = stated;
                current.name = Wire.optionalString(batch, "set_name");
            }
            var rows = batch.getAsJsonArray("rows");
            if (rows.size() > owner.fetchSize()) throw Wire.malformed();
            for (var data : rows) {
                var raw = data.getAsJsonArray();
                if (raw.size() != current.columns.size()) throw Wire.malformed();
                List<Object> values = new ArrayList<>(raw.size());
                for (int i = 0; i < raw.size(); i++)
                    values.add(ValueCodec.decode(raw.get(i), current.columns.get(i).type()));
                current.queue.addLast(values);
            }
            current.end = Wire.requiredBoolean(batch, "end_of_set");
            serverEnd = Wire.requiredBoolean(batch, "end_of_results");
            if (serverEnd && !current.end || !current.end && rows.isEmpty()) throw Wire.malformed();
        } catch (IOException | RuntimeException failure) {
            throw cleanup(Wire.malformed());
        } catch (SQLException failure) {
            throw cleanup(failure);
        }
    }

    private SQLException cleanup(SQLException failure) {
        try { finish(false); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
        return failure;
    }

    void finish(boolean success) throws SQLException {
        if (settled) return;
        settled = true;
        session.release(this);
        SQLException pending = null;
        try {
            if (!serverEnd && !session.isClosed())
                session.call("result_cancel", Wire.object("result_id", id), owner, owner.queryTimeout(), null);
        } catch (SQLException failure) {
            pending = failure;
        }
        if (ownedTransaction) {
            try { session.finishOwned(success && pending == null, owner, owner.queryTimeout()); }
            catch (SQLException failure) { if (pending == null) pending = failure; else pending.addSuppressed(failure); }
        }
        if (pending != null) throw pending;
    }

    void invalidate() {
        part.closed = true;
        part.queue.clear();
        settled = true;
        session.release(this);
    }
}
