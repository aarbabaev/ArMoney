<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
  <#if section = "header">Verify your ${verificationKind}
  <#elseif section = "form">
    <p>${verificationMessage}</p>
    <form id="armoney-verification" action="${url.loginAction}" method="post">
      <#if verificationPending?? && verificationPending>
      <button name="operation" value="status" type="submit" formnovalidate>Check status</button>
      <#else>
      <label for="verification-code">Six-digit code</label>
      <input id="verification-code" name="code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="[0-9]{6}" />
      <button name="operation" value="verify" type="submit">Verify</button>
      <button name="operation" value="send" type="submit" formnovalidate>Send a code</button>
      </#if>
    </form>
  </#if>
</@layout.registrationLayout>
