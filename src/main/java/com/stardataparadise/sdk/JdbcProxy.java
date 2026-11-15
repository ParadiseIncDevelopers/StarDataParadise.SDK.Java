package com.stardataparadise.sdk;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;

final class JdbcProxy {
    static final Object UNHANDLED = new Object();

    private JdbcProxy() { }

    static <T> T make(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(JdbcProxy.class.getClassLoader(), new Class<?>[]{type}, handler));
    }

    static Object common(Object proxy, Method method, Object[] args, String label) throws SQLException {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "StarDataParadise " + label;
            case "isWrapperFor" -> args[0] instanceof Class<?> type && type.isInstance(proxy);
            case "unwrap" -> {
                if (args[0] instanceof Class<?> type && type.isInstance(proxy)) yield type.cast(proxy);
                throw ValueCodec.unsupported("unwrap target");
            }
            default -> UNHANDLED;
        };
    }

    static SQLException state(String detail) { return new SQLException(detail, "25000"); }
    static SQLException closed(String type) { return new SQLException(type + " is closed", "08003"); }
}
