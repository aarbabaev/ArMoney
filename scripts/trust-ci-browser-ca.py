"""Trust only the exported disposable edge CA in the disposable Linux browser profile."""
import os
from pathlib import Path
import subprocess

if (os.environ.get("CI") != "true" or
        os.environ.get("ARMONEY_DISPOSABLE_CI") != "true" or
        os.environ.get("DOCKER_HOST") != "unix:///run/inner-docker/docker.sock" or
        not os.environ.get("GITHUB_WORKSPACE", "").startswith("/work/") or
        Path(".env").exists() or Path("compose.override.yaml").exists()):
    raise SystemExit("Browser CA provisioning requires the isolated disposable CI runner")

ca = Path(os.environ["SSO_CA_FILE"]).resolve(strict=True)
database = Path.home() / ".pki" / "nssdb"
database.mkdir(parents=True, exist_ok=True)
if not (database / "cert9.db").exists():
    subprocess.run(["certutil", "-N", "--empty-password", "-d", "sql:" + str(database)], check=True)
subprocess.run(["certutil", "-A", "-d", "sql:" + str(database), "-n", "armoney-disposable-edge",
                "-t", "C,,", "-i", str(ca)], check=True)
subprocess.run(["certutil", "-L", "-d", "sql:" + str(database), "-n", "armoney-disposable-edge"],
               check=True, stdout=subprocess.DEVNULL)
print("Disposable browser CA imported and read back; certificate verification remains enabled")
