"""Probe every service from the Compose network without exposing internal ports."""
import subprocess
import time

services = ["app-gateway", "auth-service", "user-service", "wallet-service", "payment-service", "ledger-service"]
deadline = time.monotonic() + 180
pending = set(services)
while pending and time.monotonic() < deadline:
    for service in list(pending):
        result = subprocess.run(
            ["docker", "run", "--rm", "--network", "arman-bank_bank",
             "curlimages/curl:8.16.0", "--fail", "--silent", "--max-time", "3",
             f"http://{service}:8080/health/ready"],
            capture_output=True, text=True, timeout=45)
        if result.returncode == 0 and '"UP"' in result.stdout:
            pending.remove(service)
    if pending:
        time.sleep(2)
if pending:
    raise SystemExit("Services not ready: " + ", ".join(sorted(pending)))
print("All six services are ready")
