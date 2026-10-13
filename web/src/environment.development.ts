// Keycloak's canonical issuer remains the HTTPS edge while the local UI runs at 4200.
export const issuer = () => "https://localhost:8443/sso/realms/armoney";
