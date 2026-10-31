package com.arman.bank.authservice.infrastructure;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
/** Presentation only. Names are user-supplied labels, not verified legal identity. */
public record LoginEmail(String subject,String text,String html) {
    static String name(String input){
        if(input==null)return null;String value=input.strip();
        return value.isEmpty()||value.codePointCount(0,value.length())>100||value.codePoints().anyMatch(Character::isISOControl)?null:value;
    }
    public static LoginEmail render(Instant occurredAt,String displayName){
        if(occurredAt==null)throw new IllegalArgumentException("Invalid notice time");
        String label=name(displayName),greeting=label==null?"Hello,":"Hello, "+label+",";
        String time=DateTimeFormatter.ISO_INSTANT.format(occurredAt);
        String text=greeting+"\n\nA successful sign-in to your ArMoney account occurred at "+time+" (UTC).\nIf you did not sign in, review your account security. Never share your password or verification codes.";
        String html="""
            <!doctype html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>ArMoney sign-in notice</title></head>
            <body style="margin:0;padding:24px 12px;background:#f1f5f9;color:#14283f;font-family:Arial,Helvetica,sans-serif">
            <table role="presentation" width="100%%"><tr><td align="center"><table role="presentation" width="100%%" style="max-width:600px;background:white;border:1px solid #dbe3ed;border-radius:16px" cellspacing="0" cellpadding="0">
            <tr><td style="padding:28px 32px;background:#14283f;color:white;border-radius:16px 16px 0 0"><strong style="font-size:24px">ArMoney</strong><p>Account security</p></td></tr>
            <tr><td style="padding:32px"><p>%s</p><h1 style="font-size:24px">Successful sign-in</h1><p>A successful sign-in to your ArMoney account occurred at:</p><p style="font-weight:bold">%s (UTC)</p>
            <p>If you did not sign in, review your account security.</p><p>Never share your password or verification codes.</p></td></tr>
            <tr><td style="padding:20px 32px;border-top:1px solid #dbe3ed;font-size:12px">This is an automated account security notice.</td></tr></table></td></tr></table></body></html>
            """.formatted(escape(greeting),escape(time));
        return new LoginEmail("ArMoney successful sign-in",text,html);
    }
    private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
