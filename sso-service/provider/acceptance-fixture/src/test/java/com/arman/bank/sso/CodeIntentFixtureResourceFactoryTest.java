package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("code-intent-fixture-install")
class CodeIntentFixtureResourceFactoryTest {
    @Test void unknownContextDeniesBeforeAnyBodyOrTimer(){
        var factory=new CodeIntentFixtureResourceFactory();var provider=factory.create(null);try{
            var failure=assertThrows(jakarta.ws.rs.WebApplicationException.class,provider::getResource);
            assertEquals(503,failure.getResponse().getStatus());assertEquals("no-store",failure.getResponse().getHeaderString("Cache-Control"));
        }finally{try{provider.close();}finally{factory.close();}}
    }
    @Test void missingWrongDuplicateAndDirectionalCredentialsDenyBeforeArming(){try(var h=new CodeIntentFixtureSupportTest.Harness()){
        CodeIntentFixtureSupport.install(h.support);
        for(var values:List.of(List.<String>of(),List.of("wrong"),List.of(CodeIntentFixtureSupportTest.CONTROL,CodeIntentFixtureSupportTest.CONTROL)))
            assertThrows(CodeIntentFixtureSupport.Rejected.class,()->CodeIntentFixtureResourceFactory.authenticate(values,false));
        assertThrows(CodeIntentFixtureSupport.Rejected.class,()->CodeIntentFixtureResourceFactory.authenticate(List.of(CodeIntentFixtureSupportTest.CONTROL),true));
        assertDoesNotThrow(()->CodeIntentFixtureResourceFactory.authenticate(List.of(CodeIntentFixtureSupportTest.CONTROL),false));
    }}
}
