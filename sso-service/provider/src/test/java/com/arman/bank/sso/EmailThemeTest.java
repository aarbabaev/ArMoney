package com.arman.bank.sso;

import freemarker.template.Configuration;
import freemarker.template.TemplateMethodModelEx;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real FreeMarker rendering with synthetic provider variables; no SMTP or action-token generation. */
class EmailThemeTest {
    private static final Path THEME = Path.of("../themes/armoney/email");
    private static final String LINK = "https://bank.example/sso/login-actions/action-token?key=synthetic&client_id=armoney-android";

    private String render(String variant, String link, String maliciousGreeting, AtomicReference<String> expiry) throws Exception {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(THEME.resolve("messages/messages_en.properties"))) { properties.load(reader); }
        var cfg = new Configuration(Configuration.VERSION_2_3_32);
        cfg.setDirectoryForTemplateLoading(THEME.toFile());
        cfg.setDefaultEncoding("UTF-8");
        cfg.setLogTemplateExceptions(false);
        Map<String,Object> model = new HashMap<>();
        model.put("link",link);
        model.put("linkExpiration",7);
        model.put("msg",(TemplateMethodModelEx) args -> {
            String key=args.get(0).toString();
            if (key.equals("armoneyEmailGreeting") && maliciousGreeting!=null) return maliciousGreeting;
            return Objects.requireNonNull(properties.getProperty(key));
        });
        model.put("linkExpirationFormatter",(TemplateMethodModelEx) args -> {
            expiry.set(args.get(0).toString());
            return "7 minutes";
        });
        var output = new StringWriter();
        cfg.getTemplate(variant+"/email-verification.ftl").process(model,output);
        return output.toString();
    }

    @Test void htmlKeepsExactProviderLinkAndFormatterArgument() throws Exception {
        var expiry=new AtomicReference<String>();
        String html=render("html",LINK,null,expiry);
        String escaped=LINK.replace("&","&amp;");
        assertTrue(html.contains("href=\""+escaped+"\""));
        assertTrue(html.contains(">"+escaped+"</a>"));
        assertTrue(html.contains("This link expires in 7 minutes."));
        assertEquals("7",expiry.get());
        assertTrue(html.contains("Hello,"));
        assertTrue(html.contains("ArMoney"));
        assertFalse(html.contains("<img"));
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("firstName"));
        assertFalse(html.contains("migration completed"));
    }

    @Test void htmlAutoescapesLinkAttributeAndMessageText() throws Exception {
        String unsafe="https://bank.example/?a=\" onmouseover=\"bad&x=<tag>";
        String html=render("html",unsafe,"<img src=x onerror=bad>",new AtomicReference<>());
        assertFalse(html.contains("href=\""+unsafe));
        assertTrue(html.contains("&quot;"));
        assertTrue(html.contains("&lt;tag&gt;"));
        assertTrue(html.contains("&lt;img src=x onerror=bad&gt;"));
        assertFalse(html.contains("<img src=x"));
        // This tests escaping only; provider authority still supplies and validates the actual signed link.
    }

    @Test void plainTextKeepsOriginalLinkAndSameExpiryWithoutHtml() throws Exception {
        var expiry=new AtomicReference<String>();
        String text=render("text",LINK,null,expiry);
        assertTrue(text.contains(LINK));
        assertFalse(text.contains("&amp;"));
        assertFalse(text.contains("<a"));
        assertTrue(text.contains("Hello,"));
        assertTrue(text.contains("This link expires in 7 minutes."));
        assertEquals("7",expiry.get());
    }

    @Test void onlyVerificationWorkflowIsOverriddenAndPackagingIncludesEmailInputs() throws Exception {
        assertEquals("parent=base",Files.readString(THEME.resolve("theme.properties")).trim());
        try (var html=Files.list(THEME.resolve("html"));var text=Files.list(THEME.resolve("text"))) {
            assertEquals(List.of("email-verification.ftl"),html.map(p->p.getFileName().toString()).sorted().toList());
            assertEquals(List.of("email-verification.ftl"),text.map(p->p.getFileName().toString()).sorted().toList());
        }
        var props=new Properties();
        try(var reader=Files.newBufferedReader(THEME.resolve("messages/messages_en.properties"))){props.load(reader);}
        assertEquals("Verify your email | ArMoney",props.getProperty("emailVerificationSubject"));
        String packageScript=Files.readString(Path.of("package-provider.sh"));
        assertTrue(packageScript.contains("cp -R \"$repo_dir/sso-service/themes/armoney/email/.\" \"$output/themes/armoney/email/\""));
        assertTrue(packageScript.contains("sso-service/themes/armoney/login sso-service/themes/armoney/email -type f"));
        assertTrue(Files.readString(Path.of("../Dockerfile")).contains("COPY sso-service/themes/armoney/email/ ./sso-service/themes/armoney/email/"));
    }
}
