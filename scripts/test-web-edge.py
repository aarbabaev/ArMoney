"""Exercise shipped edge routing with synthetic upstreams in a networkless container.

No banking stack, host ports, credentials or volumes are used. Only the listener
and upstream addresses are projected onto container loopback. HTTPS/CA acceptance
remains the responsibility of sso-smoke.py against the disposable CI stack.
"""
import pathlib
import re
import subprocess
import tempfile
import time
import uuid


ROOT = pathlib.Path(__file__).resolve().parents[1]


def docker(*args, check=True):
    return subprocess.run(
        ["docker", *args], text=True, capture_output=True, check=check, timeout=60
    )


def fixture_config():
    config = (ROOT / "infra/edge/Caddyfile.web").read_text()
    replacements = {
        "https://{$ARMONEY_HOST}:443 {": "http://127.0.0.1:18080 {",
        "    tls internal": "",
        "sso-service:8080": "127.0.0.1:18081",
        "app-gateway:8080": "127.0.0.1:18082",
        "web:8080": "127.0.0.1:18083",
    }
    for before, after in replacements.items():
        if config.count(before) != 1:
            raise AssertionError("Edge fixture projection must match shipped configuration")
        config = config.replace(before, after)
    return config + """
http://127.0.0.1:18081 {
    respond "synthetic-oidc" 200
}
http://127.0.0.1:18082 {
    respond "synthetic-api" 200
}
http://127.0.0.1:18083 {
    respond "synthetic-web" 200
}
"""


def request(container, path):
    response = docker(
        "exec", container, "wget", "-S", "-O", "-",
        "http://127.0.0.1:18080" + path, check=False,
    )
    statuses = re.findall(r"HTTP/1\.[01] (\d{3})", response.stderr)
    return (int(statuses[-1]) if statuses else None, response.stdout.strip())


def main():
    image = "caddy:2.11.4-alpine"
    compose = (ROOT / "compose.sso.yaml").read_text()
    if "image: " + image not in compose:
        raise AssertionError("Regression image must match the shipped edge version")
    container = "armoney-edge-route-test-" + uuid.uuid4().hex
    with tempfile.TemporaryDirectory(prefix="armoney-edge-route-") as temporary:
        config = pathlib.Path(temporary) / "Caddyfile"
        config.write_text(fixture_config())
        created = False
        try:
            docker("create", "--name", container, "--network", "none",
                   "--env", "ARMONEY_HOST=localhost", image)
            created = True
            docker("cp", str(config), container + ":/etc/caddy/Caddyfile")
            docker("start", container)
            for _ in range(50):
                if request(container, "/home") == (200, "synthetic-web"):
                    break
                time.sleep(0.1)
            else:
                raise AssertionError("Isolated Caddy fixture did not become ready")

            allowed = {
                "/sso/realms/armoney/.well-known/openid-configuration": "oidc",
                "/sso/realms/armoney/protocol/openid-connect/auth": "oidc",
                "/sso/realms/armoney/login-actions/authenticate": "oidc",
                "/sso/resources/example/login.css": "oidc",
                "/v1/auth/me": "api",
                "/health/ready": "api",
                "/home": "web",
                "/oauth/callback": "web",
            }
            for path, upstream in allowed.items():
                expected = (200, "synthetic-" + upstream)
                if request(container, path) != expected:
                    raise AssertionError("Incorrect upstream for " + path)
            denied = (
                "/sso", "/sso/", "/sso/admin/",
                "/sso/realms/master/.well-known/openid-configuration",
                "/sso/realms/armoney/account/", "/sso/unknown",
            )
            for path in denied:
                code, body = request(container, path)
                if code != 404 or "synthetic-" in body:
                    raise AssertionError("Denied SSO path reached a fallback: " + path)
            print(f"Caddy routing passed: {len(allowed)} allowed routes, {len(denied)} edge-local denials")
        finally:
            if created:
                docker("rm", "--force", "--volumes", container)


if __name__ == "__main__":
    main()
