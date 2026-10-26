#!/usr/bin/env python3
"""Isolated, recorded DCOP/L-DCOP virtual-transport experiments. Standard library only."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
import zipfile


def port_busy(port):
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("127.0.0.1", port))
            return False
        except OSError:
            return True


def override(path, values):
    text = path.read_text()
    for key, value in values.items():
        pattern = rf"(?m)^{re.escape(key)}\s*[:=].*$"
        replacement = f"{key}: {value}"
        if re.search(pattern, text):
            text = re.sub(pattern, replacement, text)
        else:
            text += "\n" + replacement + "\n"
    path.write_text(text)


def runtime_settings(agent):
    settings = dict(agent_heap_mb=6144, think_time_ms=120000,
                    startup_time_ms=300000, precompute_timeout_seconds=300)
    path = agent / "config/paper-runtime.json"
    if path.exists():
        configured = json.loads(path.read_text())
        if not isinstance(configured, dict) or configured.keys() - settings.keys():
            raise ValueError("unknown paper runtime settings")
        settings.update(configured)
    if any(type(value) is not int or value <= 0 for value in settings.values()):
        raise ValueError("paper runtime settings must be positive integers")
    if settings["agent_heap_mb"] > 8192:
        raise ValueError("Agent heap above 8192 MB requires a separate host memory budget review")
    return settings


def prepare(agent, server, map_name, mode, seed, steps, output, port):
    runtime = runtime_settings(agent)
    if not re.fullmatch(r"[A-Za-z0-9_-]+", map_name):
        raise ValueError("invalid map name")
    source_map = server / "maps" / map_name
    if not (source_map / "map" / "map.gml").is_file():
        raise ValueError(f"missing map: {source_map}")
    stamp = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).strftime("%Y%m%d_%H%M%S_%f")
    run = output / f"{stamp}_{map_name}_{mode}_seed{seed}"
    run.mkdir(parents=True, exist_ok=False)
    overlay = run / "server"
    (overlay / "scripts").mkdir(parents=True)
    scenario = overlay / "maps" / map_name
    scenario.mkdir(parents=True)
    shutil.copytree(source_map / "config", scenario / "config")
    (scenario / "map").symlink_to(source_map / "map", target_is_directory=True)
    for name in ("jars", "lib"):
        (overlay / name).symlink_to(server / name, target_is_directory=True)
    for name in ("functions.sh", "start-precompute.sh", "start-comprun.sh"):
        shutil.copy2(server / "scripts" / name, overlay / "scripts" / name)
    # INFO logs keep the experiment from being dominated by DEBUG perception dumps.
    logging = (server / "log4j.properties").read_text()
    logging = logging.replace("log4j.rootLogger=trace", "log4j.rootLogger=info")
    logging = re.sub(r"(?m)^(log4j.category.[^=]+=)(trace|debug)", r"\1info", logging)
    (overlay / "log4j.properties").write_text(logging)
    if (server / "modules").is_dir():
        (overlay / "modules").symlink_to(server / "modules", target_is_directory=True)
        (overlay / "scripts/modules").symlink_to(server / "modules", target_is_directory=True)
    config = scenario / "config"
    override(config / "common.cfg", {"kernel.port": port, "random.seed": seed})
    overrides = {
        "kernel.perception.auto": "AndroidRoboTeam.module.complex.paper.PaperFullCityPerception",
        "perception.standard.view-distance": 100000000,
        "perception.standard.hp-precision": 1,
        "perception.standard.damage-precision": 1,
        "kernel.agents.think-time": runtime["think_time_ms"],
        "kernel.commandcollectors": "AndroidRoboTeam.module.complex.paper.PaperCommandCollector",
        "kernel.startup.connect-time": runtime["startup_time_ms"],
        "comms.channels.0.range": 100000000,
        "comms.channels.0.noise.input.dropout.use": "no",
        "comms.channels.1.bandwidth": 1000000,
        "comms.channels.2.bandwidth": 1000000,
    }
    if steps is not None:
        overrides["kernel.timesteps"] = steps
    override(config / "kernel.cfg", overrides)
    # Every component must connect to this experiment, not a currently running competition server.
    for cfg in config.rglob("*.cfg"):
        text = cfg.read_text()
        text = re.sub(r"(?m)^kernel.port\s*[:=].*$", f"kernel.port: {port}", text)
        cfg.write_text(text)
    sandbox = run / "agent-fs"
    (sandbox / "config").mkdir(parents=True)
    for name in ("launch.cfg",):
        shutil.copy2(agent / "config" / name, sandbox / "config" / name)
    shutil.copy2(agent / "config" / "paper-module.cfg", sandbox / "config" / "module.cfg")
    develop = json.loads((agent / "config" / "paper-develop.json").read_text())
    develop["Paper.dcopBaseline"] = mode == "dcop"
    (sandbox / "config" / "develop.json").write_text(json.dumps(develop, indent=2) + "\n")
    shutil.copy2(agent / "src/main/resources/log4j.properties", sandbox / "log4j.properties")
    manifest = {
        "mode": mode, "map": map_name, "seed": seed, "steps_override": steps,
        "source_agent": str(agent), "source_server": str(server), "kernel_port": port,
        "full_city_perception": True, "transport": "virtual", "coordination": "loopback-barrier",
        "paper_numerical_replication_claimed": False,
        "runtime_settings": runtime,
        "assumptions": "See docs/paper-reproduction.md; authors' exact costs/scenarios unavailable",
    }
    (run / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (run / "runtime-settings.json").write_text(json.dumps(runtime, indent=2) + "\n")
    return run


def stop(process):
    if process is None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    limit = time.monotonic() + 5
    while time.monotonic() < limit:
        process.poll()  # Reap the group leader even if Java children outlive it.
        try:
            os.killpg(process.pid, 0)
        except ProcessLookupError:
            return
        time.sleep(0.1)
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        pass


def spawn(command, cwd, log, env):
    with log.open("w") as output:
        return subprocess.Popen(command, cwd=cwd, env=env, stdout=output,
                                stderr=subprocess.STDOUT, start_new_session=True)


def content(path):
    return path.read_text(errors="replace") if path.exists() else ""


def wait_ready(log, marker, process, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if marker in content(log):
            return
        if process.poll() is not None:
            raise RuntimeError(f"process exited before {marker}; inspect {log}")
        time.sleep(0.2)
    raise TimeoutError(f"timeout waiting for {marker}; inspect {log}")


def summarize(run, status):
    snapshots = [json.loads(p.read_text()) for p in (run / "metrics").glob("agent-*.json")]
    latest = max((s["time"] for s in snapshots), default=-1)
    current = [s for s in snapshots if s["time"] == latest]
    signatures = {(s["cohort"], s["rescued"], s["virtualTransported"], s["unknownArrivals"]) for s in current}
    representative = min(current, key=lambda s: s["actor"]) if current else {}
    active_actors = set(representative.get("activeActors", [s["actor"] for s in snapshots]))
    completed_steps = re.findall(r"Timestep (\d+) complete", content(run/"run-kernel/kernel.log"))
    kernel_step = int(completed_steps[-1]) if completed_steps else None
    cohort = representative.get("cohort", 0)
    summary = {
        "status": status, "last_observed_timestep": latest,
        "metric_snapshots": len(snapshots),
        "kernel_completed_timestep": kernel_step,
        "metrics_consistent": bool(current) and len(signatures) == 1
                              and active_actors.issubset({s["actor"] for s in current})
                              and (kernel_step is None or latest == kernel_step),
        "cohort": cohort, "rescued": representative.get("rescued", 0),
        "virtual_transported": representative.get("virtualTransported", 0),
        "unknown_arrivals": representative.get("unknownArrivals", 0),
        "rescue_rate": representative.get("rescued", 0) / cohort if cohort else None,
        "virtual_transport_rate": representative.get("virtualTransported", 0) / cohort if cohort else None,
        "target_switches_sum": sum(s["targetSwitches"] for s in snapshots),
        "decision_count": sum(s.get("decisionCount",0) for s in snapshots),
        "decision_ms_sum": sum(s.get("totalDecisionMs",0) for s in snapshots),
        "max_decision_ms": max((s.get("maxDecisionMs",0) for s in snapshots), default=0),
        "official_kernel_score": None,
        "note": "Virtual transport rates are not the official RCRS kernel score.",
    }
    (run / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    (run / "summary.md").write_text(
        "# Paper experiment\n\n" + "\n".join(f"- {k}: {v}" for k,v in summary.items()) + "\n")
    return summary


def execute(agent, run, map_name, port, relay_port, minutes):
    runtime = json.loads((run / "runtime-settings.json").read_text())
    startup_seconds = runtime["startup_time_ms"] / 1000
    init = agent / "scripts/paper-runtime.gradle"
    resolution = subprocess.run([str(agent / "gradlew"), "--offline", "-q", "-I", str(init), "paperRuntimeClasspath"],
                                cwd=agent, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
    (run / "classpath-resolution.log").write_text(resolution.stdout)
    deps = [line.split("=",1)[1] for line in resolution.stdout.splitlines() if line.startswith("PAPER_RUNTIME_CP=")]
    if len(deps) != 1:
        raise RuntimeError("cannot resolve exact dependency classpath")
    jars = sorted((agent / "build/libs").glob("*.jar"))
    jars = [j for j in jars if not j.name.endswith(("-sources.jar", "-javadoc.jar"))]
    if len(jars) != 1:
        raise RuntimeError("expected exactly one built Agent JAR; run ./gradlew --offline test build")
    if jars[0].stat().st_mtime < max(p.stat().st_mtime for p in (agent / "src/main").rglob("*") if p.is_file()):
        raise RuntimeError("Agent JAR is older than source; rebuild before testing")
    shutil.copy2(jars[0], run / "agent.jar")
    shutil.make_archive(str(run / "agent-source"), "gztar", agent, "src")
    shutil.copy2(agent / "build.gradle", run / "build.gradle.snapshot")
    dependency_hashes = []
    for dependency in deps[0].split(":"):
        path = Path(dependency)
        if path.is_file():
            dependency_hashes.append(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + dependency)
    (run/"dependencies.sha256").write_text("\n".join(dependency_hashes)+"\n")
    # The kernel only needs this one class, not the Agent/ADF dependency graph.
    with zipfile.ZipFile(run / "agent.jar") as jar, zipfile.ZipFile(run / "server/jars-placeholder.zip", "w") as helper:
        for name in ("PaperCommandCollector", "PaperFullCityPerception"):
            entry = f"AndroidRoboTeam/module/complex/paper/{name}.class"
            helper.writestr(entry, jar.read(entry))
    # Never write into the original jars directory through its symlink.
    jars_link = run / "server/jars"
    jars_link.unlink()
    jars_link.mkdir()
    # Use only the selected server's libraries, including for --server-root overrides.
    source_server = Path(json.loads((run/"manifest.json").read_text())["source_server"])
    for dependency in (source_server/"jars").glob("*.jar"):
        target = jars_link / dependency.name
        if not target.exists():
            target.symlink_to(dependency.resolve())
    (run / "server/jars-placeholder.zip").rename(jars_link / "paper-collector.jar")
    digest = hashlib.sha256((run / "agent.jar").read_bytes()).hexdigest()
    (run / "agent-jar.sha256").write_text(digest + "  agent.jar\n")
    cp = str(run / "agent.jar") + ":" + deps[0]
    (run / "runtime-classpath.txt").write_text(cp + "\n")
    env = dict(os.environ, PAPER_LDCOP_TOKEN=secrets.token_hex(32), PAPER_LDCOP_PORT=str(relay_port),
               PAPER_METRICS_DIR=str(run / "metrics"))
    sandbox = run / "agent-fs"
    base = ["java", f"-Xmx{runtime['agent_heap_mb']}m", "-XX:+ExitOnOutOfMemoryError",
            "-cp", cp, "adf.core.Main", "adf.impl.DefaultLoader",
            "-s", f"localhost:{port}", "-dev", "1", "-mc", str(sandbox / "config/module.cfg"),
            "-df", str(sandbox / "config/develop.json")]
    scripts = run / "server/scripts"
    server = agent_process = relay = None
    status = "failed"
    try:
        prelogs = run / "precompute-kernel"
        server = spawn(["bash", "./start-precompute.sh", "-m", f"../maps/{map_name}/map", "-c", f"../maps/{map_name}/config",
                        "-l", str(prelogs), "-g"], scripts, run / "precompute-server.log", env)
        wait_ready(prelogs / "kernel.log", f"Listening for connections on port {port}", server, startup_seconds)
        agent_process = spawn(base + ["-t","1,0,1,0,1,0","-pre","1"], sandbox, run / "precompute-agent.log", env)
        if agent_process.wait(timeout=runtime["precompute_timeout_seconds"]) != 0:
            raise RuntimeError("precomputation Agent failed")
        for name in ("ambulance.bin", "fire.bin", "police.bin"):
            path = sandbox / "precomp_data" / name
            if not path.is_file() or path.stat().st_size == 0:
                raise RuntimeError(f"missing precomputation: {name}")
        stop(agent_process); agent_process = None
        stop(server); server = None
        if port_busy(port):
            raise RuntimeError("precomputation left its listening port occupied")
        relay = spawn(["java","-Xmx256m","-cp",cp,"AndroidRoboTeam.module.complex.paper.PaperRelay",str(relay_port)],
                      sandbox, run / "relay.log", env)
        wait_ready(run / "relay.log", "listening on loopback", relay, 10)
        logs = run / "run-kernel"
        server = spawn(["bash", "./start-comprun.sh", "-m", f"../maps/{map_name}/map", "-c", f"../maps/{map_name}/config",
                        "-l", str(logs), "-g"], scripts, run / "run-server.log", env)
        wait_ready(logs / "kernel.log", f"Listening for connections on port {port}", server, startup_seconds)
        agent_process = spawn(base + ["-all"], sandbox, run / "run-agent.log", env)
        deadline = time.monotonic() + minutes * 60
        while time.monotonic() < deadline:
            agent_log = content(run / "run-agent.log")
            if "OutOfMemoryError" in agent_log:
                raise RuntimeError("Agent heap exhausted; see run-agent.log and runtime-settings.json")
            if "[PAPER-ERROR]" in agent_log:
                raise RuntimeError("paper protocol failed; experiment invalid, see run-agent.log")
            if "[PAPER-COLLECTOR-TIMEOUT]" in content(logs / "kernel.log"):
                raise RuntimeError("an agent missed its action deadline; comparison invalid")
            if "Kernel has shut down" in content(logs / "kernel.log"):
                status = "complete"
                break
            if agent_process.poll() is not None or server.poll() is not None or relay.poll() is not None:
                raise RuntimeError("experiment component exited early")
            time.sleep(0.5)
        else:
            status = "timeout"
    except (Exception, KeyboardInterrupt) as error:
        (run / "failure.txt").write_text(str(error) + "\n")
        print(f"Experiment failed: {error}", file=sys.stderr)
    finally:
        stop(agent_process); stop(server); stop(relay)
        summary = summarize(run, status)
        print(json.dumps(summary, indent=2))
    return 0 if status == "complete" and summary["metrics_consistent"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("map")
    parser.add_argument("--mode", choices=("ldcop","dcop"), default="ldcop")
    parser.add_argument("--seed", type=int, default=32425)
    parser.add_argument("--steps", type=int, help="short smoke test only; omitted keeps original map length")
    parser.add_argument("--port", type=int, default=28031)
    parser.add_argument("--relay-port", type=int, default=28033)
    parser.add_argument("--timeout-min", type=int, default=120)
    parser.add_argument("--agent-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--server-root", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--prepare-only", action="store_true", help="write isolated configs without starting any processes")
    args = parser.parse_args()
    agent = args.agent_root.resolve()
    server = (args.server_root or agent.parent / "rcrs-server").resolve()
    output = (args.output or agent.parent / "paper_ldcop_experiments").resolve()
    if args.steps is not None and args.steps < 1 or args.timeout_min < 1:
        parser.error("steps/timeout must be positive")
    if not 1024 <= args.port < 65535 or not 1024 <= args.relay_port <= 65535 or args.relay_port in (args.port,args.port+1):
        parser.error("reserve kernel port AND kernel port+1; use a different unprivileged relay port")
    if not args.prepare_only and (port_busy(27931) or port_busy(args.port) or port_busy(args.port+1) or port_busy(args.relay_port)):
        parser.error("another map/experiment is running; wait for it to finish before starting the comparison")
    run = prepare(agent,server,args.map,args.mode,args.seed,args.steps,output,args.port)
    print(f"Experiment directory: {run}", flush=True)
    if args.prepare_only:
        return 0
    # Every invocation owns its own filesystem and process groups; original maps/configs stay intact.
    try:
        return execute(agent,run,args.map,args.port,args.relay_port,args.timeout_min)
    except Exception as error:
        (run/"failure.txt").write_text(str(error)+"\n")
        summarize(run,"launch_failed")
        print(f"Experiment could not start: {error}",file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
