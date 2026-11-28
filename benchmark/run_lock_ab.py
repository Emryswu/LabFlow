#!/usr/bin/env python3
"""Single-variable local vs Redis lock A/B on one disposable MySQL.

Restores the same baseline before every measured run. Does not retry a
finished run and does not drop an invalid result.
"""
from __future__ import annotations

import json
import os
import signal
import subprocess
import time
from pathlib import Path
from urllib.request import urlopen

ROOT = Path("/workspace")
WORK = Path("/tmp/labflow-ab")
BASELINE = WORK / "baseline.sql"
ACCOUNTS = WORK / "accounts.json"
OUT = ROOT / "benchmark" / "ab-results" / time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
JAR = ROOT / "target" / "lab-equipment-platform-0.0.1-SNAPSHOT.jar"
CLIENT = ROOT / "benchmark" / "bench_lock_ab.py"
SCENARIOS = (("contention", 32), ("independent", 32), ("approval", 8))
ROUNDS = 3
TIMEOUT = 60


def log(message: str) -> None:
    line = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()) + " " + message
    print(line, flush=True)
    with (OUT / "orchestrator.log").open("a", encoding="utf-8") as handle:
        handle.write(line + "\n")


def env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key] = value
    return values


def run(cmd: list[str], cwd: Path | None = None, env: dict[str, str] | None = None,
        timeout: float | None = None, input_path: Path | None = None) -> subprocess.CompletedProcess[str]:
    data = input_path.read_bytes() if input_path else None
    return subprocess.run(cmd, cwd=cwd, env=env, input=data, text=data is None,
                          capture_output=True, timeout=timeout, check=False)


def mysql(cfg: dict[str, str], sql: str) -> str:
    completed = subprocess.run(
        ["sudo", "docker", "exec", "-i", "labflow-mysql", "mysql", "-uroot",
         f"-p{cfg['MYSQL_ROOT_PASSWORD']}", "--batch", "--raw", "labops"],
        input=sql, text=True, capture_output=True, check=False)
    if completed.returncode != 0:
        raise RuntimeError(completed.stderr[-500:])
    return completed.stdout


def restore(cfg: dict[str, str]) -> None:
    completed = subprocess.run(
        ["sudo", "docker", "exec", "-i", "labflow-mysql", "mysql", "-uroot",
         f"-p{cfg['MYSQL_ROOT_PASSWORD']}"],
        input=BASELINE.read_bytes(), capture_output=True, check=False)
    if completed.returncode != 0:
        raise RuntimeError(completed.stderr.decode()[-500:])


def app_env(cfg: dict[str, str]) -> dict[str, str]:
    env = os.environ.copy()
    for key in ("DB_URL", "DB_USERNAME", "DB_PASSWORD", "REDIS_HOST", "REDIS_PORT",
                "REDIS_PASSWORD", "RABBITMQ_HOST", "RABBITMQ_PORT", "RABBITMQ_USERNAME",
                "RABBITMQ_PASSWORD", "JWT_SECRET", "PAYMENT_CALLBACK_TOKEN"):
        env[key] = cfg[key]
    return env


def start_app(cfg: dict[str, str], mode: str, log_path: Path) -> subprocess.Popen[bytes]:
    handle = log_path.open("wb")
    command = [
        "java", "-jar", str(JAR),
        "--spring.profiles.active=production",
        "--server.address=127.0.0.1",
        "--server.port=18080",
        f"--labops.reservation-lock.mode={mode}",
        "--labops.demo-users.enabled=true",
        "--labops.demo-data.enabled=false",
        "--labops.jwt.refresh-cookie-secure=false",
    ]
    (log_path.parent / "java.argv").write_text("\n".join(command) + "\n", encoding="utf-8")
    return subprocess.Popen(command, env=app_env(cfg), stdout=handle, stderr=subprocess.STDOUT,
                            start_new_session=True)


def wait_ready(proc: subprocess.Popen[bytes], log_path: Path) -> None:
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(f"app exited {proc.returncode}; see {log_path}")
        text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
        if "APPLICATION FAILED TO START" in text:
            raise RuntimeError(f"app failed to start; see {log_path}")
        if "Started LabEquipmentPlatformApplication" in text:
            with urlopen("http://127.0.0.1:18080/actuator/health", timeout=5) as response:
                if response.status == 200:
                    return
        time.sleep(0.5)
    raise RuntimeError(f"app did not become ready; see {log_path}")


def stop_app(proc: subprocess.Popen[bytes]) -> None:
    if proc.poll() is None:
        os.killpg(proc.pid, signal.SIGTERM)
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait(timeout=10)


def client(cfg: dict[str, str], mode: str, scenario: str, concurrency: int, round_no: int,
           out_path: Path) -> int:
    env = os.environ.copy()
    env["LABFLOW_BENCH_ACCOUNTS"] = ACCOUNTS.read_text(encoding="utf-8")
    completed = subprocess.run(
        ["python3", str(CLIENT), "--disposable-db", "--lock-mode", mode, "--scenario", scenario,
         "--concurrency", str(concurrency), "--commit", cfg["COMMIT"], "--round", str(round_no),
         "--timeout", str(TIMEOUT), "--out", str(out_path)],
        cwd=ROOT, env=env, text=True, capture_output=True, timeout=180, check=False)
    out_path.with_suffix(".stdout").write_text(completed.stdout, encoding="utf-8")
    out_path.with_suffix(".stderr").write_text(completed.stderr, encoding="utf-8")
    return completed.returncode


def evidence(cfg: dict[str, str], directory: Path, mode: str) -> None:
    ids: list[int] = []
    client_json = directory / "measured" / "client.json"
    if client_json.exists():
        payload = json.loads(client_json.read_text(encoding="utf-8"))
        ids = [int(item) for item in payload.get("equipment_ids", [])]
    id_list = ",".join(str(item) for item in ids) or "NULL"
    reservations = mysql(cfg, f"""
        SELECT status, COUNT(*) AS rows_n FROM equipment_reservations GROUP BY status ORDER BY status;
        SELECT COUNT(*) AS equipment_n FROM equipment;
        SELECT COUNT(*) AS reservation_n FROM equipment_reservations;
        SELECT id, code FROM equipment WHERE id IN ({id_list}) ORDER BY id;
        SELECT equipment_id, status, COUNT(*) AS rows_n
        FROM equipment_reservations WHERE equipment_id IN ({id_list})
        GROUP BY equipment_id, status ORDER BY equipment_id, status;
    """)
    (directory / "mysql-reservations.txt").write_text(reservations, encoding="utf-8")
    keys = subprocess.run(
        ["sudo", "docker", "exec", "labflow-redis", "redis-cli", "-a", cfg["REDIS_PASSWORD"],
         "--scan", "--pattern", "labops:reservation:*"],
        text=True, capture_output=True, check=False)
    (directory / "redis-lock-keys.txt").write_text(keys.stdout + keys.stderr, encoding="utf-8")
    app_log = (directory / "app.log").read_text(encoding="utf-8", errors="replace")
    acquired = app_log.count("Redis reservation lock acquired")
    released = app_log.count("Redis reservation lock released")
    (directory / "evidence.txt").write_text(
        f"declared_mode={mode}\nredis_lock_acquired_log_lines={acquired}\n"
        f"redis_lock_released_log_lines={released}\n", encoding="utf-8")


def write_environment(cfg: dict[str, str]) -> None:
    def capture(cmd: list[str]) -> str:
        completed = subprocess.run(cmd, text=True, capture_output=True, check=False)
        return "$ " + " ".join(cmd) + "\n" + completed.stdout + completed.stderr
    parts = [
        capture(["uname", "-a"]),
        capture(["cat", "/etc/os-release"]),
        capture(["lscpu"]),
        capture(["free", "-b"]),
        capture(["java", "-version"]),
        capture(["python3", "--version"]),
        capture(["sudo", "docker", "version"]),
        capture(["sudo", "docker", "inspect", "--format",
                 "{{.Name}} image={{.Config.Image}} id={{.Image}}",
                 "labflow-mysql", "labflow-redis", "labflow-rabbitmq"]),
        capture(["sudo", "docker", "exec", "labflow-mysql", "mysql", "--version"]),
        capture(["sudo", "docker", "exec", "labflow-redis", "redis-server", "--version"]),
        capture(["sudo", "docker", "exec", "labflow-rabbitmq", "rabbitmqctl", "version"]),
        "mysql variables:\n" + mysql(cfg, """
            SHOW VARIABLES WHERE Variable_name IN (
              'version','innodb_buffer_pool_size','max_connections','character_set_server',
              'collation_server','transaction_isolation','innodb_lock_wait_timeout');
        """),
        "git HEAD " + cfg["COMMIT"] + "\n",
        "jar " + str(JAR) + "\n",
        "parameters: scenarios=" + repr(SCENARIOS) + f" rounds={ROUNDS} timeout={TIMEOUT}\n",
        "lock wait/lease not overridden (application defaults 2s/10s)\n",
        "hikari and tomcat not overridden\n",
        "login mode and expiry mode not overridden (production: redis login, rabbit expiry)\n",
        "demo users enabled, demo data disabled\n",
        "same baseline.sql restored before every measured run\n",
    ]
    text = "\n".join(parts)
    (OUT / "environment.txt").write_text(text, encoding="utf-8")
    (OUT / "baseline.sql").write_bytes(BASELINE.read_bytes())
    plan = {
        "commit": cfg["COMMIT"],
        "scenarios": [{"name": name, "concurrency": conc} for name, conc in SCENARIOS],
        "rounds": ROUNDS,
        "timeout_seconds": TIMEOUT,
        "order": "odd rounds local then redis; even rounds redis then local",
        "warmup": "independent concurrency 2, saved separately, not used as the comparison",
    }
    (OUT / "plan.json").write_text(json.dumps(plan, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def one(cfg: dict[str, str], mode: str, scenario: str, concurrency: int, round_no: int) -> None:
    label = f"r{round_no}-{mode}-{scenario}"
    directory = OUT / "runs" / label
    (directory / "warmup").mkdir(parents=True)
    (directory / "measured").mkdir(parents=True)
    log(f"begin {label}")
    restore(cfg)
    app_log = directory / "app.log"
    proc = start_app(cfg, mode, app_log)
    try:
        wait_ready(proc, app_log)
        warmup_code = client(cfg, mode, "independent", 2, round_no, directory / "warmup" / "client.json")
        measured_code = client(cfg, mode, scenario, concurrency, round_no, directory / "measured" / "client.json")
        evidence(cfg, directory, mode)
        (directory / "exit_code.txt").write_text(
            f"warmup={warmup_code}\nmeasured={measured_code}\n", encoding="utf-8")
        log(f"end {label} warmup={warmup_code} measured={measured_code}")
    finally:
        stop_app(proc)


def main() -> None:
    OUT.mkdir(parents=True)
    cfg = env_file(ROOT / ".env")
    cfg["COMMIT"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    admin_password = os.environ["LABFLOW_BENCH_ADMIN_PASSWORD"]
    student_password = os.environ["LABFLOW_BENCH_STUDENT_PASSWORD"]
    students = [{"username": f"bench{i:02d}", "password": student_password} for i in range(1, 33)]
    ACCOUNTS.write_text(json.dumps({
        "admin": {"username": "admin", "password": admin_password},
        "students": students,
    }), encoding="utf-8")
    os.chmod(ACCOUNTS, 0o600)
    write_environment(cfg)
    for round_no in range(1, ROUNDS + 1):
        modes = ("local", "redis") if round_no % 2 else ("redis", "local")
        for mode in modes:
            for scenario, concurrency in SCENARIOS:
                try:
                    one(cfg, mode, scenario, concurrency, round_no)
                except Exception as exc:
                    failed = OUT / "runs" / f"r{round_no}-{mode}-{scenario}"
                    failed.mkdir(parents=True, exist_ok=True)
                    (failed / "error.txt").write_text(repr(exc) + "\n", encoding="utf-8")
                    log(f"FAILED r{round_no}-{mode}-{scenario}: {exc}")
    log("matrix complete")


if __name__ == "__main__":
    main()
