package com.arman.bank.paymentservice.infrastructure;
import java.net.URI;
import java.util.Map;

public final class EmailConfiguration {
    public final boolean enabled;
    public final String mode, token, fromEmail, fromName, key;
    public final URI endpoint, auth;
    public EmailConfiguration(Map<String,String> env) {
        String enabledValue=env.getOrDefault("EMAIL_DELIVERY_ENABLED","false");
        if (!enabledValue.equals("true") && !enabledValue.equals("false")) throw new IllegalArgumentException("Invalid EMAIL_DELIVERY_ENABLED");
        enabled=enabledValue.equals("true");
        mode=env.getOrDefault("MAILTRAP_MODE","sandbox");
        if (!mode.equals("sandbox") && !mode.equals("sending")) throw new IllegalArgumentException("Invalid MAILTRAP_MODE");
        if (!enabled) { token=null; fromEmail=null; fromName=null; key=null; endpoint=null; auth=null; return; }
        token=required(env,"MAILTRAP_API_TOKEN");
        if (token.length()>2048 || token.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid MAILTRAP_API_TOKEN");
        fromEmail=required(env,"MAILTRAP_FROM_EMAIL");
        if (!validEmail(fromEmail)) throw new IllegalArgumentException("Invalid MAILTRAP_FROM_EMAIL");
        fromName=env.getOrDefault("MAILTRAP_FROM_NAME","ArMoney");
        if (fromName.length()>100 || fromName.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid MAILTRAP_FROM_NAME");
        String inbox=env.getOrDefault("MAILTRAP_INBOX_ID","");
        if (mode.equals("sandbox") && !inbox.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid MAILTRAP_INBOX_ID");
        endpoint=URI.create(mode.equals("sandbox") ? "https://sandbox.api.mailtrap.io/api/send/"+inbox : "https://send.api.mailtrap.io/api/send");
        key=required(env,"INTERNAL_AUTH_KEY");
        if (key.length()<32 || key.length()>256 || key.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid internal key");
        auth=URI.create(required(env,"AUTH_BASE_URL"));
        if (!("http".equals(auth.getScheme()) || "https".equals(auth.getScheme())) || auth.getHost()==null ||
            auth.getUserInfo()!=null || auth.getQuery()!=null || auth.getFragment()!=null ||
            !(auth.getPath().isEmpty() || auth.getPath().equals("/"))) throw new IllegalArgumentException("Invalid auth origin");
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
