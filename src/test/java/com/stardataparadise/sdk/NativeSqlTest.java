package com.stardataparadise.sdk;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeSqlTest {
    @Test void exactJsonRetainsLargeIntegersAndDecimals() {
        var value=new SdpNativeConnection.Json("{\"id\":9007199254740993,\"price\":123.4500}");
        var object=value.value().getAsJsonObject();
        assertEquals(new BigInteger("9007199254740993"),object.get("id").getAsBigInteger());
        assertEquals(new BigDecimal("123.4500"),object.get("price").getAsBigDecimal());
        assertEquals("{\"id\":9007199254740993,\"price\":123.4500}",value.text());
        assertThrows(IllegalArgumentException.class,()->new SdpNativeConnection.Json("{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class,()->new SdpNativeConnection.Json("\"\ud800\""));
    }

    @Test void boundValuesKeepUnsignedAnd65DigitDecimal() throws Exception {
        var integer=new BigInteger("18446744073709551615");
        assertEquals("u64",SdpNativeConnection.binding(integer).get("t").getAsString());
        assertEquals(integer.toString(),SdpNativeConnection.binding(integer).get("v").getAsString());
        var decimal=new BigDecimal("12345678901234567890123456789012345.123456789012345678901234567890");
        assertEquals(decimal.toPlainString(),SdpNativeConnection.binding(decimal).get("v").getAsString());
        assertThrows(SdpException.class,()->SdpNativeConnection.binding(BigInteger.ONE.shiftLeft(64)));
        assertThrows(SdpException.class,()->SdpNativeConnection.binding(Double.NaN));
        assertEquals("null",SdpNativeConnection.binding(null).get("t").getAsString());
    }

    @Test void typedResultAllowsSqlNullAndRetainsTimeIntervals() throws Exception {
        var column=new SdpNativeConnection.Column("duration","TIME",11,true);
        var time=(SdpNativeConnection.Temporal)SdpNativeConnection.cell(Wire.object("t","time","v","-25:00:00"),column);
        assertEquals("-25:00:00",time.text());
        var result=new SdpNativeConnection.ResultSet(List.of(column),List.of(Arrays.asList((Object)null)),BigInteger.ZERO,BigInteger.ZERO);
        assertNull(result.rows().get(0).get(0));
    }

    @Test void toolRollbackKeepsCauseSeparateFromWrapperErrno() {
        var details=Wire.object("native_errno",61085,"phase","tool","commit_state","committed",
                "run_id","a".repeat(32),"call_id","b".repeat(32),"action_commit_state","rolled_back",
                "cause_native_errno",3024,"cause_sqlstate","HY000");
        var error=new SdpException("Tool transaction rolled back.","45000",510005,false,false,null,null,0,0,details);
        assertEquals(61085,error.nativeErrno());
        assertEquals(3024,error.causeNativeErrno());
        assertEquals("HY000",error.causeSqlstate());
        assertEquals("b".repeat(32),error.callId());
        assertEquals("committed",error.commitState());
        assertEquals("rolled_back",error.actionCommitState());
        assertFalse(error.isOutcomeUnknown());
        details.addProperty("action_commit_state","unknown");
        assertEquals("rolled_back",error.actionCommitState());
    }

    @Test void waitErrorPreservesKnownCommitAndRunDiagnostics() {
        JsonObject details=Wire.object("native_errno",61093,"phase","wait","commit_state","committed","run_state","running","run_id","a".repeat(32));
        var error=new SdpException("Wait expired","HYT00",511003,false,false,null,null,0,0,details);
        assertFalse(error.isOutcomeUnknown());
        assertEquals(511003,error.getErrorCode());
        assertEquals(61093,error.nativeErrno());
        assertEquals("committed",error.commitState());
        assertEquals("a".repeat(32),error.runId());
        details.addProperty("commit_state","unknown");
        assertEquals("committed",error.commitState());
    }
}
