package com.arman.bank.sso;

import java.net.URI;

/** Exact pair identity; never normalizes an issuer or selects a subject. */
record CodeIntentPair(String issuer,String clientId) {
    CodeIntentPair {
        if(!safe(issuer,512) || issuer.endsWith("/") || !safe(clientId,128) || !clientId.matches("[A-Za-z0-9_-]+")) invalid();
        URI uri=parseUri(issuer);
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || uri.getPort()==0 || uri.getPort() < -1 || uri.getPort()>65535) invalid();
    }
    static boolean safe(String value,int limit) {
        if(value==null || value.isEmpty() || value.length()>limit) return false;
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.isISOControl(c)) return false;
            if(Character.isHighSurrogate(c)) { if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) return false; }
            else if(Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    static URI parseUri(String value) {
        try { return URI.create(value); }
        catch(IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid code-intent configuration"); }
    }
    static void invalid() { throw new IllegalArgumentException("Invalid code-intent configuration"); }
}
