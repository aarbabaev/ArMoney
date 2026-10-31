<#ftl output_format="HTML" auto_esc=true>
<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body style="margin:0;padding:24px 12px;background:#f4f5f8;color:#202335;font-family:Arial,Helvetica,sans-serif;">
<table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="border-collapse:collapse;"><tr><td align="center">
<table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="max-width:560px;border-collapse:collapse;background:#ffffff;border:1px solid #e4e6ed;border-radius:16px;">
<tr><td style="padding:32px 32px 16px;font-size:24px;font-weight:bold;color:#34306b;">ArMoney</td></tr>
<tr><td style="padding:0 32px 32px;font-size:16px;line-height:1.6;">
<p>${msg("armoneyEmailGreeting")}</p>
<h1 style="margin:16px 0;font-size:26px;line-height:1.25;">${msg("armoneyEmailHeading")}</h1>
<p>${msg("armoneyEmailIntro")}</p>
<p style="margin:28px 0;"><a href="${link}" style="display:inline-block;padding:13px 24px;background:#5145b6;color:#ffffff;text-decoration:none;font-weight:bold;border-radius:8px;">${msg("armoneyEmailAction")}</a></p>
<p>${msg("armoneyEmailExpiry")} ${linkExpirationFormatter(linkExpiration)}.</p>
<p style="font-size:14px;color:#55596b;">${msg("armoneyEmailFallback")}<br><a href="${link}" style="color:#5145b6;word-break:break-all;">${link}</a></p>
<p style="font-size:14px;color:#55596b;">${msg("armoneyEmailIgnore")}</p>
</td></tr></table>
<p style="font-size:12px;color:#666a7a;">${msg("armoneyEmailFooter")}</p>
</td></tr></table>
</body>
</html>
