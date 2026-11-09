"""Summarises a JFR recording printed as JSON (jfr print --json): where the CPU samples landed.

Usage: python jfr_top.py samples.json
Prints the busiest threads, the top frames, and the first frame of Convx's own code (or Compose's,
or Skia's) in each sample, which is usually the answer to "what is drawing while nothing happens".
"""
import collections
import json
import sys

OWN = ("com.convx.", "com.kyant.", "com.mocharealm.")
UI = ("androidx.compose.", "org.jetbrains.skiko.", "org.jetbrains.skia.", "kotlinx.coroutines.")


def frames(event):
    trace = event["values"].get("stackTrace") or {}
    for frame in trace.get("frames") or []:
        method = frame.get("method") or {}
        kind = (method.get("type") or {}).get("name", "?")
        yield f"{kind}.{method.get('name', '?')}"


def thread_of(event):
    values = event["values"]
    thread = values.get("sampledThread") or values.get("eventThread") or {}
    return thread.get("javaName") or thread.get("osName") or "?"


def first(stack, prefixes):
    return next((f for f in stack if f.startswith(prefixes)), None)


def main(path):
    with open(path, encoding="utf-8-sig") as file:
        events = json.load(file)["recording"]["events"]
    samples = [e for e in events if e["type"] in ("jdk.ExecutionSample", "jdk.NativeMethodSample")]
    running = [e for e in samples if e["type"] == "jdk.ExecutionSample"]
    print(f"{len(running)} running samples, {len(samples) - len(running)} in native code")

    loads = collections.defaultdict(list)
    for e in events:
        if e["type"] == "jdk.ThreadCPULoad":
            v = e["values"]
            loads[thread_of(e)].append(float(v.get("user", 0)) + float(v.get("system", 0)))
    if loads:
        print("\nThreads by CPU (share of one core, average):")
        for name, values in sorted(loads.items(), key=lambda kv: -sum(kv[1]) / len(kv[1]))[:15]:
            print(f"  {100 * sum(values) / len(values):6.2f}%  {name}")

    def table(title, counter, limit=25):
        print(f"\n{title}:")
        for key, count in counter.most_common(limit):
            print(f"  {count:5d}  {key}")

    table("Running samples by thread", collections.Counter(thread_of(e) for e in running), 15)
    stacks = [list(frames(e)) for e in running]
    table("Top frames", collections.Counter(s[0] for s in stacks if s))
    table("First Convx frame", collections.Counter(first(s, OWN) for s in stacks if first(s, OWN)))
    table("First Compose/Skia frame", collections.Counter(first(s, UI) for s in stacks if first(s, UI)))
    native = [list(frames(e)) for e in samples if e["type"] == "jdk.NativeMethodSample"]
    table("Native samples, top frame (threads waiting in native code show here too)", collections.Counter(s[0] for s in native if s), 15)


if __name__ == "__main__":
    main(sys.argv[1])
