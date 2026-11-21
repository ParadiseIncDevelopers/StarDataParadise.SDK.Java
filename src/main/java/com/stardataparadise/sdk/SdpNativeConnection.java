package com.stardataparadise.sdk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/** Dedicated native SQL session, independent of the JDBC CRUD receipt lifecycle. */
public final class SdpNativeConnection implements AutoCloseable {
    /** Exact JSON source. Gson values preserve integer/decimal lexical digits. */
    public record Json(String text) {
        public Json {
            try { Wire.unicode(text); Wire.parseValue(text.getBytes(StandardCharsets.UTF_8)); }
            catch (IOException | RuntimeException invalid) { throw new IllegalArgumentException("Invalid native JSON",invalid); }
        }
        public JsonElement value() {
            try { return Wire.parseValue(text.getBytes(StandardCharsets.UTF_8)); }
            catch (IOException invalid) { throw new IllegalStateException(invalid); }
        }
    }
    /** SQL temporal source; TIME is a signed interval and can exceed 24 hours. */
    public record Temporal(String sqlType,String text) { }
    public record Column(String name,String type,int nativeType,boolean nullable) { }
    public record ResultSet(List<Column> columns,List<List<Object>> rows,BigInteger affectedRows,BigInteger lastInsertId) {
        public ResultSet {
            columns=List.copyOf(columns);
            rows=rows.stream().map(row->Collections.unmodifiableList(new ArrayList<>(row))).toList();
        }
    }
    public record Result(List<ResultSet> sets,boolean inTransaction,boolean autocommit) {
        public Result { sets=List.copyOf(sets); }
    }

    private final Wire wire;
    private final int timeoutMs;
    private boolean inTransaction;
    private boolean autocommit;

    private SdpNativeConnection(Wire wire,int timeoutMs,boolean auto) {
        this.wire=wire;this.timeoutMs=timeoutMs;this.autocommit=auto;
    }

    /** Open a fresh authenticated TLS connection and negotiate native_sql_v1. */
    public static SdpNativeConnection open(String host,int port,String tlsName,Path caFile,
                                            String user,String password,int timeoutMs,boolean autocommit) throws SQLException {
        if(host==null||host.isEmpty()||tlsName==null||tlsName.isEmpty()||caFile==null||
           user==null||user.isEmpty()||password==null||password.isEmpty()||port<1||port>65535||timeoutMs<1000||timeoutMs>300000)
            throw Wire.invalid("Invalid native connection settings");
        Wire wire=Wire.open(host,port,tlsName,caFile,timeoutMs);
        try {
            JsonObject hello=wire.request("hello",Wire.object("client","StarDataParadise native Java SQL"),wire,timeoutMs,null);
            if(Wire.requiredInteger(hello,"protocol_version")!=1 ||
               !Wire.requiredString(hello,"session_id").matches("[0-9a-f]{32}") ||
               !Wire.requiredString(hello,"product_version").matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw Wire.malformed();
            JsonObject auth=Wire.object("username",user,"password",password);
            try {
                if(!Wire.requiredBoolean(wire.request("auth",auth,wire,timeoutMs,null),"authenticated"))throw Wire.malformed();
            } finally {auth.remove("password");}
            JsonObject features=Wire.requiredObject(wire.request("capabilities",new JsonObject(),wire,timeoutMs,null),"features");
            if(!features.has("native_sql_v1")||!Wire.requiredBoolean(features,"native_sql_v1"))
                throw new SdpException("Server does not advertise native_sql_v1","0A000",1004,false,false,null,null,0,0);
            wire.request("native.open",Wire.object("autocommit",autocommit),wire,timeoutMs,null);
            return new SdpNativeConnection(wire,timeoutMs,autocommit);
        }catch(IOException invalid){wire.close();throw Wire.malformed();}
        catch(SQLException | RuntimeException error){wire.close();throw error;}
    }

    public boolean inTransaction(){return inTransaction;}
    public boolean autocommit(){return autocommit;}
    public boolean isClosed(){return wire.isClosed();}

    /** Compile one statement on the server; positional ? bindings are never interpolated. */
    public Result execute(String sql,Object...parameters) throws SQLException {
        return execute(sql,Arrays.asList(parameters),1000);
    }

    public synchronized Result execute(String sql,List<?> parameters,int maxRows) throws SQLException {
        if(sql==null||sql.isEmpty()||sql.indexOf('\0')>=0||parameters==null||parameters.size()>128||maxRows<1||maxRows>10000)
            throw Wire.invalid("Invalid native SQL or response limit");
        JsonArray values=new JsonArray();
        for(Object parameter:parameters)values.add(binding(parameter));
        JsonObject result=wire.request("native.execute",Wire.object("sql",sql,"parameters",values,
            "timeout_ms",Math.max(1000,timeoutMs-2000),"max_rows",maxRows),this,timeoutMs,null,true);
        try {
            if(result.has("native_result")) result=readChunks(result);
            ArrayList<ResultSet> sets=new ArrayList<>();
            for(JsonElement value:result.getAsJsonArray("sets")){
                JsonObject part=value.getAsJsonObject();
                ArrayList<Column> columns=new ArrayList<>();
                for(JsonElement item:part.getAsJsonArray("columns")){
                    JsonObject meta=item.getAsJsonObject();
                    columns.add(new Column(Wire.requiredString(meta,"name"),Wire.requiredString(meta,"type"),
                        Math.toIntExact(Wire.requiredInteger(meta,"native_type")),Wire.requiredBoolean(meta,"nullable")));
                }
                ArrayList<List<Object>> rows=new ArrayList<>();
                for(JsonElement item:part.getAsJsonArray("rows")){
                    JsonArray row=item.getAsJsonArray();
                    if(row.size()!=columns.size())throw new IOException("Invalid row width");
                    ArrayList<Object> decoded=new ArrayList<>();
                    for(int i=0;i<row.size();++i)decoded.add(cell(row.get(i).getAsJsonObject(),columns.get(i)));
                    rows.add(decoded);
                }
                sets.add(new ResultSet(columns,rows,new BigInteger(Wire.requiredString(part,"affected_rows")),
                    new BigInteger(part.has("last_insert_id")?Wire.requiredString(part,"last_insert_id"):"0")));
            }
            inTransaction=Wire.requiredBoolean(result,"in_transaction");
            autocommit=Wire.requiredBoolean(result,"autocommit");
            return new Result(sets,inTransaction,autocommit);
        }catch(IOException | RuntimeException invalid){
            wire.close();
            throw new SdpException("Malformed native result; query the run/outcome before retrying","08P01",1001,
                true,false,null,null,0,0,Wire.object("commit_state","unknown"));
        }
    }

    private JsonObject readChunks(JsonObject first) throws SQLException,IOException {
        JsonObject part=Wire.requiredObject(first,"native_result");
        String id=Wire.requiredString(part,"id"),sha=Wire.requiredString(part,"sha256");
        int size=Math.toIntExact(Wire.requiredInteger(part,"total_bytes"));
        if(size<1||size>16*1024*1024||!id.matches("[0-9a-f]{32}")||!sha.matches("[0-9a-f]{64}"))throw new IOException("Invalid native chunks");
        byte[] result=new byte[size];int offset=0;
        for(;;){
            if(!Wire.requiredString(part,"id").equals(id)||Wire.requiredInteger(part,"offset")!=offset||
               Wire.requiredInteger(part,"total_bytes")!=size||!Wire.requiredString(part,"sha256").equals(sha))throw new IOException("Native chunk identity changed");
            String encoded=Wire.requiredString(part,"chunk");
            byte[] bytes=Base64.getDecoder().decode(encoded);
            if(bytes.length==0||offset+bytes.length>size||!Base64.getEncoder().encodeToString(bytes).equals(encoded))throw new IOException("Invalid native chunk");
            System.arraycopy(bytes,0,result,offset,bytes.length);offset+=bytes.length;
            if(Wire.requiredBoolean(part,"end"))break;
            part=Wire.requiredObject(wire.request("native.fetch",Wire.object("result_id",id,"offset",offset),this,timeoutMs,null,true),"native_result");
        }
        try {
            if(offset!=size||!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(result),HexFormat.of().parseHex(sha)))
                throw new IOException("Native result digest mismatch");
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        return Wire.parse(result);
    }

    static JsonObject binding(Object value) throws SQLException {
        if(value==null)return Wire.object("t","null");
        if(value instanceof Json json)return Wire.object("t","json","v",json.text());
        if(value instanceof Boolean flag)return Wire.object("t","bool","v",flag);
        if(value instanceof BigInteger number){
            if(number.compareTo(BigInteger.ONE.shiftLeft(63).negate())<0||number.compareTo(BigInteger.ONE.shiftLeft(64))>=0)
                throw Wire.invalid("Native BIGINT range exceeded");
            return Wire.object("t",number.bitLength()<64?"i64":"u64","v",number.toString());
        }
        if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long)
            return Wire.object("t","i64","v",value.toString());
        if(value instanceof BigDecimal number){
            if(number.precision()+Math.max(0,-number.scale())>65||number.scale()>30)
                throw Wire.invalid("Native DECIMAL precision/scale exceeded");
            return Wire.object("t","decimal","v",number.toPlainString());
        }
        if(value instanceof Float||value instanceof Double){
            if(!Double.isFinite(((Number)value).doubleValue()))throw Wire.invalid("Native float must be finite");
            return Wire.object("t","float","v",value.toString());
        }
        if(value instanceof byte[] bytes)return Wire.object("t","binary","v",HexFormat.of().formatHex(bytes));
        if(value instanceof String text)return Wire.object("t","text","v",text);
        if(value instanceof Temporal temporal)return Wire.object("t","text","v",temporal.text());
        if(value instanceof java.time.temporal.TemporalAccessor)return Wire.object("t","text","v",value.toString());
        throw Wire.invalid("Unsupported native SQL binding");
    }

    static Object cell(JsonObject value,Column column) throws IOException {
        String tag=Wire.requiredString(value,"t");
        if(tag.equals("null"))return null;
        String text=Wire.requiredString(value,"v");
        return switch(tag){
            case "i64"->Long.valueOf(text);
            case "u64"->new BigInteger(text);
            case "decimal"->new BigDecimal(text);
            case "float"->{double number=Double.parseDouble(text);if(!Double.isFinite(number))throw new IOException("Invalid native float");yield number;}
            case "binary"->HexFormat.of().parseHex(text);
            case "json"->new Json(text);
            case "date","time","datetime"->new Temporal(column.type(),text);
            case "text"->text;
            default->throw new IOException("Invalid native result tag");
        };
    }

    public Result begin() throws SQLException{return execute("START TRANSACTION");}
    public Result rollback() throws SQLException{return execute("ROLLBACK");}
    public Result commit() throws SQLException{return execute("COMMIT");}
    public Result commit(boolean asynchronous,String requestKey,Integer waitTimeoutMs) throws SQLException {
        if(asynchronous&&waitTimeoutMs!=null)throw Wire.invalid("ASYNC cannot capture or wait");
        ArrayList<String> options=new ArrayList<>();ArrayList<Object> values=new ArrayList<>();
        if(requestKey!=null){options.add("request_key=?");values.add(key(requestKey));}
        if(waitTimeoutMs!=null){
            if(waitTimeoutMs<1||waitTimeoutMs>timeoutMs-3000)throw Wire.invalid("SQL wait deadline must fit inside the connection timeout");
            options.add("wait_timeout_ms=?");values.add(waitTimeoutMs);
        }
        String sql=asynchronous?"COMMIT ASYNC":"COMMIT";
        if(!options.isEmpty())sql+=" WITH ("+String.join(", ",options)+")";
        return execute(sql,values,1000);
    }
    private static String key(String value) throws SQLException {
        if(value==null||!value.matches("[0-9a-f]{32}"))throw Wire.invalid("Expected 32 lowercase hexadecimal digits");return value;
    }
    public Result fetchResponse(String runId) throws SQLException{return execute("FETCH RESPONSE FROM RUN ?",key(runId));}
    public Result cancelRun(String runId) throws SQLException{return execute("CANCEL RUN ?",key(runId));}
    public void cancel(){wire.cancel(this);}
    @Override public void close(){wire.close();}
}
