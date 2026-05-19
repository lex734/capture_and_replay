#!/usr/bin/env python3

import argparse
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple


DEADLOCK_BENCHMARKS = {
    "cmu.pasta.fray.benchmark.sctbench.cs.origin.Carter01Bad",
    "cmu.pasta.fray.benchmark.sctbench.cs.origin.Deadlock01Bad",
    "cmu.pasta.fray.benchmark.sctbench.cs.origin.Phase01Bad",
    "cmu.pasta.fray.benchmark.sctbench.cs.origin.Sync01Bad",
    "cmu.pasta.fray.benchmark.sctbench.cs.origin.Sync02Bad",
}

BUG_SIGNAL_RE = re.compile(
    r"AssertionError|Bug [Ff]ound!|Deadlock detected|RuntimeException: deadlock|Error found at iter"
)

INCOMPLETE_REPLAY_SIGNALS = (
    "[DIVERGENCE]",
    "Replay has structurally diverged",
    "valued event mismatch",
    "site mismatch",
    "object mismatch",
    "Incomplete replay",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Measure SCTBench overhead for Fray using the same "
            "warmup + fixed-window methodology as overhead/BenchmarkRunner.java."
        )
    )
    parser.add_argument(
        "--tests",
        nargs="*",
        default=None,
        help="Optional list of SCTBench simple names or fully-qualified class names",
    )
    parser.add_argument(
        "--warmup",
        type=int,
        default=3,
        help="Warmup rounds before timing each mode (default: 3)",
    )
    parser.add_argument(
        "--measure",
        type=int,
        default=10,
        help="Replay rounds per saved capture artifact (default: 10)",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=8,
        help="Per-run timeout in seconds (default: 8)",
    )
    parser.add_argument(
        "--measure-window",
        type=int,
        default=30,
        help="Wall-clock measurement window in seconds (default: 30)",
    )
    parser.add_argument(
        "--fray-scheduler",
        default="random",
        choices=["random", "pos", "surw", "pct3", "pct15"],
        help="Scheduler to use for Fray capture runs (default: random)",
    )
    parser.add_argument(
        "--java",
        default=os.environ.get("JAVA_CMD", "java"),
        help="Java executable for plain baseline runs (default: JAVA_CMD or java)",
    )
    parser.add_argument(
        "--output-root",
        default=None,
        help="Override output root directory",
    )
    return parser.parse_args()


def read_text(path: Path) -> str:
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def write_text(path: Path, data: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(data, encoding="utf-8")


def shell_join(command: Sequence[str]) -> str:
    return shlex.join(list(command))


def slugify_test_name(class_name: str) -> str:
    return class_name.split(".")[-1]


def inspect_layout_roots(candidates: Sequence[Path]) -> Optional[Dict[str, Path]]:
    for candidate in candidates:
        if (candidate / "fray_benchmark").exists() and (candidate / "bms").exists():
            return {
                "repo_root": candidate,
                "bench_root": candidate,
                "nested_root": candidate,
                "output_root": candidate / "output" / "capture-replay" / "sctbench-overhead",
            }

        nested_root = candidate / "benchmark" / "fray-benchmark"
        if (nested_root / "fray_benchmark").exists() and (candidate / "benchmark" / "bms").exists():
            return {
                "repo_root": candidate,
                "bench_root": candidate / "benchmark",
                "nested_root": nested_root,
                "output_root": candidate / "benchmark" / "output" / "capture-replay" / "sctbench-overhead",
            }
    return None


def discover_layout(script_path: Path) -> Dict[str, Path]:
    override = os.environ.get("SCTBENCH_OVERHEAD_ROOT")
    candidates: List[Path] = []
    if override:
        candidates.append(Path(override).resolve())
    candidates.extend([script_path.parent, *script_path.parents, Path.cwd().resolve()])
    layout = inspect_layout_roots(candidates)
    if layout is not None:
        return layout
    raise RuntimeError(
        "Unable to locate benchmark layout. "
        f"Tried script path {script_path} and cwd {Path.cwd().resolve()}. "
        "Set SCTBENCH_OVERHEAD_ROOT to the fray-benchmark root if needed."
    )


def resolve_paths(layout: Dict[str, Path]) -> Dict[str, Path]:
    bench_root = layout["bench_root"]
    nested_root = layout["nested_root"]
    jar_candidates = sorted((bench_root / "bms" / "SCTBench" / "build" / "libs").glob("*.jar"))
    sctbench_jar = next((jar for jar in jar_candidates if "fray-benchmark" in jar.name), None)
    if sctbench_jar is None and jar_candidates:
        sctbench_jar = jar_candidates[0]
    return {
        "repo_root": layout["repo_root"],
        "bench_root": bench_root,
        "nested_root": nested_root,
        "output_root": layout["output_root"],
        "assets_file": nested_root / "fray_benchmark" / "assets" / "sctbench.txt",
        "sctbench_jar": sctbench_jar or bench_root / "bms" / "SCTBench" / "build" / "libs" / "fray-benchmark-1.0-SNAPSHOT.jar",
        "fray_root": nested_root / "tools" / "fray",
        "fray_java": nested_root / "tools" / "fray" / "result" / "java-inst-jdk21" / "bin" / "java",
        "fray_jvmti": nested_root / "tools" / "fray" / "result" / "native-libs" / "libjvmti.so",
        "fray_core_jar": nested_root / "tools" / "fray" / "result" / "libs" / "fray-core-0.5.2-SNAPSHOT.jar",
        "fray_agent_jar": nested_root / "tools" / "fray" / "result" / "libs" / "fray-instrumentation-agent-0.5.2-SNAPSHOT.jar",
    }


def validate_or_fail(paths: Dict[str, Path]) -> None:
    required = [
        ("SCTBench class list", paths["assets_file"]),
        ("SCTBench jar", paths["sctbench_jar"]),
    ]
    missing = []
    for label, path in required:
        if not path.exists():
            missing.append(f"{label}: {path}")
    for label, key in (
        ("Fray instrumented Java", "fray_java"),
        ("Fray JVMTI library", "fray_jvmti"),
        ("Fray core jar", "fray_core_jar"),
        ("Fray instrumentation agent jar", "fray_agent_jar"),
    ):
        if not paths[key].exists():
            missing.append(f"{label}: {paths[key]}")
    if missing:
        print("Missing prerequisites:", file=sys.stderr)
        for item in missing:
            print(f"  - {item}", file=sys.stderr)
        sys.exit(1)


def load_tests(assets_file: Path, selected: Optional[Sequence[str]]) -> List[str]:
    tests = [
        line.strip()
        for line in assets_file.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    if not selected:
        return tests
    selected_set = set(selected)
    filtered = [test for test in tests if test in selected_set or slugify_test_name(test) in selected_set]
    if not filtered:
        raise ValueError("No SCTBench tests matched --tests selection")
    return filtered


def has_bug_signal(text: str) -> bool:
    return BUG_SIGNAL_RE.search(text) is not None


def has_incomplete_replay_signal(text: str) -> bool:
    return any(signal in text for signal in INCOMPLETE_REPLAY_SIGNALS)


def had_bug(class_name: str, run_result: Dict[str, object]) -> bool:
    if has_bug_signal(str(run_result["output"])):
        return True
    return bool(run_result["timed_out"]) and class_name in DEADLOCK_BENCHMARKS


def mean(values: Sequence[int]) -> Optional[float]:
    if not values:
        return None
    return sum(values) / len(values)


def stddev(values: Sequence[int]) -> Optional[float]:
    if not values:
        return None
    m = mean(values)
    if m is None:
        return None
    variance = sum((value - m) * (value - m) for value in values) / len(values)
    return variance ** 0.5


def format_stats(values: Sequence[int]) -> str:
    m = mean(values)
    s = stddev(values)
    if m is None or s is None:
        return "N/A"
    return f"{m:.1f} +- {s:.1f}"


def format_ratio(numerator: Sequence[int], denominator: Sequence[int]) -> str:
    m_num = mean(numerator)
    m_den = mean(denominator)
    if m_num is None or m_den is None or m_den == 0:
        return "N/A"
    return f"{m_num / m_den:.2f}x"


def safe_workload_name(class_name: str) -> str:
    return class_name.replace(".", "_")


def delete_path(path: Path) -> None:
    if path.is_dir():
        shutil.rmtree(path, ignore_errors=True)
    else:
        try:
            path.unlink()
        except FileNotFoundError:
            pass


def copy_tree(src: Path, dest: Path) -> None:
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(src, dest)


def run_process(
    command: Sequence[str],
    cwd: Path,
    env: Dict[str, str],
    timeout_seconds: int,
    log_prefix: Path,
) -> Dict[str, object]:
    log_prefix.parent.mkdir(parents=True, exist_ok=True)
    write_text(log_prefix.with_suffix(".command.txt"), shell_join(command))
    started = time.time()
    timed_out = False
    try:
        proc = subprocess.run(
            list(command),
            cwd=str(cwd),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout_seconds,
            check=False,
            text=True,
        )
        output = proc.stdout or ""
        exit_code = proc.returncode
        completed = exit_code == 0
    except subprocess.TimeoutExpired as exc:
        timed_out = True
        output = (exc.stdout or "") if isinstance(exc.stdout, str) else ""
        exit_code = -1
        completed = False
    elapsed_ms = int((time.time() - started) * 1000)
    write_text(log_prefix.with_suffix(".stdout.txt"), output)
    return {
        "completed": completed,
        "timed_out": timed_out,
        "exit_code": exit_code,
        "elapsed_ms": elapsed_ms,
        "output": output,
    }


def build_plain_command(paths: Dict[str, Path], java_cmd: str, class_name: str) -> Tuple[List[str], Dict[str, str], Path]:
    command = [
        java_cmd,
        "-ea",
        "--add-opens",
        "java.base/java.lang=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.util.concurrent=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.util.concurrent.locks=ALL-UNNAMED",
        "-cp",
        str(paths["sctbench_jar"]),
        class_name,
    ]
    return command, os.environ.copy(), paths["bench_root"]


def write_fray_config(config_path: Path, class_name: str, classpath: Path) -> None:
    data = {
        "executor": {
            "clazz": class_name,
            "method": "main",
            "args": [],
            "classpaths": [str(classpath)],
            "properties": {},
        },
        "ignore_unhandled_exceptions": False,
        "interleave_memory_ops": False,
        "max_scheduled_step": -1,
        "timed_wait_wait_inf": False,
    }
    write_text(config_path, json.dumps(data, indent=2))


def fray_scheduler_args(name: str) -> List[str]:
    if name == "pct3":
        return ["--scheduler=pct", "--num-switch-points=3"]
    if name == "pct15":
        return ["--scheduler=pct", "--num-switch-points=15"]
    return [f"--scheduler={name}"]


def build_fray_capture_command(
    paths: Dict[str, Path],
    config_path: Path,
    report_dir: Path,
    scheduler: str,
    timeout_seconds: int,
) -> Tuple[List[str], Dict[str, str], Path]:
    command = [
        str(paths["fray_java"]),
        "-ea",
        f"-agentpath:{paths['fray_jvmti']}",
        f"-javaagent:{paths['fray_agent_jar']}",
        "--add-opens",
        "java.base/java.lang=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.util=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.io=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.util.concurrent.atomic=ALL-UNNAMED",
        "--add-opens",
        "java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.lang.reflect=ALL-UNNAMED",
        "-cp",
        str(paths["fray_core_jar"]),
        "org.pastalab.fray.core.MainKt",
        "--run-config",
        "json",
        "--config-path",
        str(config_path),
        *fray_scheduler_args(scheduler),
        "--iter",
        "1",
        "--timeout",
        str(timeout_seconds),
        "--sleep-as-yield",
        "-o",
        str(report_dir),
    ]
    return command, os.environ.copy(), paths["fray_root"]


def build_fray_replay_command(
    paths: Dict[str, Path],
    config_path: Path,
    recording_dir: Path,
    report_dir: Path,
    timeout_seconds: int,
) -> Tuple[List[str], Dict[str, str], Path]:
    command = [
        str(paths["fray_java"]),
        "-ea",
        f"-agentpath:{paths['fray_jvmti']}",
        f"-javaagent:{paths['fray_agent_jar']}",
        "--add-opens",
        "java.base/java.lang=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.util=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.io=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.util.concurrent.atomic=ALL-UNNAMED",
        "--add-opens",
        "java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.lang.reflect=ALL-UNNAMED",
        "-cp",
        str(paths["fray_core_jar"]),
        "org.pastalab.fray.core.MainKt",
        "--run-config",
        "json",
        "--config-path",
        str(config_path),
        "--scheduler=replay",
        f"--path-to-scheduler={recording_dir}",
        "--iter",
        "1",
        "--timeout",
        str(timeout_seconds),
        "--sleep-as-yield",
        "-o",
        str(report_dir),
    ]
    return command, os.environ.copy(), paths["fray_root"]


def warmup(command_builder, count: int) -> None:
    for _ in range(count):
        command_builder()


def measure_runs_for_duration(
    class_name: str,
    run_once,
    measure_window_seconds: int,
) -> Dict[str, object]:
    buggy_ms: List[int] = []
    clean_ms: List[int] = []
    attempts = 0
    buggy_runs = 0
    clean_runs = 0
    deadline = time.monotonic() + measure_window_seconds
    while attempts == 0 or time.monotonic() < deadline:
        result = run_once()
        attempts += 1
        if had_bug(class_name, result):
            buggy_runs += 1
            buggy_ms.append(int(result["elapsed_ms"]))
        elif bool(result["completed"]):
            clean_runs += 1
            clean_ms.append(int(result["elapsed_ms"]))
    return {
        "buggy_ms": buggy_ms,
        "clean_ms": clean_ms,
        "attempts": attempts,
        "buggy_runs": buggy_runs,
        "clean_runs": clean_runs,
    }


def measure_capture_and_replay_for_duration(
    class_name: str,
    capture_once,
    replay_once,
    artifact_exists,
    save_artifact,
    prepare_replay_artifact,
    cleanup_capture_artifact,
    measure_window_seconds: int,
    replay_measure_cap: int,
    bug_outcome: bool,
) -> Dict[str, object]:
    cap_ms: List[int] = []
    rep_ms: List[int] = []
    attempts = 0
    cap_runs = 0
    artifacts: List[Path] = []
    missing_artifact = False
    replay_status: Optional[str] = "N/A"
    deadline = time.monotonic() + measure_window_seconds

    while attempts == 0 or time.monotonic() < deadline:
        cleanup_capture_artifact()
        capture_result = capture_once(attempts + 1)
        attempts += 1
        capture_bug = had_bug(class_name, capture_result)
        capture_matches = capture_bug if bug_outcome else ((not capture_bug) and bool(capture_result["completed"]))
        if not capture_matches:
            continue
        cap_runs += 1
        cap_ms.append(int(capture_result["elapsed_ms"]))
        if not artifact_exists():
            missing_artifact = True
            continue
        artifacts.append(save_artifact(cap_runs))

    saw_replay_mismatch = False
    for artifact in artifacts:
        for replay_index in range(1, replay_measure_cap + 1):
            prepare_replay_artifact(artifact)
            replay_result = replay_once(artifact, replay_index)
            replay_bug = had_bug(class_name, replay_result)
            replay_matches = replay_bug if bug_outcome else (
                (not replay_bug)
                and bool(replay_result["completed"])
                and (not has_incomplete_replay_signal(str(replay_result["output"])))
            )
            if replay_matches:
                rep_ms.append(int(replay_result["elapsed_ms"]))
                replay_status = None
            else:
                saw_replay_mismatch = True

    if replay_status is not None:
        if artifacts and saw_replay_mismatch:
            replay_status = "INCOMPLETE"
        elif artifacts:
            replay_status = "INCOMPLETE" if not rep_ms else None
        elif missing_artifact:
            replay_status = "NO_TRACE"

    return {
        "cap_ms": cap_ms,
        "cap_runs": cap_runs,
        "cap_attempts": attempts,
        "rep_ms": rep_ms,
        "replay_status": replay_status,
    }


def print_header() -> None:
    print(
        f"{'Workload':<28}  {'Baseline (ms)':>16}  {'Capture (ms)':>16}  "
        f"{'Replay (ms)':>16}  {'Plain':>10}  {'Cap':>10}  {'Cap/Base':>8}  {'Rep/Base':>8}"
    )
    print("-" * 127)


def format_count(runs: int, attempts: int) -> str:
    return f"{runs}/{attempts}"


def print_section(title: str, rows: List[Dict[str, object]], key: str) -> None:
    print(title)
    print_header()
    for row in rows:
        outcome = row[key]
        replay_rendered = outcome["replay_status"] if outcome["replay_status"] else format_stats(outcome["rep_ms"])
        print(
            f"{row['test_name']:<28}  "
            f"{format_stats(outcome['base_ms']):>16}  "
            f"{format_stats(outcome['cap_ms']):>16}  "
            f"{replay_rendered:>16}  "
            f"{format_count(outcome['base_runs'], outcome['base_attempts']):>10}  "
            f"{format_count(outcome['cap_runs'], outcome['cap_attempts']):>10}  "
            f"{format_ratio(outcome['cap_ms'], outcome['base_ms']):>8}  "
            f"{format_ratio(outcome['rep_ms'], outcome['base_ms']):>8}"
        )
    print()


def append_tsv(path: Path, values: Sequence[object]) -> None:
    with path.open("a", encoding="utf-8") as handle:
        handle.write("\t".join(str(value) for value in values) + "\n")


def benchmark_tool(
    tool: str,
    class_name: str,
    seed: int,
    paths: Dict[str, Path],
    args: argparse.Namespace,
    test_root: Path,
) -> Dict[str, object]:
    plain_cmd, plain_env, plain_cwd = build_plain_command(paths, args.java, class_name)
    baseline_run_counter = {"value": 0}

    def run_plain() -> Dict[str, object]:
        baseline_run_counter["value"] += 1
        prefix = test_root / "baseline" / f"run-{baseline_run_counter['value']:03d}"
        return run_process(plain_cmd, plain_cwd, plain_env, args.timeout, prefix)

    for _ in range(args.warmup):
        run_plain()
    baseline = measure_runs_for_duration(class_name, run_plain, args.measure_window)

    active_recording = test_root / "capture" / "active-report" / "recording"
    saved_root = test_root / "saved-traces"
    last_capture_config = {"path": None}

    def cleanup_capture_artifact() -> None:
        delete_path(test_root / "capture" / "active-report")

    def capture_once(attempt_number: int) -> Dict[str, object]:
        config_path = test_root / "capture" / f"config-{attempt_number:03d}.json"
        report_dir = test_root / "capture" / "active-report"
        write_fray_config(config_path, class_name, paths["sctbench_jar"])
        cmd, env, cwd = build_fray_capture_command(
            paths,
            config_path,
            report_dir,
            args.fray_scheduler,
            args.timeout,
        )
        prefix = test_root / "capture" / f"attempt-{attempt_number:03d}"
        result = run_process(cmd, cwd, env, args.timeout, prefix)
        last_capture_config["path"] = config_path
        return result

    def artifact_exists() -> bool:
        return active_recording.exists()

    def save_artifact(cap_run_index: int) -> Path:
        saved_dir = saved_root / f"capture-{cap_run_index:03d}"
        saved_recording_dir = saved_dir / "recording"
        copy_tree(active_recording, saved_recording_dir)
        if last_capture_config["path"] is not None:
            saved_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy2(last_capture_config["path"], saved_dir / "config.json")
        return saved_dir

    def prepare_replay_artifact(_artifact: Path) -> None:
        return None

    def replay_once(artifact: Path, replay_index: int) -> Dict[str, object]:
        recording_dir = artifact / "recording"
        config_path = artifact / "config.json"
        report_dir = test_root / "replay" / f"{artifact.name}-report-{replay_index:03d}"
        cmd, env, cwd = build_fray_replay_command(
            paths,
            config_path,
            recording_dir,
            report_dir,
            args.timeout,
        )
        prefix = test_root / "replay" / f"{artifact.name}-run-{replay_index:03d}"
        return run_process(cmd, cwd, env, args.timeout, prefix)

    for warmup_index in range(args.warmup):
        capture_once(-(warmup_index + 1))

    buggy = measure_capture_and_replay_for_duration(
        class_name,
        capture_once,
        replay_once,
        artifact_exists,
        save_artifact,
        prepare_replay_artifact,
        cleanup_capture_artifact,
        args.measure_window,
        args.measure,
        True,
    )
    clean = measure_capture_and_replay_for_duration(
        class_name,
        capture_once,
        replay_once,
        artifact_exists,
        save_artifact,
        prepare_replay_artifact,
        cleanup_capture_artifact,
        args.measure_window,
        args.measure,
        False,
    )

    buggy["base_ms"] = baseline["buggy_ms"]
    buggy["base_runs"] = baseline["buggy_runs"]
    buggy["base_attempts"] = baseline["attempts"]
    clean["base_ms"] = baseline["clean_ms"]
    clean["base_runs"] = baseline["clean_runs"]
    clean["base_attempts"] = baseline["attempts"]

    return {
        "tool": tool,
        "class_name": class_name,
        "test_name": slugify_test_name(class_name),
        "buggy": buggy,
        "clean": clean,
    }


def main() -> int:
    layout = discover_layout(Path(__file__).resolve())
    paths = resolve_paths(layout)
    args = parse_args()
    validate_or_fail(paths)
    tests = load_tests(paths["assets_file"], args.tests)
    output_root = Path(args.output_root) if args.output_root else paths["output_root"]
    output_root.mkdir(parents=True, exist_ok=True)
    summary_path = output_root / "summary.tsv"
    write_text(
        summary_path,
        (
            "tool\ttest_class\ttest_name\toutcome\t"
            "baseline_mean_ms\tbaseline_std_ms\tcapture_mean_ms\tcapture_std_ms\treplay_mean_ms\treplay_std_ms\t"
            "baseline_runs\tbaseline_attempts\tcapture_runs\tcapture_attempts\t"
            "capture_base_ratio\treplay_base_ratio\treplay_status\n"
        ),
    )

    print("=== SCTBench Overhead Runner ===")
    print(f"Tests          : {len(tests)}")
    print("Tools          : fray")
    print(f"Warmup rounds  : {args.warmup}")
    print(f"Replay rounds  : {args.measure}")
    print(f"Window         : {args.measure_window}s")
    print(f"Timeout        : {args.timeout}s")
    print(f"Output root    : {output_root}")
    print()

    tool = "fray"
    rows: List[Dict[str, object]] = []
    print(f"=== Tool: {tool} ===")
    for seed, class_name in enumerate(tests, start=1):
            test_root = output_root / tool / slugify_test_name(class_name)
            test_root.mkdir(parents=True, exist_ok=True)
            print(f"[{seed}/{len(tests)}] Running {class_name}")
            row = benchmark_tool(tool, class_name, seed, paths, args, test_root)
            rows.append(row)
            for outcome_name in ("buggy", "clean"):
                outcome = row[outcome_name]
                append_tsv(
                    summary_path,
                    [
                        tool,
                        row["class_name"],
                        row["test_name"],
                        outcome_name,
                        f"{mean(outcome['base_ms']) or 0:.4f}" if outcome["base_ms"] else "N/A",
                        f"{stddev(outcome['base_ms']) or 0:.4f}" if outcome["base_ms"] else "N/A",
                        f"{mean(outcome['cap_ms']) or 0:.4f}" if outcome["cap_ms"] else "N/A",
                        f"{stddev(outcome['cap_ms']) or 0:.4f}" if outcome["cap_ms"] else "N/A",
                        f"{mean(outcome['rep_ms']) or 0:.4f}" if outcome["rep_ms"] else "N/A",
                        f"{stddev(outcome['rep_ms']) or 0:.4f}" if outcome["rep_ms"] else "N/A",
                        outcome["base_runs"],
                        outcome["base_attempts"],
                        outcome["cap_runs"],
                        outcome["cap_attempts"],
                        format_ratio(outcome["cap_ms"], outcome["base_ms"]),
                        format_ratio(outcome["rep_ms"], outcome["base_ms"]),
                        outcome["replay_status"] or "",
                    ],
                )
    print()
    print_section(f"{tool.upper()} Buggy Outcomes", rows, "buggy")
    print_section(f"{tool.upper()} Clean Outcomes", rows, "clean")

    print(f"Summary TSV: {summary_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
