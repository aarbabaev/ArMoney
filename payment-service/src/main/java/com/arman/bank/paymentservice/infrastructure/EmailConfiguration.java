package com.arman.bank.paymentservice.infrastructure;
import java.net.URI;
import java.util.Map;
import java.util.Set;

public final class EmailConfiguration {
    public final boolean enabled;
    public final String mode, transport, token, fromEmail, fromName, key;
    public final String smtpHost, smtpUsername, smtpPassword;
    public final int smtpPort;
    public final URI endpoint, auth, users;
    public EmailConfiguration(Map<String,String> env) {
        String enabledValue=env.getOrDefault("EMAIL_DELIVERY_ENABLED","false");
        if (!enabledValue.equals("true") && !enabledValue.equals("false")) throw new IllegalArgumentException("Invalid EMAIL_DELIVERY_ENABLED");
        enabled=enabledValue.equals("true");
        mode=env.getOrDefault("MAILTRAP_MODE","sandbox");
        if (!mode.equals("sandbox") && !mode.equals("sending")) throw new IllegalArgumentException("Invalid MAILTRAP_MODE");
        transport=env.getOrDefault("MAILTRAP_TRANSPORT","api");
        if (!Set.of("api","smtp").contains(transport)) throw new IllegalArgumentException("Invalid MAILTRAP_TRANSPORT");
        if (transport.equals("smtp") && !mode.equals("sandbox")) throw new IllegalArgumentException("SMTP requires sandbox mode");
        if (!enabled) {
            token=null; fromEmail=null; fromName=null; key=null; endpoint=null; auth=null; users=null;
            smtpHost=null; smtpUsername=null; smtpPassword=null; smtpPort=0; return;
        }
        if (transport.equals("smtp")) {
            smtpHost=env.getOrDefault("MAILTRAP_SMTP_HOST","sandbox.smtp.mailtrap.io");
            if (!smtpHost.equals("sandbox.smtp.mailtrap.io")) throw new IllegalArgumentException("Invalid MAILTRAP_SMTP_HOST");
            String port=env.getOrDefault("MAILTRAP_SMTP_PORT","2525");
            if (!Set.of("25","587","2525").contains(port)) throw new IllegalArgumentException("Invalid MAILTRAP_SMTP_PORT");
            smtpPort=Integer.parseInt(port);
            smtpUsername=credential(env,"MAILTRAP_SMTP_USERNAME");
            smtpPassword=credential(env,"MAILTRAP_SMTP_PASSWORD");
            token=null; endpoint=null;
        } else {
            smtpHost=null; smtpUsername=null; smtpPassword=null; smtpPort=0;
            token=credential(env,"MAILTRAP_API_TOKEN");
            String inbox=env.getOrDefault("MAILTRAP_INBOX_ID","");
            if (mode.equals("sandbox") && !inbox.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid MAILTRAP_INBOX_ID");
            endpoint=URI.create(mode.equals("sandbox") ? "https://sandbox.api.mailtrap.io/api/send/"+inbox : "https://send.api.mailtrap.io/api/send");
        }
        fromEmail=required(env,"MAILTRAP_FROM_EMAIL");
        if (!validEmail(fromEmail)) throw new IllegalArgumentException("Invalid MAILTRAP_FROM_EMAIL");
        fromName=env.getOrDefault("MAILTRAP_FROM_NAME","ArMoney");
        if (fromName.length()>100 || fromName.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid MAILTRAP_FROM_NAME");
        key=required(env,"INTERNAL_AUTH_KEY");
        if (key.length()<32 || key.length()>256 || key.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid internal key");
        auth=origin(required(env,"AUTH_BASE_URL"));
        users=origin(required(env,"USER_BASE_URL"));
    }
    private static URI origin(String value) {
        final URI uri;
        try { uri=URI.create(value); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid email peer origin"); }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null ||
            uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null ||
            !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException("Invalid email peer origin");
        return uri;
    }
    private static String credential(Map<String,String> env,String name) {
        String value=required(env,name);
        if (value.length()>2048 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid "+name);
        return value;
    }
    private static String required(Map<String,String> env,String name) {
        String value=env.get(name);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing "+name);
        return value;
    }
    public static boolean validEmail(String value) {
        return value!=null && value.length()<=254 && value.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+") &&
            value.chars().noneMatch(Character::isISOControl);
    }
}
