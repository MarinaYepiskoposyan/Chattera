// Endpoints for the local dev stack (docker-compose.yml + `mvn spring-boot:run`
// per service). Edit here if you're running services on non-default ports.
const CONFIG = {
  keycloakBaseUrl: "http://localhost:8080",
  realm: "chattera",
  clientId: "chattera-web",
  profileServiceBaseUrl: "http://localhost:8082",
  chatServiceBaseUrl: "http://localhost:8083",
  // Must exactly match a redirect URI registered for chattera-web
  // (infra/keycloak/realm-export/chattera-realm.json allows
  // http://localhost:3000/*), and this page must be served from that origin.
  redirectUri: window.location.origin + window.location.pathname,
};
