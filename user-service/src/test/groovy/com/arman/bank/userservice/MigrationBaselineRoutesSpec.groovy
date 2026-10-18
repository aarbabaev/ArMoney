package com.arman.bank.userservice
import com.arman.bank.runtime.ServiceRuntime
import com.arman.bank.userservice.application.MigrationBaselineStore
import com.arman.bank.userservice.infrastructure.*
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.http.*
import spock.lang.Specification

class MigrationBaselineRoutesSpec extends Specification {
 static final String STAGE='synthetic-migration-stage-key-00001'
 static final String PUBLISH='synthetic-migration-publication-001'
 static final String DECISION='synthetic-migration-decision-0001'
 static HttpResponse<String> request(int port,String action,String header,String key,String body) {
  def builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+MigrationBaselineRoutes.PREFIX+action))
   .timeout(java.time.Duration.ofSeconds(5)).header('Content-Type','application/json')
  if(header!=null) builder.header(header,key)
  HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString())
 }
 def 'stage and publication credentials are disjoint and missing credentials never reach store'() {
  given:
  def store=Mock(MigrationBaselineStore)
  def config=new MigrationBaselineConfiguration(true,[auth:[STAGE]],[auth:[PUBLISH]],URI.create('http://auth-primary:8080'),DECISION)
  def routes=new MigrationBaselineRoutes(store,config)
  def runtime=ServiceRuntime.start('user-service',0,null,routes::configure)
  when:
  def missing=request(runtime.port(),'user-stage',null,null,'{}')
  def wrong=request(runtime.port(),'user-publish',MigrationBaselineRoutes.PUBLICATION_HEADER,STAGE,'{}')
  then:
  missing.statusCode()==401 && wrong.statusCode()==401
  missing.headers().firstValue('Cache-Control').orElse('')=='no-store'
  0 * store._
  when:
  def malformed=request(runtime.port(),'user-stage',MigrationBaselineRoutes.STAGE_HEADER,STAGE,'{"protocol_version":1e0}')
  then:
  malformed.statusCode()==400
  0 * store._
  cleanup: runtime?.close()
 }
 def 'default-off configuration does not register migration routes'() {
  given:
  def store=Mock(MigrationBaselineStore)
  def routes=new MigrationBaselineRoutes(store,MigrationBaselineConfiguration.from([:]))
  def runtime=ServiceRuntime.start('user-service',0,null,routes::configure)
  when: def response=request(runtime.port(),'user-stage',MigrationBaselineRoutes.STAGE_HEADER,STAGE,'{}')
  then:
  response.statusCode()==404
  0 * store._
  cleanup: runtime?.close()
 }
}
