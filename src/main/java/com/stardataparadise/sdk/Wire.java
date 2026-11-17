package com.stardataparadise.sdk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

final class Wire implements AutoCloseable {
    static final int MAX_FRAME = 1_048_576;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "sdp-jdbc-deadline");
        thread.setDaemon(true);
        return thread;
    });

    private final SSLSocket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Object requestLock = new Object();
    private final AtomicReference<Flight> active = new AtomicReference<>();
    private volatile boolean closed;
    private long requestId;

    private static final class Flight {
        final Object owner;
        final AtomicInteger cause = new AtomicInteger();
        Flight(Object owner) { this.owner = owner; }
    }

    private Wire(SSLSocket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    static Wire open(String host, int port, String tlsName, Path caFile, int timeoutMs) throws SQLException {
        Socket tcp = new Socket();
        SSLSocket tls = null;
        try {
            var trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            try (var input = Files.newInputStream(caFile)) {
                var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
                if (certificates.isEmpty()) throw new IOException("Empty trust bundle");
                int index = 0;
                for (var certificate : certificates) {
                    if (!(certificate instanceof X509Certificate)) throw new IOException("Invalid trust bundle");
                    trustStore.setCertificateEntry("ca-" + index++, certificate);
                }
            }
            var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trustStore);
            var ssl = SSLContext.getInstance("TLS");
            ssl.init(null, factory.getTrustManagers(), null);
            tcp.connect(new InetSocketAddress(host, port), timeoutMs);
            tls = (SSLSocket) ssl.getSocketFactory().createSocket(tcp, tlsName, port, true);
            tls.setSoTimeout(timeoutMs);
            var parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setProtocols(Arrays.stream(tls.getSupportedProtocols())
                    .filter(protocol -> protocol.equals("TLSv1.2") || protocol.equals("TLSv1.3"))
                    .toArray(String[]::new));
            tls.setSSLParameters(parameters);
            tls.startHandshake();
            return new Wire(tls);
        } catch (Exception error) {
            closeSocket(tls == null ? tcp : tls);
            if (error instanceof SocketTimeoutException) throw transport(1008, null);
            if (error instanceof SSLException || !tcp.isConnected()) {
                if (error instanceof java.net.ConnectException || error instanceof java.net.UnknownHostException)
                    throw transport(1010, null);
                throw transport(1011, null);
            }
            throw transport(1010, null);
        }
    }

    boolean isClosed() { return closed; }

    void cancel(Object owner) {
        var flight = active.get();
        if (flight != null && flight.owner == owner) abort(flight, 1009);
    }

    private void abort(Flight flight, int reason) {
        if (active.compareAndSet(flight, null)) {
            flight.cause.set(reason);
            close();
        }
    }

    JsonObject request(String op, JsonObject body, Object owner, int timeoutMs, String outcomeToken)
            throws SQLException {
        synchronized (requestLock) {
            if (closed) throw transport(1010, null);
            if (requestId == 0xffff_ffffL) { close(); throw transport(1010, null); }
            long id = ++requestId;
            var payload = object("v", 1, "id", id, "op", op, "body", body);
            byte[] data;
            try {
                var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(JSON.toJson(payload)));
                data = new byte[encoded.remaining()];
                encoded.get(data);
                if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
            } catch (java.nio.charset.CharacterCodingException e) {
                throw invalid("Parameter contains invalid Unicode");
            }
            if (data.length == 0 || data.length > MAX_FRAME) {
                Arrays.fill(data, (byte) 0);
                throw invalid("Request exceeds the wire frame limit");
            }
            var flight = new Flight(owner);
            active.set(flight);
            var alarm = TIMER.schedule(() -> abort(flight, 1008), timeoutMs, TimeUnit.MILLISECONDS);
            boolean sent = false;
            try {
                socket.setSoTimeout(timeoutMs);
                sent = true;
                out.writeInt(data.length);
                out.write(data);
                out.flush();
                int length = in.readInt();
                if (length <= 0 || length > MAX_FRAME) throw new IOException("Invalid frame size");
                byte[] answer = in.readNBytes(length);
                if (answer.length != length) throw new IOException("Truncated frame");
                JsonObject message;
                try {
                    message = parse(answer);
                } finally {
                    Arrays.fill(answer, (byte) 0);
                }
                if (requiredInteger(message, "v") != 1 || requiredInteger(message, "id") != id)
                    throw new IOException("Uncorrelated response");
                if (!requiredBoolean(message, "ok")) {
                    var error = requiredObject(message, "error");
                    int code = Math.toIntExact(requiredInteger(error, "code"));
                    boolean unknown = requiredBoolean(error, "unknown_outcome");
                    boolean retryable = requiredBoolean(error, "retryable");
                    String sqlState = requiredString(error, "sqlstate");
                    if (!sqlState.matches("[0-9A-Z]{5}")) throw new IOException("Invalid SQLSTATE");
                    String correlation = optionalString(error, "correlation_id");
                    throw new SdpException(messageFor(code), sqlState, code, unknown, retryable,
                            unknown ? outcomeToken : null, correlation, optionalInt(error, "line"),
                            optionalInt(error, "column"));
                }
                return requiredObject(message, "body");
            } catch (SocketTimeoutException error) {
                close();
                throw transport(1008, sent ? outcomeToken : null);
            } catch (IOException | RuntimeException error) {
                close();
                int reason = flight.cause.get();
                if (reason == 0) reason = error instanceof java.io.EOFException
                        || error instanceof java.net.SocketException ? 1010 : 1001;
                throw transport(reason, sent ? outcomeToken : null);
            } finally {
                active.compareAndSet(flight, null);
                alarm.cancel(false);
                Arrays.fill(data, (byte) 0);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        closeSocket(socket);
    }

    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    static String token() {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    static JsonObject object(Object... pairs) {
        var output = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = (String) pairs[i];
            Object value = pairs[i + 1];
            if (value instanceof JsonElement element) output.add(key, element);
            else if (value instanceof Boolean flag) output.addProperty(key, flag);
            else if (value instanceof Number number) output.addProperty(key, number);
            else if (value == null) output.add(key, JsonNull.INSTANCE);
            else output.addProperty(key, (String) value);
        }
        return output;
    }

    static JsonObject parse(byte[] bytes) throws IOException {
        String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (s.isEmpty() || s.charAt(0) == '\uFEFF') throw new IOException("Invalid JSON encoding");
        try (var reader = new JsonReader(new StringReader(s))) {
            reader.setStrictness(Strictness.STRICT);
            var value = read(reader, 0);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IOException("Invalid JSON object");
            return value.getAsJsonObject();
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                if (depth >= 32) throw new IOException("JSON depth limit");
                var value = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    unicode(key);
                    if (value.has(key)) throw new IOException("Duplicate field");
                    value.add(key, read(reader, depth + 1));
                }
                reader.endObject();
                yield value;
            }
            case BEGIN_ARRAY -> {
                if (depth >= 32) throw new IOException("JSON depth limit");
                var value = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) value.add(read(reader, depth + 1));
                reader.endArray();
                yield value;
            }
            case STRING -> {
                String value = reader.nextString();
                unicode(value);
                yield new JsonPrimitive(value);
            }
            case NUMBER -> new JsonPrimitive(new LexicalNumber(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Invalid JSON token");
        };
    }

    private static void unicode(String value) throws IOException {
        for (int i = 0; i < value.length(); i++) {
            char unit = value.charAt(i);
            if (Character.isHighSurrogate(unit) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) i++;
            else if (Character.isSurrogate(unit)) throw new IOException("Invalid Unicode scalar");
        }
    }

    private static final class LexicalNumber extends Number {
        private final String raw;
        LexicalNumber(String raw) { this.raw = raw; }
        public int intValue() { return new BigDecimal(raw).intValueExact(); }
        public long longValue() { return new BigDecimal(raw).longValueExact(); }
        public float floatValue() { return Float.parseFloat(raw); }
        public double doubleValue() { return Double.parseDouble(raw); }
        public String toString() { return raw; }
    }

    static long requiredInteger(JsonObject json, String key) throws IOException {
        var value = json.get(key);
        if (!(value instanceof JsonPrimitive number) || !number.isNumber()
                || !number.getAsString().matches("0|-?[1-9][0-9]*")) throw new IOException("Invalid integer " + key);
        return Long.parseLong(number.getAsString());
    }

    private static int optionalInt(JsonObject parent, String key) throws IOException {
        return !parent.has(key) || parent.get(key).isJsonNull() ? 0 : Math.toIntExact(requiredInteger(parent, key));
    }

    static boolean requiredBoolean(JsonObject json, String key) throws IOException {
        var value = json.get(key);
        if (!(value instanceof JsonPrimitive bool) || !bool.isBoolean()) throw new IOException("Invalid boolean " + key);
        return bool.getAsBoolean();
    }

    static String requiredString(JsonObject json, String key) throws IOException {
        var value = json.get(key);
        if (!(value instanceof JsonPrimitive string) || !string.isString()) throw new IOException("Invalid text " + key);
        return string.getAsString();
    }

    static String optionalString(JsonObject json, String key) throws IOException {
        return !json.has(key) || json.get(key).isJsonNull() ? null : requiredString(json, key);
    }

    static JsonObject requiredObject(JsonObject json, String key) throws IOException {
        var value = json.get(key);
        if (!(value instanceof JsonObject object)) throw new IOException("Invalid object " + key);
        return object;
    }

    static SdpException invalid(String detail) {
        return new SdpException(detail, "22023", 1012, false, false, null, null, 0, 0);
    }

    static SdpException malformed() { return malformed(null); }
    static SdpException malformed(String token) { return transport(1001, token); }

    private static SdpException transport(int code, String token) {
        String state = switch (code) {
            case 1008 -> "HYT00";
            case 1009 -> "57014";
            case 1010 -> "08006";
            case 1011 -> "08001";
            default -> "08P01";
        };
        return new SdpException(messageFor(code), state, code, token != null, false, token, null, 0, 0);
    }

    private static String messageFor(int code) {
        return switch (code) {
            case 1001 -> "Malformed protocol message";
            case 1002 -> "Message exceeded the wire frame limit";
            case 1003 -> "Incompatible protocol";
            case 1004 -> "Feature unavailable";
            case 1005 -> "Authentication failed";
            case 1006 -> "Permission denied";
            case 1007 -> "Module unavailable";
            case 1008 -> "Operation timed out";
            case 1009 -> "Operation cancelled";
            case 1010 -> "Connection lost";
            case 1011 -> "TLS verification or setup failed";
            case 1012 -> "Invalid argument or SQL";
            case 1013 -> "Invalid state";
            case 1014 -> "Resource limit reached";
            case 1016 -> "Server memory limit reached";
            default -> "Server operation failed";
        };
    }
}
