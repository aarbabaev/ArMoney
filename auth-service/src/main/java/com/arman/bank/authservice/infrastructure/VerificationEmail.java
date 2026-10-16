package com.arman.bank.authservice.infrastructure;

/** Registration content only: no profile exists or is required at this stage. */
public record VerificationEmail(String subject, String text, String html) {
    public static VerificationEmail render(String code) {
        if (code == null || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("Invalid verification code");
        String text = "Your ArMoney registration verification code is " + code
            + ". It expires in 5 minutes. Do not share this code.";
        String html = """
            <!doctype html>
            <html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>ArMoney registration verification</title></head>
            <body style="margin:0;padding:24px 12px;background:#f1f5f9;color:#14283f;font-family:Arial,Helvetica,sans-serif">
            <table role="presentation" width="100%%" cellspacing="0" cellpadding="0"><tr><td align="center">
            <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:600px;background:#ffffff;border:1px solid #dbe3ed;border-radius:16px">
            <tr><td style="padding:28px 32px;background:#14283f;color:#ffffff;border-radius:16px 16px 0 0">
            <span style="font-size:24px;font-weight:bold;letter-spacing:0.5px">ArMoney</span>
            <p style="margin:8px 0 0;font-size:13px;color:#d6e3f0">Registration verification</p></td></tr>
            <tr><td style="padding:32px"><p style="margin:0 0 16px">Hello,</p>
            <h1 style="font-size:24px;line-height:1.3;margin:0 0 12px">Verify your email address</h1>
            <p style="font-size:16px;line-height:1.6;margin:0 0 24px">Enter this code in ArMoney to continue your registration.</p>
            <p style="margin:0;font-size:13px;color:#526579">Registration verification code</p>
            <p style="font-size:32px;font-weight:bold;letter-spacing:6px;line-height:1.3;margin:6px 0 24px">%s</p>
            <p style="font-size:14px;line-height:1.6;margin:0">It expires in 5 minutes. Do not share this code.</p>
            <p style="font-size:14px;line-height:1.6;margin:24px 0 0">If you did not request this code, you can ignore this email.</p>
            </td></tr><tr><td style="padding:20px 32px;border-top:1px solid #dbe3ed;font-size:12px;line-height:1.6;color:#526579">
            This is an automated registration verification email.</td></tr>
            </table></td></tr></table></body></html>
            """.formatted(escape(code));
        return new VerificationEmail("ArMoney registration verification", text, html);
    }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
