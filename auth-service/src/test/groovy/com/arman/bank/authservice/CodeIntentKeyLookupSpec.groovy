package com.arman.bank.authservice

import com.arman.bank.authservice.domain.CodeIntentKeyLookup as K
import spock.lang.Specification

class CodeIntentKeyLookupSpec extends Specification {
    static K.Input input(List<K.Candidate> candidates=[new K.Candidate('old','a'*64)]){new K.Input('https://issuer.example/realm','subject','armoney-web','session',candidates)}
    def 'one and two unique candidates validate without exposing values in display'(){
        given:def request=input(candidates)
        when:K.validate(request,['old','new'] as Set)
        then:!request.toString().contains('issuer.example');!request.toString().contains('subject');!request.candidates()[0].toString().contains('a'*64)
        where:candidates << [[new K.Candidate('old','a'*64)],[new K.Candidate('old','a'*64),new K.Candidate('new','b'*64)]]
    }
    def 'malformed duplicate or unallowlisted candidates reject generically'(){
        when:K.validate(input(candidates),['old','new'] as Set)
        then:def rejected=thrown(K.Failure);rejected.error()==K.Error.BAD_INPUT;rejected.message=='code_intent_request_rejected';rejected.cause==null
        where:candidates << [null,[],[null],[new K.Candidate('old','a'*64),new K.Candidate('old','b'*64)],
            [new K.Candidate('old','a'*64),new K.Candidate('new','a'*64)],
            [new K.Candidate('old','a'*64),new K.Candidate('new','b'*64),new K.Candidate('third','c'*64)],
            [new K.Candidate('unknown','a'*64)],[new K.Candidate('old','A'*64)],[new K.Candidate('old','a'*63)]]
    }
    def 'binding strings never trim coerce or accept whitespace controls or lone surrogate'(){
        given:def values=['https://issuer.example/realm','subject','armoney-web','session'];values[index]=bad
        when:K.validate(new K.Input(values[0],values[1],values[2],values[3],[new K.Candidate('old','a'*64)]),['old'] as Set)
        then:def rejected=thrown(K.Failure);rejected.error()==K.Error.BAD_INPUT;rejected.cause==null;!rejected.message.contains(bad?:'secret-marker')
        where:
        index | bad
        0 | 'https://secret-marker@bad host/realm'
        0 | 'https://issuer.example:65536/realm'
        0 | 'https://issuer.example/realm\u00a0'
        1 | 'subject '
        1 | 'x'*256
        2 | 'armoney web'
        3 | '\ud800'
        3 | 'session\u0000'
    }
    def 'input candidate list defensively copies and cannot be modified'(){
        given:def original=[new K.Candidate('old','a'*64)];def request=input(original);original.clear()
        expect:request.candidates().size()==1
        when:request.candidates().clear()
        then:thrown(UnsupportedOperationException)
    }
}
