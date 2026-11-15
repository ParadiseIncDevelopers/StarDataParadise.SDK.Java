package com.stardataparadise.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ContractTest {
    private static JsonObject read(String json) throws IOException {
        return Wire.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsDuplicateKeysInvalidNumbersOrUnicode() throws IOException {
        assertThrows(IOException.class, () -> read("{\"v\":1,\"v\":1}"));
        assertThrows(IOException.class, () -> read("{\"str\":\"\\ud800\"}"));
        assertThrows(IOException.class, () -> Wire.parse(new byte[] {'{', '"', (byte) 0xc0, (byte) 0x80, '"', '}'}));
        assertThrows(IOException.class, () -> Wire.requiredInteger(read("{\"id\":1e0}"), "id"));
        assertEquals(1, Wire.requiredInteger(read("{\"id\":1}"), "id"));
    }

    @Test
    void exactDecimalAndTimestampKeepPrecisionAndOffset() throws Exception {
        var decimal = new BigDecimal("-12345678901234567890123456789012.3400");
        var binding = ValueCodec.binding(decimal, Types.DECIMAL, null);
        assertEquals(4, binding.declaration().get("scale").getAsInt());
        assertEquals(decimal, ValueCodec.decode(binding.value(), "DECIMAL(38,4)"));
        var instant = OffsetDateTime.parse("1970-01-01T02:59:59.999999999+03:00");
        var time = ValueCodec.binding(instant, Types.TIMESTAMP_WITH_TIMEZONE, null);
        var decoded = (SdpValues.Timestamp) ValueCodec.decode(time.value(), "TIMESTAMP WITH TIME ZONE");
        assertEquals(-1, decoded.seconds());
        assertEquals(999_999_999, decoded.nanos());
        assertEquals(180, decoded.offsetMinutes());
        assertEquals(instant, decoded.toOffsetDateTime());
        assertNull(ValueCodec.decode(ValueCodec.binding(null, Types.DECIMAL, 4).value(), "DECIMAL(38,4)"));
    }

    @Test
    void rewritesOnlySqlParameterTokens() throws Exception {
        String input = "/* ? */ SELECT 'İstanbul''un ?' AS \"a?\" WHERE id = ? -- ?";
        var sql = SqlText.parse(input, false);
        assertEquals(1, sql.parameters());
        assertEquals(SqlText.Kind.READ, sql.kind());
        assertEquals(input.replace("id = ?", "id = :jdbc_p_1"), sql.wire());
        assertThrows(SdpException.class, () -> SqlText.parse("SELECT :jdbc_p_1, ?", false));
        assertEquals(SqlText.Kind.ROUTINE, SqlText.parse("{call q(?,?)}", true).kind());
        assertEquals(SqlText.Kind.EXTENSION, SqlText.parse("CREATE MODEL USING '{}'", false).kind());
    }

    @Test
    void exposesJdbcServiceAndRejectsSecretsInUrl() throws Exception {
        var driver = DriverManager.getDriver("jdbc:sdp://localhost:7443");
        assertInstanceOf(StarDataParadiseDriver.class, driver);
        assertNull(driver.connect("jdbc:unrelated://localhost", new Properties()));
        var auth = new Properties();
        auth.putAll(Map.of("user", "not-retained", "password", "test-only-secret", "caFile", "unused"));
        var rejected = assertThrows(SdpException.class,
                () -> driver.connect("jdbc:sdp://localhost:7443?password=test-only-secret", auth));
        assertFalse(rejected.getMessage().contains("test-only-secret"));
        assertNull(java.util.Arrays.stream(driver.getPropertyInfo("", auth))
                .filter(info -> info.name.equals("password")).findFirst().orElseThrow().value);
        assertThrows(SQLFeatureNotSupportedException.class, driver::getParentLogger);
    }
}
