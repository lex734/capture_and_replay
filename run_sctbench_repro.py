#!/usr/bin/env python3

import argparse
import json
import os
import re
import shlex
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

JPF_FAILURE_MARKERS = (
    "UnsupportedOperationException",
    "NoSuchMethodException",
    "FileNotFoundException",
    "Null charset name",
    "NoSuchMethodError",
    "JPF out of memory",
    "java.lang.NullPointerException: Calling 'startsWith(Ljava/lang/String;)Z' on null object",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run SCTBench reproducibility experiments for Fray. "
            "For each selected test, run N captures and M replays per capture, "
            "then measure how often replay matches the capture outcome."
        )
    )
    parser.add_argument(
        "--captures",
        type=int,
        default=10,
        help="Number of capture runs per test and tool (default: 10)",
    )
    parser.add_argument(
        "--replays",
        type=int,
        default=10,
        help="Number of replay runs per capture (default: 10)",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=180,
        help="Per-run timeout in seconds (default: 180)",
    )
    parser.add_argument(
        "--fray-scheduler",
        default="random",
        choices=["random", "pos", "surw", "pct3", "pct15"],
        help="Scheduler to use for Fray captures (default: random)",
    )
    parser.add_argument(
        "--output-root",
        default=None,
        help="Override output root directory",
    )
    parser.add_argument(
        "--java",
        default=os.environ.get("JAVA_CMD", "java"),
        help="Java executable placeholder (unused by Fray runner)",
    )
    parser.add_argument(
        "--tests",
        nargs="*",
        default=None,
        help="Optional list of SCTBench simple names or fully-qualified class names",
    )
    return parser.parse_args()


def read_text(path: Path) -> str:
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def write_text(path: Path, data: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(data, encoding="utf-8")


def slugify_test_name(class_name: str) -> str:
    return class_name.split(".")[-1]


def has_bug_signal(stdout_path: Path, stderr_path: Path) -> bool:
    text = read_text(stdout_path) + "\n" + read_text(stderr_path)
    return BUG_SIGNAL_RE.search(text) is not None


def is_deadlock_benchmark(class_name: str) -> bool:
    return class_name in DEADLOCK_BENCHMARKS


def shell_join(command: Sequence[str]) -> str:
    return shlex.join(list(command))


def inspect_layout_roots(candidates: Sequence[Path]) -> Optional[Dict[str, Path]]:
    for candidate in candidates:
        # Standalone fray-benchmark checkout or container image layout:
        # /fray-benchmark/{scripts,fray_benchmark,bms,tools,helpers}
        if (candidate / "fray_benchmark").exists() and (candidate / "bms").exists():
            return {
                "repo_root": candidate,
                "bench_root": candidate,
                "nested_root": candidate,
                "output_root": candidate / "output" / "capture-replay" / "sctbench-repro",
            }

        # Monorepo layout used in this workspace:
        # <repo>/benchmark/fray-benchmark/{scripts,...}
        nested_root = candidate / "benchmark" / "fray-benchmark"
        if (nested_root / "fray_benchmark").exists() and (candidate / "benchmark" / "bms").exists():
            return {
                "repo_root": candidate,
                "bench_root": candidate / "benchmark",
                "nested_root": nested_root,
                "output_root": candidate / "benchmark" / "output" / "capture-replay" / "sctbench-repro",
            }

    return None


def discover_layout(script_path: Path) -> Dict[str, Path]:
    override = os.environ.get("SCTBENCH_REPRO_ROOT")
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
        "Set SCTBENCH_REPRO_ROOT to the fray-benchmark root if needed."
    )


def resolve_tool_paths(layout: Dict[str, Path]) -> Dict[str, Path]:
    bench_root = layout["bench_root"]
    nested_root = layout["nested_root"]
    output_root = layout["output_root"]

    sctbench_candidates = sorted((bench_root / "bms" / "SCTBench" / "build" / "libs").glob("*.jar"))
    sctbench_jar = next((jar for jar in sctbench_candidates if "fray-benchmark" in jar.name), None)
    if sctbench_jar is None and sctbench_candidates:
        sctbench_jar = sctbench_candidates[0]

    return {
        "repo_root": layout["repo_root"],
        "bench_root": bench_root,
        "nested_root": nested_root,
        "assets_file": nested_root / "fray_benchmark" / "assets" / "sctbench.txt",
        "sctbench_jar": sctbench_jar or bench_root / "bms" / "SCTBench" / "build" / "libs" / "fray-benchmark-1.0-SNAPSHOT.jar",
        "fray_root": nested_root / "tools" / "fray",
        "fray_java": nested_root / "tools" / "fray" / "result" / "java-inst-jdk21" / "bin" / "java",
        "fray_jvmti": nested_root / "tools" / "fray" / "result" / "native-libs" / "libjvmti.so",
        "fray_core_jar": nested_root / "tools" / "fray" / "result" / "libs" / "fray-core-0.5.2-SNAPSHOT.jar",
        "fray_agent_jar": nested_root / "tools" / "fray" / "result" / "libs" / "fray-instrumentation-agent-0.5.2-SNAPSHOT.jar",
        "output_root": output_root,
    }


def validate_or_fail(paths: Dict[str, Path]) -> None:
    required = {
        "shared": [
            ("SCTBench class list", paths["assets_file"]),
            ("SCTBench jar", paths["sctbench_jar"]),
        ],
        "fray": [
            ("Fray instrumented Java", paths["fray_java"]),
            ("Fray JVMTI library", paths["fray_jvmti"]),
            ("Fray core jar", paths["fray_core_jar"]),
            ("Fray instrumentation agent jar", paths["fray_agent_jar"]),
        ],
    }

    missing = []
    for label, path in required["shared"]:
        if not path.exists():
            missing.append(f"{label}: {path}")
    for label, path in required["fray"]:
        if not path.exists():
            missing.append(f"{label}: {path}")

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
    missing = [
        name for name in selected
        if name not in tests and name not in {slugify_test_name(test) for test in tests}
    ]
    if missing:
        print("Unknown SCTBench tests:", file=sys.stderr)
        for name in missing:
            print(f"  - {name}", file=sys.stderr)
        sys.exit(1)
    return filtered


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


def run_command(
    command: Sequence[str],
    cwd: Path,
    env: Dict[str, str],
    stdout_path: Path,
    stderr_path: Path,
    timeout_seconds: Optional[int],
) -> Dict[str, object]:
    stdout_path.parent.mkdir(parents=True, exist_ok=True)
    stderr_path.parent.mkdir(parents=True, exist_ok=True)
    start = time.time()
    timed_out = False
    rc = 0
    with stdout_path.open("w", encoding="utf-8") as stdout, stderr_path.open("w", encoding="utf-8") as stderr:
        try:
            proc = subprocess.run(
                list(command),
                cwd=str(cwd),
                env=env,
                stdout=stdout,
                stderr=stderr,
                timeout=timeout_seconds,
                check=False,
            )
            rc = proc.returncode
        except subprocess.TimeoutExpired:
            timed_out = True
            rc = 124
    return {
        "returncode": rc,
        "timed_out": timed_out,
        "elapsed_seconds": time.time() - start,
    }


def classify_fray(report_log: Path, stdout_path: Path, stderr_path: Path, rc: int, timed_out: bool) -> str:
    log_text = read_text(report_log)
    if "Error found at iter" in log_text or "A bug has been found" in log_text:
        return "error"
    if "Run finished" in log_text:
        return "no_error"
    if has_bug_signal(stdout_path, stderr_path):
        return "error"
    if timed_out:
        return "timeout"
    if rc != 0:
        return "run_failed"
    return "no_error"


def write_metadata(path: Path, data: Dict[str, object]) -> None:
    write_text(path, json.dumps(data, indent=2, sort_keys=True))


def run_fray_capture(
    paths: Dict[str, Path],
    class_name: str,
    run_dir: Path,
    timeout_seconds: int,
    scheduler: str,
) -> Dict[str, object]:
    config_path = run_dir / "config.json"
    report_dir = run_dir / "report"
    write_fray_config(config_path, class_name, paths["sctbench_jar"])
    command, env, cwd = build_fray_capture_command(
        paths,
        config_path,
        report_dir,
        scheduler,
        timeout_seconds,
    )
    write_text(run_dir / "command.txt", shell_join(command))
    result = run_command(
        command,
        cwd,
        env,
        run_dir / "stdout.txt",
        run_dir / "stderr.txt",
        timeout_seconds,
    )
    outcome = classify_fray(
        report_dir / "fray.log",
        run_dir / "stdout.txt",
        run_dir / "stderr.txt",
        int(result["returncode"]),
        bool(result["timed_out"]),
    )
    data = {
        **result,
        "outcome": outcome,
        "recording_present": (report_dir / "recording").exists(),
    }
    write_metadata(run_dir / "result.json", data)
    return data


def run_fray_replay(
    paths: Dict[str, Path],
    class_name: str,
    capture_dir: Path,
    run_dir: Path,
    timeout_seconds: int,
) -> Dict[str, object]:
    config_path = capture_dir / "config.json"
    recording_dir = capture_dir / "report" / "recording"
    report_dir = run_dir / "report"
    command, env, cwd = build_fray_replay_command(
        paths,
        config_path,
        recording_dir,
        report_dir,
        timeout_seconds,
    )
    write_text(run_dir / "command.txt", shell_join(command))
    result = run_command(
        command,
        cwd,
        env,
        run_dir / "stdout.txt",
        run_dir / "stderr.txt",
        timeout_seconds,
    )
    outcome = classify_fray(
        report_dir / "fray.log",
        run_dir / "stdout.txt",
        run_dir / "stderr.txt",
        int(result["returncode"]),
        bool(result["timed_out"]),
    )
    data = {
        **result,
        "outcome": outcome,
    }
    write_metadata(run_dir / "result.json", data)
    return data


def append_tsv_row(path: Path, values: Sequence[object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write("\t".join(str(value) for value in values) + "\n")


def format_rate(numerator: int, denominator: int) -> str:
    if denominator == 0:
        return "0.0000"
    return f"{numerator / denominator:.4f}"


def print_console_summary(rows: Sequence[Dict[str, object]]) -> None:
    print("\n=== Console Summary ===")
    header = (
        f"{'Test':<24}  {'Buggy Caps':>10}  {'Clean Caps':>10}  {'Replayable':>10}  "
        f"{'Replay Reqs':>11}  {'Reproduced':>18}  {'Buggy Repro':>18}  {'Clean No Art':>12}"
    )
    print(header)
    print("-" * len(header))
    for row in rows:
        print(
            f"{str(row['test_name']):<24}  "
            f"{int(row['buggy_captures']):>10}  "
            f"{int(row['non_buggy_captures']):>10}  "
            f"{int(row['replayable_captures']):>10}  "
            f"{int(row['replayable_replays']):>11}  "
            f"{str(row['reproduced_display']):>18}  "
            f"{str(row['buggy_reproduced_display']):>18}  "
            f"{int(row['non_replayable_valid_captures']):>12}"
        )


def is_buggy_outcome(outcome: str) -> bool:
    return outcome == "error"


def is_valid_comparable_outcome(outcome: str) -> bool:
    return outcome in {"error", "no_error"}


def main() -> int:
    layout = discover_layout(Path(__file__).resolve())
    paths = resolve_tool_paths(layout)
    args = parse_args()
    validate_or_fail(paths)
    tests = load_tests(paths["assets_file"], args.tests)
    output_root = Path(args.output_root) if args.output_root else paths["output_root"]
    output_root.mkdir(parents=True, exist_ok=True)

    summary_path = output_root / "summary.tsv"
    write_text(
        summary_path,
        "tool\ttest_class\ttest_name\tcapture_index\tcapture_outcome\treplay_index\treplay_outcome\treproduced\n",
    )

    print("=== SCTBench Reproducibility Runner ===")
    print(f"Tests          : {len(tests)}")
    print("Tools          : fray")
    print(f"Captures       : {args.captures}")
    print(f"Replays/capture: {args.replays}")
    print(f"Timeout        : {args.timeout}s")
    print(f"Output root    : {output_root}")

    tool = "fray"
    console_rows: List[Dict[str, object]] = []
    print(f"\n=== Tool: {tool} ===")
    for class_index, class_name in enumerate(tests):
            test_name = slugify_test_name(class_name)
            test_root = output_root / tool / test_name
            test_root.mkdir(parents=True, exist_ok=True)
            reproduced_total = 0
            replay_total = 0
            valid_capture_count = 0
            invalid_capture_count = 0
            replayable_capture_count = 0
            non_replayable_valid_capture_count = 0
            skipped_replays_invalid_capture = 0
            buggy_capture_count = 0
            buggy_replay_total = 0
            buggy_reproduced_total = 0
            non_buggy_capture_count = 0
            non_buggy_replay_total = 0
            non_buggy_reproduced_total = 0
            capture_outcome_counts = {
                "error": 0,
                "no_error": 0,
                "timeout": 0,
                "run_failed": 0,
                "not_replayable": 0,
                "skipped_invalid_capture": 0,
            }
            replay_outcome_counts = {
                "error": 0,
                "no_error": 0,
                "timeout": 0,
                "run_failed": 0,
                "not_replayable": 0,
                "skipped_invalid_capture": 0,
            }

            print(f"  -> {test_name}")
            for capture_index in range(1, args.captures + 1):
                capture_dir = test_root / f"capture-{capture_index:02d}"

                capture_result = run_fray_capture(paths, class_name, capture_dir, args.timeout, args.fray_scheduler)
                replayable = bool(capture_result["recording_present"])

                capture_outcome = str(capture_result["outcome"])
                capture_outcome_counts.setdefault(capture_outcome, 0)
                capture_outcome_counts[capture_outcome] += 1
                if is_valid_comparable_outcome(capture_outcome):
                    valid_capture_count += 1
                else:
                    invalid_capture_count += 1

                if is_buggy_outcome(capture_outcome):
                    buggy_capture_count += 1
                elif capture_outcome == "no_error":
                    non_buggy_capture_count += 1
                else:
                    skipped_replays_invalid_capture += args.replays
                    replay_outcome_counts["skipped_invalid_capture"] += args.replays
                    continue

                if replayable:
                    replayable_capture_count += 1
                else:
                    non_replayable_valid_capture_count += 1

                for replay_index in range(1, args.replays + 1):
                    replay_dir = capture_dir / f"replay-{replay_index:02d}"

                    if not replayable:
                        replay_result = {"outcome": "not_replayable"}
                    else:
                        replay_result = run_fray_replay(paths, class_name, capture_dir, replay_dir, args.timeout)

                    reproduced = int(replay_result["outcome"] == capture_result["outcome"])
                    replay_outcome = str(replay_result["outcome"])
                    replay_outcome_counts.setdefault(replay_outcome, 0)
                    replay_outcome_counts[replay_outcome] += 1
                    if replayable:
                        reproduced_total += reproduced
                        replay_total += 1
                        if is_buggy_outcome(capture_outcome):
                            buggy_replay_total += 1
                            buggy_reproduced_total += reproduced
                        else:
                            non_buggy_replay_total += 1
                            non_buggy_reproduced_total += reproduced
                    append_tsv_row(
                        summary_path,
                        [
                            tool,
                            class_name,
                            test_name,
                            capture_index,
                            capture_outcome,
                            replay_index,
                            replay_outcome,
                            reproduced,
                        ],
                    )

            non_replayable_slots = non_replayable_valid_capture_count * args.replays
            print(
                f"     replayable captures {replayable_capture_count}/{valid_capture_count} | "
                f"replayable replays {replay_total}"
            )
            reproduced_display = (
                f"{reproduced_total}/{replay_total} ({format_rate(reproduced_total, replay_total)})"
                if replay_total
                else "n/a"
            )
            print(f"     reproduced {reproduced_display} over replayable captures")
            buggy_reproduced_display = (
                f"{buggy_reproduced_total}/{buggy_replay_total} ({format_rate(buggy_reproduced_total, buggy_replay_total)})"
                if buggy_replay_total
                else "n/a"
            )
            if buggy_capture_count:
                print(f"     buggy reproduction {buggy_reproduced_display}")
            if non_replayable_valid_capture_count:
                print(
                    f"     clean captures without replay artifact "
                    f"{non_replayable_valid_capture_count}/{valid_capture_count} "
                    f"({non_replayable_slots} summary rows marked not_replayable)"
                )
            console_rows.append(
                {
                    "test_name": test_name,
                    "buggy_captures": buggy_capture_count,
                    "non_buggy_captures": non_buggy_capture_count,
                    "replayable_captures": replayable_capture_count,
                    "replayable_replays": replay_total,
                    "reproduced_display": reproduced_display,
                    "buggy_reproduced_display": buggy_reproduced_display,
                    "non_replayable_valid_captures": non_replayable_valid_capture_count,
                }
            )

    print_console_summary(console_rows)
    print("\nSummary files:")
    print(f"  - {summary_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
