package com.arman.bank.paymentservice.infrastructure;

import com.arman.bank.paymentservice.application.EmailOutbox;
import java.util.Locale;

/** One content model for API and SMTP, without remote resources or customer-controlled markup. */
public record PaymentEmail(String subject, String text, String html) {
    public static PaymentEmail render(EmailOutbox.Claim claim) {
        String action;
        String status;
        String direction;
        String summary;
        switch (claim.type()) {
            case "PAYMENT_COMPLETED" -> {
                action = "Payment completed"; status = "Completed"; direction = "Outgoing transfer";
                summary = "Your transfer has been completed.";
            }
            case "PAYMENT_RECEIVED" -> {
                action = "Payment received"; status = "Completed"; direction = "Incoming transfer";
                summary = "You have received a transfer.";
            }
            case "PAYMENT_REJECTED" -> {
                action = "Payment rejected"; status = "Rejected"; direction = "Outgoing transfer";
                summary = "Your transfer was rejected. Open ArMoney to review the payment details.";
            }
            default -> throw new IllegalArgumentException("Invalid email event");
        }
        if (claim.amountMinor() <= 0) throw new IllegalArgumentException("Invalid email amount");
        String amount = "AED " + claim.amountMinor() / 100 + "."
            + String.format(Locale.ROOT, "%02d", claim.amountMinor() % 100);
        // This is a profile display name, not a verified or legal name.
        String greeting = claim.recipientName() == null ? "Hello," : "Hello " + claim.recipientName() + ",";
        String text = "ArMoney\n\n" + greeting + "\n\n" + summary
            + "\n\nAmount: " + amount + "\nStatus: " + status + "\nType: " + direction
            + "\nPayment ID: " + claim.paymentId() + "\nEvent ID: " + claim.id()
            + "\n\nOpen ArMoney to view your payment history.\nThis is an automated payment notification.";
        String html = """
            <!doctype html>
            <html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>%s</title></head>
            <body style="margin:0;padding:24px 12px;background:#f1f5f9;color:#14283f;font-family:Arial,Helvetica,sans-serif">
            <table role="presentation" width="100%%" cellspacing="0" cellpadding="0"><tr><td align="center">
            <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:600px;background:#ffffff;border:1px solid #dbe3ed;border-radius:16px">
            <tr><td style="padding:28px 32px;background:#14283f;color:#ffffff;border-radius:16px 16px 0 0">
            <span style="font-size:24px;font-weight:bold;letter-spacing:0.5px">ArMoney</span>
            <p style="margin:8px 0 0;font-size:13px;color:#d6e3f0">Payment notification</p></td></tr>
            <tr><td style="padding:32px"><p style="margin:0 0 16px">%s</p>
            <h1 style="font-size:24px;line-height:1.3;margin:0 0 12px">%s</h1>
            <p style="font-size:16px;line-height:1.6;margin:0 0 24px">%s</p>
            <p style="margin:0;font-size:13px;color:#526579">Amount</p>
            <p style="font-size:32px;font-weight:bold;line-height:1.3;margin:6px 0 24px">%s</p>
            <table width="100%%" cellspacing="0" cellpadding="0" style="font-size:14px;line-height:1.6;border-collapse:collapse">
            %s%s%s%s
            </table>
            <p style="font-size:14px;line-height:1.6;margin:24px 0 0">Open ArMoney to view your payment history.</p>
            </td></tr><tr><td style="padding:20px 32px;border-top:1px solid #dbe3ed;font-size:12px;line-height:1.6;color:#526579">
            This is an automated payment notification.</td></tr>
            </table></td></tr></table></body></html>
            """.formatted(escape(action), escape(greeting), escape(action), escape(summary), escape(amount),
                row("Status", status), row("Type", direction), row("Payment ID", claim.paymentId().toString()),
                row("Event ID", claim.id().toString()));
        return new PaymentEmail("ArMoney: " + action, text, html);
    }
    private static String row(String label, String value) {
        return "<tr><th scope='row' align='left' style='padding:10px 12px 10px 0;vertical-align:top;border-top:1px solid #e5ebf2;font-weight:normal;color:#526579'>"
            + escape(label) + "</th><td style='padding:10px 0;border-top:1px solid #e5ebf2;overflow-wrap:anywhere;word-break:break-word'>"
            + escape(value) + "</td></tr>";
    }
    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
