"""Exercise the packaged API and kill/restart its Worker against an external Temporal server."""

import argparse
import base64
import json
import os
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def request(base, method, path, payload=None, expected=200, actor=None):
    data = None if payload is None else json.dumps(payload).encode()
    actor = actor or ("approver" if method == "POST" and path.endswith("/approval") else "operator")
    credentials = actor + ":" + os.environ[f"PLATFORM_{actor.upper()}_PASSWORD"]
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": "application/json", "X-Platform-Request": "true",
                                          "Authorization": "Basic " + base64.b64encode(credentials.encode()).decode()})
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = response.read().decode()
        if response.status != expected:
            raise AssertionError(f"{method} {path}: expected {expected}, got {response.status}: {body}")
        return json.loads(body) if body else None


def await_state(base, run_id, state):
    deadline = time.monotonic() + 30
    latest = None
    while time.monotonic() < deadline:
        latest = request(base, "GET", f"/api/runs/{run_id}")
        if latest["state"] == state:
            return latest
        time.sleep(0.2)
    raise AssertionError(f"Expected {state}, got {latest}")


def start(jar, port, output, delivery=True):
    process = subprocess.Popen(
        ["java", "-jar", str(jar), f"--server.port={port}",
         f"--platform.approval-delivery.enabled={str(delivery).lower()}"],
        stdout=output, stderr=subprocess.STDOUT,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    base = f"http://127.0.0.1:{port}"
    deadline = time.monotonic() + 90
    try:
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("Application exited; inspect var/smoke.log")
            try:
                if request(base, "GET", "/actuator/health")["status"] == "UP":
                    return process
            except (OSError, AssertionError):
                pass
            time.sleep(0.5)
        raise TimeoutError("Application did not start; inspect var/smoke.log")
    except BaseException:
        if process.poll() is None:
            process.kill()
        process.wait(timeout=15)
        raise


def create(base, seconds=300):
    request_id = str(uuid.uuid4())
    payload = {"requestId": request_id, "service": "orders", "approvalTimeoutSeconds": seconds}
    created = request(base, "POST", "/api/runs", payload, 202)
    return created["runId"], payload


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", default="platform-api/target/platform-api-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--port", type=int, default=9091)
    parser.add_argument("--restart-approval-db", action="store_true",
                        help="Restart this Compose project's approval-db during the recovery check")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    jar = (root / args.jar).resolve()
    if not jar.is_file():
        raise FileNotFoundError("Build the application with Maven verify first")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", args.port))
    base = f"http://127.0.0.1:{args.port}"
    if not os.environ.get("PLATFORM_DATABASE_PASSWORD"):
        raise RuntimeError("Set PLATFORM_DATABASE_PASSWORD to match the approval-db container")
    # Ephemeral test credentials are passed only through the child process environment.
    os.environ["PLATFORM_OPERATOR_PASSWORD"] = secrets.token_urlsafe(32)
    os.environ["PLATFORM_APPROVER_PASSWORD"] = secrets.token_urlsafe(32)
    (root / "var").mkdir(exist_ok=True)
    process = None
    with (root / "var/smoke.log").open("w", encoding="utf-8") as output:
        try:
            process = start(jar, args.port, output, delivery=False)
            run_id, payload = create(base)
            pending = await_state(base, run_id, "WAITING_APPROVAL")
            assert pending["reasonCode"] == "DEMO_RESTART_REQUIRES_APPROVAL"
            approval_path = f"/api/runs/{run_id}/approval"
            original_record = request(base, "GET", approval_path)
            assert original_record["status"] == "PENDING"
            request(base, "POST", "/api/runs", payload, 409)
            first_pid = process.pid
            process.kill()
            process.wait(timeout=15)
            process = start(jar, args.port, output, delivery=False)
            recovered = await_state(base, run_id, "WAITING_APPROVAL")
            assert recovered == pending, "Persisted Run changed across Worker restart"
            assert request(base, "GET", approval_path) == original_record
            decision = {"approvalId": pending["approvalId"], "decision": "APPROVE"}
            request(base, "POST", approval_path, decision, 403, actor="operator")
            request(base, "POST", approval_path, decision, 202)
            request(base, "POST", approval_path, decision, 202)
            saved_decision = request(base, "GET", approval_path)
            assert saved_decision["decidedBy"] == "approver" and saved_decision["status"] == "APPROVE"
            assert request(base, "GET", f"/api/runs/{run_id}")["state"] == "WAITING_APPROVAL"
            process.kill()
            process.wait(timeout=15)
            if args.restart_approval_db:
                subprocess.run(["docker", "compose", "restart", "approval-db"], cwd=root, check=True)
                subprocess.run(["docker", "compose", "up", "-d", "--wait", "approval-db"], cwd=root, check=True)
            process = start(jar, args.port, output)
            complete = await_state(base, run_id, "SUCCEEDED")
            assert complete["output"].startswith("SIMULATED_RESTART:orders")
            assert complete["reasonCode"] == "DEMO_RESTART_REQUIRES_APPROVAL"
            completed_record = request(base, "GET", approval_path)
            assert completed_record == dict(saved_decision, executionStarted=True)
            request(base, "POST", approval_path, decision, 202)
            request(base, "POST", "/api/runs", payload, 409)
            print(f"PASS restart recovery: {run_id}; Worker {first_pid} -> {process.pid}", flush=True)
            print("PASS persistent approval, authenticated actor, duplicate decision and outbox recovery"
                  + (" with PostgreSQL restart" if args.restart_approval_db else ""), flush=True)

            rejected, _ = create(base)
            approval = await_state(base, rejected, "WAITING_APPROVAL")
            request(base, "POST", f"/api/runs/{rejected}/approval",
                    {"approvalId": approval["approvalId"], "decision": "REJECT"}, 202)
            assert await_state(base, rejected, "REJECTED")["output"] is None
            cancelled, _ = create(base)
            await_state(base, cancelled, "WAITING_APPROVAL")
            request(base, "POST", f"/api/runs/{cancelled}/cancel", expected=202)
            assert await_state(base, cancelled, "CANCELLED")["output"] is None
            expired, _ = create(base, seconds=2)
            assert await_state(base, expired, "TIMED_OUT")["output"] is None
            print("PASS rejection, cancellation, approval timeout and duplicate Run protection", flush=True)
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=15)


if __name__ == "__main__":
    main()
