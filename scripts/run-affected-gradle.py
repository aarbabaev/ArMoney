"""Run only allowlisted affected Java checks; provision distributions only for fixtures."""
import json
import os
import subprocess

MODULES = frozenset(("platform-runtime", "app-gateway", "auth-service", "user-service", "wallet-service", "payment-service", "ledger-service", "sms-sandbox"))
BANK = sorted(MODULES - {"platform-runtime", "sms-sandbox"})

def command(raw, images):
    selected = json.loads(raw)
    if not isinstance(selected, list) or any(not isinstance(x, str) or x not in MODULES for x in selected) or len(selected) != len(set(selected)):
        raise ValueError("Invalid affected module selection")
    if images not in ("true", "false"):
        raise ValueError("Invalid fixture selection")
    tasks = [f":{module}:check" for module in sorted(selected)]
    if images == "true":
        tasks += [f":{module}:installDist" for module in BANK]
    return ["./gradlew", "--no-daemon", *tasks] if tasks else []

if __name__ == "__main__":
    args = command(os.environ["AFFECTED_MODULES"], os.environ["NEED_BANK_IMAGES"])
    if args:
        print("Affected Java tasks: " + " ".join(args[2:]), flush=True)
        subprocess.run(args, check=True)
    else:
        print("No affected Java modules or bank fixture distributions.")
