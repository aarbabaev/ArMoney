#!/usr/bin/env python3
"""Disposable PostgreSQL replication checks; never reads .env or uses the live stack."""
import os
import pathlib
import secrets
import subprocess
import tempfile
import time
import uuid


ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    project = "ledger-repl-test-" + uuid.uuid4().hex[:12]
    with tempfile.TemporaryDirectory(prefix=project) as temporary:
        directory = pathlib.Path(temporary)
        base = directory / "base.yaml"
        empty_env = directory / "empty.env"
        empty_env.write_text("", encoding="utf-8")
        # A complete independent base, with no gateway, host ports, live volumes,
        # application builds, or implicit compose.override.yaml.
        base.write_text("""services:
  ledger-db:
    image: postgres:17.6-alpine
    environment:
      POSTGRES_DB: bank
      POSTGRES_USER: bank
      POSTGRES_PASSWORD: ${LOCAL_DB_PASSWORD}
    volumes: [ledger-db:/var/lib/postgresql/data]
    healthcheck:
      test: [CMD-SHELL, 'pg_isready -U bank -d bank']
      interval: 1s
      timeout: 3s
      retries: 60
    networks: [bank]
  ledger-service:
    image: postgres:17.6-alpine
    profiles: [unused]
networks:
  bank:
volumes:
  ledger-db:
""", encoding="utf-8")
        environment = os.environ.copy()
        # Prevent caller-provided Compose controls from changing fixture scope.
        for key in list(environment):
            if key.startswith("COMPOSE_"):
                del environment[key]
        environment.update(LOCAL_DB_PASSWORD=secrets.token_urlsafe(32),
                           LEDGER_REPLICATION_PASSWORD=secrets.token_urlsafe(32))
        command = ["docker", "compose", "--project-directory", str(ROOT),
                   "--env-file", str(empty_env), "-p", project, "-f", str(base),
                   "-f", str(ROOT / "compose.ledger-replication.yaml")]

        def compose(*args, timeout=180):
            result = subprocess.run(command + list(args), env=environment,
                                    capture_output=True, text=True, timeout=timeout)
            if result.returncode:
                # Do not dump Compose diagnostics or environments containing credentials.
                raise RuntimeError("Compose operation failed: " + " ".join(args[:2]))
            return result.stdout.strip()

        def diagnostics():
            result = subprocess.run(command + ["logs", "--no-color", "--tail", "40",
                                    "ledger-db", "ledger-replication-setup",
                                    "ledger-db-replica1", "ledger-db-replica2"],
                                    env=environment, capture_output=True, text=True, timeout=30)
            output = result.stdout + result.stderr
            for key in ("LOCAL_DB_PASSWORD", "LEDGER_REPLICATION_PASSWORD"):
                output = output.replace(environment[key], "[REDACTED]")
            # Fixture credentials contain URL-safe characters only; exact replacement
            # also removes their SQL-literal and connection-string representations.
            print("Sanitized disposable-project log tail:\n" + output[-16000:])

        def sql(service, query, timeout=20):
            result = subprocess.run(command + ["exec", "-T", service, "psql", "-X",
                                    "-U", "bank", "-d", "bank", "-At", "-v",
                                    "ON_ERROR_STOP=1"], input=query, env=environment,
                                    capture_output=True, text=True, timeout=timeout)
            if result.returncode:
                raise RuntimeError("SQL check failed on " + service)
            return result.stdout.strip()

        def eventually(check, description, seconds=90):
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                try:
                    if check():
                        return
                except (RuntimeError, subprocess.TimeoutExpired):
                    pass
                time.sleep(1)
            raise AssertionError("Timed out: " + description)

        primary = "ledger-db"
        replicas = ["ledger-db-replica1", "ledger-db-replica2"]
        writer = None
        try:
            # Activate against an already initialized, populated volume first.
            # The setup service has not run, so no synchronous quorum is required
            # until the overlay's explicit administrative activation below.
            compose("up", "-d", primary)
            eventually(lambda: sql(primary, "SELECT 1;") == "1", "initial primary ready")
            sql(primary, "CREATE TABLE activation_marker (id integer PRIMARY KEY); INSERT INTO activation_marker VALUES (1);")
            compose("restart", primary)
            eventually(lambda: sql(primary, "SELECT count(*) FROM activation_marker;") == "1", "existing primary volume preserved")
            # Reproduce the previous setup's legacy never-reserved physical slots.
            # Their NULL restart_lsn must be repaired before simultaneous fast clones.
            sql(primary, "SELECT pg_create_physical_replication_slot('ledger_replica1');\nSELECT pg_create_physical_replication_slot('ledger_replica2');\n")
            assert sql(primary, "SELECT count(*) FROM pg_replication_slots WHERE restart_lsn IS NULL AND NOT active;") == "2"
            compose("run", "--rm", "--no-deps", "ledger-replication-setup")
            assert sql(primary, "SELECT count(*) FROM pg_replication_slots WHERE slot_name IN ('ledger_replica1','ledger_replica2') AND restart_lsn IS NOT NULL AND NOT active AND wal_status='reserved';") == "2"
            compose("up", "-d", primary, *replicas)
            eventually(lambda: sql(primary, "SELECT count(*) FROM pg_stat_replication WHERE state='streaming' AND sync_state='quorum';") == "2",
                       "two synchronous streaming standbys", seconds=180)
            assert sql(primary, "SHOW synchronous_commit;") == "on"
            assert sql(primary, "SHOW max_slot_wal_keep_size;") == "1GB"
            system_id = sql(primary, "SELECT system_identifier FROM pg_control_system();")
            # Provisioning must be repeatable against populated existing PGDATA,
            # not dependent on docker-entrypoint-initdb.d running again.
            compose("run", "--rm", "--no-deps", "ledger-replication-setup")
            assert sql(primary, "SELECT count(*) FROM pg_roles WHERE rolname='ledger_replication' AND rolreplication AND NOT rolsuper;") == "1"
            assert sql(primary, "SELECT count(*) FROM pg_replication_slots WHERE slot_name IN ('ledger_replica1','ledger_replica2');") == "2"
            # Generic isolated fixture: no ledger funding or financial-success mocks.
            sql(primary, "CREATE TABLE replication_probe (id integer PRIMARY KEY); INSERT INTO replication_probe VALUES (1);")
            for replica in replicas:
                assert sql(replica, "SELECT count(*) FROM activation_marker;") == "1"
                eventually(lambda r=replica: sql(r, "SELECT count(*) FROM replication_probe;") == "1",
                           replica + " initial replay")
                assert sql(replica, "SELECT pg_is_in_recovery(); SHOW transaction_read_only;") == "t\non"
                failed = subprocess.run(command + ["exec", "-T", replica, "psql", "-X", "-U", "bank", "-d", "bank", "-v", "ON_ERROR_STOP=1"],
                                        input="INSERT INTO replication_probe VALUES (99);", env=environment,
                                        capture_output=True, text=True, timeout=20)
                assert failed.returncode != 0 and "read-only" in failed.stderr
            compose("stop", replicas[0])
            sql(primary, "INSERT INTO replication_probe VALUES (2);")
            eventually(lambda: sql(replicas[1], "SELECT count(*) FROM replication_probe;") == "2", "single surviving standby")
            compose("stop", replicas[1])
            eventually(lambda: sql(primary, "SELECT count(*) FROM pg_stat_replication;") == "0", "both standbys disconnected")
            writer = subprocess.Popen(command + ["exec", "-T", primary, "psql", "-X", "-U", "bank", "-d", "bank", "-v", "ON_ERROR_STOP=1"],
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                      env=environment, text=True)
            writer.stdin.write("INSERT INTO replication_probe VALUES (3);\n")
            writer.stdin.close()
            time.sleep(2)
            assert writer.poll() is None, "Commit acknowledged with no synchronous standby"
            assert sql(primary, "SELECT count(*) FROM pg_stat_activity WHERE wait_event='SyncRep';") == "1"
            compose("start", replicas[0])
            writer.wait(timeout=90)
            assert writer.returncode == 0
            writer = None
            compose("start", replicas[1])
            compose("restart", primary)
            eventually(lambda: sql(primary, "SELECT count(*) FROM pg_stat_replication WHERE state='streaming';") == "2", "reconnect after primary restart")
            for replica in replicas:
                eventually(lambda r=replica: sql(r, "SELECT count(*) FROM replication_probe;") == "3", replica + " restart recovery")
            assert sql(primary, "SELECT count(*) FROM pg_replication_slots WHERE slot_type='physical' AND active AND wal_status IN ('reserved','extended');") == "2"
            # Manual isolated promotion: fence the old primary first. This proves
            # acknowledged data survives promotion; it does not automate reparenting
            # or relax the quorum to make promoted-node writes appear successful.
            compose("stop", primary)
            compose("exec", "-T", "--user", "postgres", replicas[0], "pg_ctl", "-D",
                    "/var/lib/postgresql/data", "promote", "-w", timeout=60)
            assert sql(replicas[0], "SELECT pg_is_in_recovery();") == "f"
            assert sql(replicas[0], "SELECT system_identifier FROM pg_control_system();") == system_id
            # Promotion switches the active WAL timeline before a subsequent
            # checkpoint updates pg_control_checkpoint's historical timeline.
            assert int(sql(replicas[0], "SELECT substring(pg_walfile_name(pg_current_wal_lsn()),1,8);"), 16) > 1
            assert sql(replicas[0], "SELECT count(*) FROM replication_probe;") == "3"
            assert sql(replicas[0], "SHOW synchronous_standby_names;") == "ANY 1 (ledger_replica1, ledger_replica2)"
            # Explicit operator-only reparenting in this disposable fixture.
            sql(replicas[0], "SET synchronous_commit=local;\nSELECT pg_create_physical_replication_slot('ledger_replica2', true) WHERE NOT EXISTS (SELECT FROM pg_replication_slots WHERE slot_name='ledger_replica2');\nALTER SYSTEM SET synchronous_standby_names='ANY 1 (ledger_replica2)';\nSELECT pg_reload_conf();\n")
            compose("stop", replicas[1])
            # Container remains stopped, so use a one-off container mounting its
            # exact project volume. No persistent stack or old-primary volume is touched.
            compose("run", "--rm", "--no-deps", "--entrypoint", "/bin/sh", replicas[1], "-c",
                    "printf \"\\nprimary_conninfo = 'host=ledger-db-replica1 port=5432 user=ledger_replication application_name=ledger_replica2 passfile=/var/lib/postgresql/.pgpass'\\nprimary_slot_name = 'ledger_replica2'\\n\" >> /var/lib/postgresql/data/postgresql.auto.conf")
            compose("start", replicas[1])
            # Start regenerates pgpass for the original primary. Add the promoted
            # host without exposing the credential in argv or diagnostic output.
            compose("exec", "-T", "--user", "postgres", replicas[1], "/bin/sh", "-c",
                    "line=$(sed 's/^ledger-db:/ledger-db-replica1:/' /var/lib/postgresql/.pgpass); printf '%s\\n' \"$line\" >> /var/lib/postgresql/.pgpass")
            eventually(lambda: sql(replicas[0], "SELECT count(*) FROM pg_stat_replication WHERE application_name='ledger_replica2' AND state='streaming' AND sync_state='quorum';") == "1", "manual reparenting with synchronous quorum")
            sql(replicas[0], "INSERT INTO replication_probe VALUES (4);")
            eventually(lambda: sql(replicas[1], "SELECT count(*) FROM replication_probe;") == "4", "post-promotion synchronous data continuity")
            print("PASS: isolated streaming, read-only enforcement, quorum failure/recovery, restart, slots and fenced manual promotion/reparenting")
        except Exception:
            try:
                diagnostics()
            except Exception:
                print("Disposable-project diagnostics unavailable")
            raise
        finally:
            if writer is not None and writer.poll() is None:
                writer.kill()
                writer.wait(timeout=10)
            compose("down", "--volumes", "--remove-orphans", timeout=120)


if __name__ == "__main__":
    main()
