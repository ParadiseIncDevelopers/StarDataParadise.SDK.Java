package com.stardataparadise.sdk;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

/** The JDBC driver for jdbc:sdp://host[:port]. */
public final class StarDataParadiseDriver implements Driver {
    private static final String PREFIX = "jdbc:sdp://";
    private static final Set<String> PROPERTIES = Set.of("user", "password", "caFile", "tlsName",
            "connectTimeoutMs", "queryTimeoutMs");

    static {
        try {
            DriverManager.registerDriver(new StarDataParadiseDriver());
        } catch (SQLException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        if (info == null) throw Wire.invalid("TLS and authentication properties are required");
        for (var key : info.stringPropertyNames())
            if (!PROPERTIES.contains(key)) throw Wire.invalid("Unsupported connection property");
        URI endpoint;
        try {
            endpoint = URI.create(url.substring(5));
        } catch (IllegalArgumentException failure) {
            throw Wire.invalid("Invalid JDBC endpoint");
        }
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !endpoint.getPath().isEmpty())
            throw Wire.invalid("Use jdbc:sdp://host[:port] with separate credentials");
        int port = endpoint.getPort() < 0 ? 7443 : endpoint.getPort();
        if (port < 1 || port > 65535) throw Wire.invalid("Invalid endpoint port");
        String host = endpoint.getHost();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        String user = required(info, "user");
        String password = required(info, "password");
        String tlsName = info.getProperty("tlsName", host);
        if (tlsName.isEmpty() || tlsName.length() > 253 || !tlsName.matches("[A-Za-z0-9.:%-]+"))
            throw Wire.invalid("Invalid TLS identity name");
        Path ca;
        try {
            ca = Path.of(required(info, "caFile"));
        } catch (IllegalArgumentException failure) {
            throw Wire.invalid("Invalid TLS trust bundle property");
        }
        int login = DriverManager.getLoginTimeout();
        int connectTimeout = timeout(info.getProperty("connectTimeoutMs"), login > 0
                ? (int) Math.min(120_000, 1000L * login) : 5_000);
        int queryTimeout = timeout(info.getProperty("queryTimeoutMs"), 10_000);
        Wire wire = Wire.open(host, port, tlsName, ca, connectTimeout);
        try {
            return JdbcSession.connect(wire, url, user, password, connectTimeout, queryTimeout);
        } catch (SQLException | RuntimeException failure) {
            wire.close();
            if (failure instanceof SQLException sql) throw sql;
            throw Wire.malformed();
        }
    }

    private static String required(Properties info, String key) throws SQLException {
        String value = info.getProperty(key);
        if (value == null || value.isEmpty() || value.indexOf(0) >= 0) throw Wire.invalid("Missing connection property " + key);
        return value;
    }

    private static int timeout(String value, int fallback) throws SQLException {
        if (value == null) return fallback;
        try {
            int result = Integer.parseInt(value);
            if (result < 100 || result > 120_000) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException failure) {
            throw Wire.invalid("Connection timeouts must be integers from 100 through 120000");
        }
    }

    @Override public boolean acceptsURL(String url) { return url != null && url.startsWith(PREFIX); }
    @Override public int getMajorVersion() { return 0; }
    @Override public int getMinorVersion() { return 3; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw ValueCodec.unsupported("driver logging");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return PROPERTIES.stream().sorted().map(name -> {
            var property = new DriverPropertyInfo(name,
                    info == null || name.equals("password") ? null : info.getProperty(name));
            property.required = Set.of("user", "password", "caFile").contains(name);
            return property;
        }).toArray(DriverPropertyInfo[]::new);
    }
}
