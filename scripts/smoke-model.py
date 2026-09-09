"""Explicit, bounded live-model acceptance using a local credential file or environment.

Uses in-memory Temporal and H2, not the PostgreSQL cross-process recovery smoke.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, help="Existing local dotenv file; never copied into the repository")
    parser.add_argument("--pricing-version", required=True, help="Explicit estimate/rate version; not a provider bill")
    parser.add_argument("--input-microusd-per-million", type=int, required=True)
    parser.add_argument("--output-microusd-per-million", type=int, required=True)
    args = parser.parse_args()
    values = dict(os.environ)
    if args.env_file:
        for line in args.env_file.read_text(encoding="utf-8-sig").splitlines():
            match = re.match(r"\s*(LLM_BASE_URL|LLM_MODEL|LLM_API_KEY)\s*=\s*(.*)", line)
            if match:
                values[match[1]] = match[2].strip().strip("\"'")
    env = dict(os.environ)
    for destination, source in (("BASE_URL", "LLM_BASE_URL"), ("NAME", "LLM_MODEL"), ("API_KEY", "LLM_API_KEY")):
        value = values.get(source)
        if not value:
            raise SystemExit(f"Missing {source}; provide the local environment or --env-file")
        env["PLATFORM_LIVE_MODEL_" + destination] = value
    env.update(PLATFORM_LIVE_MODEL_TEST="true", PLATFORM_LIVE_MODEL_PRICING_VERSION=args.pricing_version,
               PLATFORM_LIVE_MODEL_INPUT_PRICE=str(args.input_microusd_per_million),
               PLATFORM_LIVE_MODEL_OUTPUT_PRICE=str(args.output_microusd_per_million))
    root = Path(__file__).resolve().parents[1]
    (root / "var").mkdir(exist_ok=True)
    log = root / "var/model-live.log"
    wrapper = root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    command = [str(wrapper), "-B", "-ntp", "-pl", "platform-api", "-am", "test",
               "-Dtest=SpringAiRunApiTest", "-Dsurefire.failIfNoSpecifiedTests=false"]
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run(command, cwd=root, env=env, stdout=output, stderr=subprocess.STDOUT,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if result.returncode:
        raise SystemExit("Live acceptance failed; inspect var/model-live.log locally")
    for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("MODEL_ACCEPTANCE "):
            summary = json.loads(line.removeprefix("MODEL_ACCEPTANCE "))
            print(json.dumps(summary, ensure_ascii=False))
            return
    raise SystemExit("No live acceptance result was reported")


if __name__ == "__main__":
    main()
