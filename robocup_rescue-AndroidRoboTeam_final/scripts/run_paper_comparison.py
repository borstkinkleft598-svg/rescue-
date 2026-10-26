#!/usr/bin/env python3
"""Run paired experiments sequentially, keeping maps/seeds/length identical."""
import argparse
import datetime as dt
import json
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maps", nargs="+", required=True)
    parser.add_argument("--seeds", nargs="+", type=int, default=[32425])
    parser.add_argument("--modes", nargs="+", choices=("dcop", "ldcop"), default=["dcop", "ldcop"])
    parser.add_argument("--steps", type=int)
    parser.add_argument("--server-root", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    agent = Path(__file__).resolve().parents[1]
    output = args.output or agent.parent / "paper_ldcop_experiments"
    stamp = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).strftime("%Y%m%d_%H%M%S_%f")
    batch = output.resolve() / f"comparison_{stamp}"
    batch.mkdir(parents=True)
    print(f"Experiment batch directory: {batch}", flush=True)
    (batch/"comparison.md").write_text("# Experiment results\n\nRunning; waiting for the first completed map.\n\nVirtual transport metrics; not official kernel scores.\n")
    records = []
    for map_name in args.maps:
        for seed in args.seeds:
            for mode in args.modes:
                print(f"Starting map={map_name} seed={seed} mode={mode}", flush=True)
                command = [sys.executable, str(agent/"scripts/run_paper_experiment.py"), map_name,
                           "--mode",mode,"--seed",str(seed),"--output",str(batch)]
                if args.server_root:
                    command += ["--server-root",str(args.server_root.resolve())]
                if args.steps:
                    command += ["--steps",str(args.steps)]
                completed = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                print(completed.stdout, flush=True)
                directories = [line.removeprefix("Experiment directory: ") for line in completed.stdout.splitlines()
                               if line.startswith("Experiment directory: ")]
                summary = json.loads((Path(directories[0])/"summary.json").read_text()) if directories and (Path(directories[0])/"summary.json").exists() else {"status":"launch_failed"}
                records.append(dict(map=map_name,seed=seed,mode=mode,exit_code=completed.returncode,
                                    directory=directories[0] if directories else None,**summary))
                (batch/"comparison.json").write_text(json.dumps(records,indent=2)+"\n")
                rows = ["# Experiment results", "", "Virtual transport metrics; not official kernel scores.", "",
                        "|Map|Seed|Mode|Status|Rescued|Virtually transported|Cohort|", "|---|---:|---|---|---:|---:|---:|"]
                rows += [f"|{r['map']}|{r['seed']}|{r['mode']}|{r['status']}|{r.get('rescued','N/A')}|{r.get('virtual_transported','N/A')}|{r.get('cohort','N/A')}|" for r in records]
                (batch/"comparison.md").write_text("\n".join(rows)+"\n")
                if not directories:
                    print("Comparison stopped: launch conditions are not satisfied.",file=sys.stderr)
                    return 1
    print(f"Comparison report: {batch/'comparison.md'}")
    return 0 if all(r["exit_code"] == 0 for r in records) else 1


if __name__ == "__main__":
    sys.exit(main())
