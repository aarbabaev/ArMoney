package com.arman.bank.authservice.infrastructure;
import java.net.URI;
import java.util.*;
/** Separate capture/dispatch gate. OTP resources do not select this feature. */
public final class LoginNotificationConfiguration {
    public final boolean enabled;
    final EmailConfiguration email;final URI users;final String serviceKey;
    public LoginNotificationConfiguration(Map<String,String> env){
        String flag=env.getOrDefault("AUTH_LOGIN_NOTIFICATIONS_ENABLED","false");
        if(!Set.of("true","false").contains(flag))throw invalid();enabled=flag.equals("true");
        if(!enabled){email=null;users=null;serviceKey=null;return;}
        if(!"true".equals(env.get("AUTH_MANAGED_BANKING_ENABLED")))throw invalid();
        var copy=new HashMap<>(env);copy.put("LOGIN_NOTIFICATIONS_ENABLED","true");email=new EmailConfiguration(copy);
        try{users=URI.create(env.get("USER_BASE_URL"));if(!Set.of("http","https").contains(users.getScheme())||users.getHost()==null||users.getUserInfo()!=null||users.getRawQuery()!=null||users.getRawFragment()!=null||!Set.of("","/").contains(users.getRawPath()))throw invalid();}catch(RuntimeException failure){throw invalid();}
        serviceKey=env.get("INTERNAL_AUTH_KEY");if(serviceKey==null||!serviceKey.matches("[!-~]{32,256}"))throw invalid();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Login notification configuration rejected");}
    @Override public String toString(){return "LoginNotificationConfiguration[redacted]";}
}
