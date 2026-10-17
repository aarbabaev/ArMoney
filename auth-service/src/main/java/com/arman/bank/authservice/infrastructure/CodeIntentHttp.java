package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LoginProof;
import com.arman.bank.authservice.domain.LoginProof.Error;
import com.fasterxml.jackson.databind.*;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** C2 private transport mechanics only; does not register routes or create authority. */
final class CodeIntentHttp {
    private CodeIntentHttp() {}
    private static final ObjectMapper JSON=new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static void authenticate(Context ctx,String header,byte[] expected){
        var headers=ctx.req().getHeaders(header);
        String supplied=headers.hasMoreElements()?headers.nextElement():null;
        if(supplied==null||headers.hasMoreElements()||supplied.length()>256
            ||!MessageDigest.isEqual(expected,supplied.getBytes(StandardCharsets.UTF_8)))throw failure(Error.UNAUTHORIZED);
    }
    static JsonNode body(Context ctx,LoginProofBodyDeadline deadline,Set<String> fields){
        if(!ctx.queryParamMap().isEmpty())throw failure(Error.BAD_INPUT);
        String type=ctx.contentType();
        if(type==null||!type.split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw failure(Error.BAD_INPUT);
        try{
            byte[] bytes=deadline.read(ctx.req().getInputStream(),12289);
            if(bytes.length>12288)throw failure(Error.BAD_INPUT);
            JsonNode body=JSON.readTree(bytes);exact(body,fields);return body;
        }catch(java.io.IOException malformed){throw failure(Error.BAD_INPUT);}
    }
    static void exact(JsonNode body,Set<String> fields){
        if(body==null||!body.isObject()||body.size()!=fields.size())throw failure(Error.BAD_INPUT);
        var names=body.fieldNames();while(names.hasNext())if(!fields.contains(names.next()))throw failure(Error.BAD_INPUT);
    }
    static String text(JsonNode body,String field){var value=body.get(field);if(value==null||!value.isTextual())throw failure(Error.BAD_INPUT);return value.textValue();}
    static void reply(Context ctx,int status,Map<String,?> body){
        try{ctx.status(status).contentType("application/json").result(JSON.writeValueAsString(body));}
        catch(java.io.IOException unavailable){throw failure(Error.UNAVAILABLE);}
    }
    static void reject(Context ctx,int status){if(status==429)ctx.header("Retry-After","60");reply(ctx,status,Map.of("error","code_intent_request_rejected"));}
    static int status(LoginProof.Error error){return switch(error){case BAD_INPUT->400;case UNAUTHORIZED->401;case CONFLICT->409;case EXPIRED->410;case MISSING->404;case RATE_LIMITED->429;case UNAVAILABLE->503;};}
    static LoginProof.Failure failure(Error error){return new LoginProof.Failure(error);}
}
