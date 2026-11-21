package com.stardataparadise.sdk;

import com.google.gson.JsonObject;
import java.sql.SQLException;

/** A Server or transport error with optional outcome and native run diagnostics. */
public final class SdpException extends SQLException {
    private final boolean unknownOutcome;
    private final boolean retryable;
    private final String requestToken;
    private final String correlationId;
    private final int line;
    private final int column;
    private final JsonObject nativeDetails;

    SdpException(String reason,String state,int code,boolean unknownOutcome,boolean retryable,
                 String requestToken,String correlationId,int line,int column) {
        this(reason,state,code,unknownOutcome,retryable,requestToken,correlationId,line,column,null);
    }

    SdpException(String reason,String state,int code,boolean unknownOutcome,boolean retryable,
                 String requestToken,String correlationId,int line,int column,JsonObject nativeDetails) {
        super(reason,state,code);
        this.unknownOutcome=unknownOutcome;
        this.retryable=retryable&&!unknownOutcome;
        this.requestToken=requestToken;
        this.correlationId=correlationId;
        this.line=line;
        this.column=column;
        this.nativeDetails=nativeDetails==null?new JsonObject():nativeDetails.deepCopy();
    }

    public boolean isOutcomeUnknown(){return unknownOutcome;}
    public boolean isRetryable(){return retryable;}
    public String requestToken(){return requestToken;}
    public String correlationId(){return correlationId;}
    public int line(){return line;}
    public int column(){return column;}
    public JsonObject nativeDiagnostics(){return nativeDetails.deepCopy();}
    private String nativeText(String name){
        var value=nativeDetails.get(name);
        return value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()?value.getAsString():null;
    }
    public String phase(){return nativeText("phase");}
    public String commitState(){return nativeText("commit_state");}
    public String runState(){return nativeText("run_state");}
    public String runId(){return nativeText("run_id");}
    public String callId(){return nativeText("call_id");}
    public String actionCommitState(){return nativeText("action_commit_state");}
    public String causeSqlstate(){return nativeText("cause_sqlstate");}
    public int causeNativeErrno(){return nativeNumber("cause_native_errno");}
    public int nativeErrno(){return nativeNumber("native_errno");}
    private int nativeNumber(String name){
        var value=nativeDetails.get(name);
        return value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber()?value.getAsInt():0;
    }
}
