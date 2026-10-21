<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
  <#if section = "header">Disposable registration acceptance
  <#elseif section = "form">
    <p>Use the isolated controller to observe preparation and bind continuation.</p>
    <form action="${url.loginAction}" method="post">
      <button name="operation" value="prepare" type="submit">Prepare isolated registration</button>
      <button name="operation" value="continue" type="submit">Continue isolated registration</button>
    </form>
  </#if>
</@layout.registrationLayout>
