package com.arman.bank.authservice.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import spock.lang.Specification

/** In-memory transport responses prove controller lifecycle only, never actual Keycloak permissions. */
class ProductionProviderAcceptancePolicySpec extends Specification {
    static Map document(){[policies:[[name:'armoney-code-intent-bank-clients',enabled:true,profiles:['armoney-code-intent-profile'],
        conditions:[[condition:'client-attributes',configuration:[attributes:'[{"key":"armoney.code-intent-policy","value":"true"}]']]]],
        [name:'unrelated',enabled:true,profiles:['other'],conditions:[]]],metadata:[preserve:'exact']]}
    static class Peer implements ProductionProviderAcceptancePolicy.Peer {
        JsonNode current=ProductionProviderAcceptanceController.JSON.valueToTree(document())
        int writes
        boolean unknownCommit
        boolean badReadback
        JsonNode get(ProductionProviderAcceptanceController.Deadline deadline){current.deepCopy()}
        void put(JsonNode value,ProductionProviderAcceptanceController.Deadline deadline){writes++;current=value.deepCopy();if(badReadback)current.put('unexpected',true);if(unknownCommit)throw new IOException('synthetic unavailable')}
    }
    def 'one disable lifecycle preserves original document and exact restoration with idempotent readback'(){
        given:def peer=new Peer();def original=peer.current.deepCopy();def policy=new ProductionProviderAcceptancePolicy(peer,new ProductionProviderAcceptanceController.Deadline())
        when:policy.remove(new ProductionProviderAcceptanceController.Deadline());policy.remove(new ProductionProviderAcceptanceController.Deadline())
        then:
        peer.writes==1
        !peer.current.path('policies').get(0).path('enabled').booleanValue()
        peer.current.path('policies').get(1)==original.path('policies').get(1)
        peer.current.path('metadata')==original.path('metadata')
        when:policy.restore(new ProductionProviderAcceptanceController.Deadline());policy.restore(new ProductionProviderAcceptanceController.Deadline())
        then:peer.writes==2;peer.current==original
        when:policy.remove(new ProductionProviderAcceptanceController.Deadline())
        then:thrown(ProductionProviderAcceptanceController.Rejected);peer.writes==2
    }
    def 'unrelated drift before disable fails closed without writes'(){
        given:def peer=new Peer();def policy=new ProductionProviderAcceptancePolicy(peer,new ProductionProviderAcceptanceController.Deadline());peer.current.put('drift',true)
        when:policy.remove(new ProductionProviderAcceptanceController.Deadline())
        then:thrown(ProductionProviderAcceptanceController.Rejected);peer.writes==0
        when:policy.restore(new ProductionProviderAcceptanceController.Deadline())
        then:thrown(ProductionProviderAcceptanceController.Rejected);peer.writes==0
    }
    def 'restore does not overwrite concurrent drift after confirmed disable'(){
        given:def peer=new Peer();def policy=new ProductionProviderAcceptancePolicy(peer,new ProductionProviderAcceptanceController.Deadline());policy.remove(new ProductionProviderAcceptanceController.Deadline());peer.current.put('drift',true)
        when:policy.restore(new ProductionProviderAcceptanceController.Deadline())
        then:thrown(ProductionProviderAcceptanceController.Rejected);peer.writes==1;peer.current.path('drift').booleanValue()
    }
    def 'unknown commit and mismatched readback permanently fence subsequent mutations'(){
        given:def peer=new Peer(unknownCommit:unknown,badReadback:!unknown);def policy=new ProductionProviderAcceptancePolicy(peer,new ProductionProviderAcceptanceController.Deadline())
        when:policy.remove(new ProductionProviderAcceptanceController.Deadline())
        then:thrown(ProductionProviderAcceptanceController.Rejected);peer.writes==1
        when:policy.restore(new ProductionProviderAcceptanceController.Deadline())
        then:thrown(ProductionProviderAcceptanceController.Rejected);peer.writes==1
        where:unknown << [true,false]
    }
    def 'startup rejects missing duplicate disabled and wrong-profile target policies'(){
        given:
        def peer=new Peer();def value=document()
        if(kind=='missing')value.policies.remove(0)
        if(kind=='duplicate')value.policies.add(value.policies[0])
        if(kind=='disabled')value.policies[0].enabled=false
        if(kind=='profile')value.policies[0].profiles=['other']
        if(kind=='condition')value.policies[0].conditions[0]=[condition:'client-access-type',configuration:[type:['public']]]
        peer.current=ProductionProviderAcceptanceController.JSON.valueToTree(value)
        when:new ProductionProviderAcceptancePolicy(peer,new ProductionProviderAcceptanceController.Deadline())
        then:thrown(ProductionProviderAcceptanceController.Rejected);peer.writes==0
        where:kind << ['missing','duplicate','disabled','profile','condition']
    }
    def 'server-owned client attribute selector rejects malformed broader and nonstring configurations'(){
        given:
        def peer=new Peer();def value=document();value.policies[0].conditions[0].configuration.attributes=attributes
        peer.current=ProductionProviderAcceptanceController.JSON.valueToTree(value)
        when:new ProductionProviderAcceptancePolicy(peer,new ProductionProviderAcceptanceController.Deadline())
        then:thrown(ProductionProviderAcceptanceController.Rejected);peer.writes==0
        where:
        attributes << [null,[],[[key:'armoney.code-intent-policy',value:'true']],true,'not-json','[]','{}',
            '[{"key":"armoney.code-intent-policy","value":true}]',
            '[{"key":"armoney.code-intent-policy","value":"false"}]',
            '[{"key":"foreign-policy","value":"true"}]',
            '[{"key":"armoney.code-intent-policy","value":"true","extra":"broad"}]',
            '[{"key":"armoney.code-intent-policy","value":"false","value":"true"}]',
            '[{"key":"armoney.code-intent-policy","value":"true"}] {}',
            '[{"key":"armoney.code-intent-policy","value":"true"},{"key":"other","value":"true"}]',
            ' '*257]
    }
    def 'broader condition arrays extra condition fields and extra configuration fields are rejected'(){
        given:
        def peer=new Peer();def value=document();def condition=value.policies[0].conditions[0]
        if(kind=='additional-condition')value.policies[0].conditions.add([condition:'client-access-type',configuration:[type:['public']]])
        if(kind=='extra-field')condition.isNegativeLogic=true
        if(kind=='extra-config')condition.configuration.type=['public']
        peer.current=ProductionProviderAcceptanceController.JSON.valueToTree(value)
        when:new ProductionProviderAcceptancePolicy(peer,new ProductionProviderAcceptanceController.Deadline())
        then:thrown(ProductionProviderAcceptanceController.Rejected);peer.writes==0
        where:kind << ['additional-condition','extra-field','extra-config']
    }
    def 'decoded exact attribute selector preserves original JSON string representation across disable and restore'(){
        given:
        def peer=new Peer();def value=document();String encoded='[ { "value": "true", "key": "armoney.code-intent-policy" } ]'
        value.policies[0].conditions[0].configuration.attributes=encoded
        peer.current=ProductionProviderAcceptanceController.JSON.valueToTree(value);def original=peer.current.deepCopy()
        def policy=new ProductionProviderAcceptancePolicy(peer,new ProductionProviderAcceptanceController.Deadline())
        when:policy.remove(new ProductionProviderAcceptanceController.Deadline())
        then:peer.current.path('policies').get(0).path('conditions').get(0).path('configuration').path('attributes').textValue()==encoded
        when:policy.restore(new ProductionProviderAcceptanceController.Deadline())
        then:peer.current==original;peer.writes==2
    }
    def 'admin transport refuses master or caller-selected origins'(){
        when:new ProductionProviderAcceptancePolicy.AdminPeer(URI.create('http://127.0.0.1:8080'),'synthetic-distinct-private-admin-secret')
        then:thrown(ProductionProviderAcceptanceController.Rejected)
    }
}
