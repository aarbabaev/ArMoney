package com.arman.bank.userservice
import com.arman.bank.userservice.infrastructure.LiveUserRevisionConfiguration
import spock.lang.Specification
class LiveUserRevisionConfigurationSpec extends Specification {
    static final String CURRENT='synthetic-current-key-000000000000001',MUTATION='synthetic-mutation-key-00000000000001'
    static LiveUserRevisionConfiguration enabled(URI origin=URI.create('http://127.0.0.1:9999')) {new LiveUserRevisionConfiguration(true,[auth:[CURRENT]],origin,LiveUserRevisionSpec.WRITER,MUTATION)}
    def 'configuration is default off and credentials are redacted'(){
        expect:!LiveUserRevisionConfiguration.from([:]).enabled()
        !enabled().toString().contains(CURRENT)
        !enabled().toString().contains(MUTATION)
    }
    def 'cross purpose credential aliases and unbounded origins reject'(){
        when:new LiveUserRevisionConfiguration(true,[auth:[CURRENT]],URI.create('http://localhost:9999'),'writer',Base64.encoder.encodeToString(CURRENT.bytes))
        then:thrown(IllegalArgumentException)
        when:enabled(URI.create('http://user:password@localhost/path'))
        then:thrown(IllegalArgumentException)
    }
    def 'configured unrelated secret cannot alias a live credential'(){
        when:LiveUserRevisionConfiguration.from([USER_LIVE_REVISION_ENABLED:'true',USER_LIVE_CURRENT_WRITER_ID:'auth',AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY:CURRENT,USER_LIVE_MUTATION_WRITER_ID:LiveUserRevisionSpec.WRITER,USER_TO_AUTH_MIGRATION_USER_MUTATION_KEY:MUTATION,AUTH_MIGRATION_PRIMARY_ORIGIN:'http://localhost:9999',OTHER_SECRET:Base64.encoder.encodeToString(CURRENT.bytes)])
        then:thrown(IllegalArgumentException)
    }
}
