#!/usr/bin/env python3
"""
Run all 18 benchmark configurations sequentially
"""
import subprocess
import time

# Define all 18 configurations
configs = [
    # 100 objects
    {"name": "baseline_static_100", "size": 100, "scenario": "STATIC", "preparedCache": "false", "drawCache": "false"},
    {"name": "preparedcache_static_100", "size": 100, "scenario": "STATIC", "preparedCache": "true", "drawCache": "false"},
    {"name": "drawcache_static_100", "size": 100, "scenario": "STATIC", "preparedCache": "false", "drawCache": "true"},
    {"name": "baseline_mutation_100", "size": 100, "scenario": "FULL_MUTATION", "preparedCache": "false", "drawCache": "false"},
    {"name": "preparedcache_mutation_100", "size": 100, "scenario": "FULL_MUTATION", "preparedCache": "true", "drawCache": "false"},
    {"name": "drawcache_mutation_100", "size": 100, "scenario": "FULL_MUTATION", "preparedCache": "false", "drawCache": "true"},

    # 500 objects
    {"name": "baseline_static_500", "size": 500, "scenario": "STATIC", "preparedCache": "false", "drawCache": "false"},
    {"name": "preparedcache_static_500", "size": 500, "scenario": "STATIC", "preparedCache": "true", "drawCache": "false"},
    {"name": "drawcache_static_500", "size": 500, "scenario": "STATIC", "preparedCache": "false", "drawCache": "true"},
    {"name": "baseline_mutation_500", "size": 500, "scenario": "FULL_MUTATION", "preparedCache": "false", "drawCache": "false"},
    {"name": "preparedcache_mutation_500", "size": 500, "scenario": "FULL_MUTATION", "preparedCache": "true", "drawCache": "false"},
    {"name": "drawcache_mutation_500", "size": 500, "scenario": "FULL_MUTATION", "preparedCache": "false", "drawCache": "true"},

    # 1000 objects
    {"name": "baseline_static_1000", "size": 1000, "scenario": "STATIC", "preparedCache": "false", "drawCache": "false"},
    {"name": "preparedcache_static_1000", "size": 1000, "scenario": "STATIC", "preparedCache": "true", "drawCache": "false"},
    {"name": "drawcache_static_1000", "size": 1000, "scenario": "STATIC", "preparedCache": "false", "drawCache": "true"},
    {"name": "baseline_mutation_1000", "size": 1000, "scenario": "FULL_MUTATION", "preparedCache": "false", "drawCache": "false"},
    {"name": "preparedcache_mutation_1000", "size": 1000, "scenario": "FULL_MUTATION", "preparedCache": "true", "drawCache": "false"},
    {"name": "drawcache_mutation_1000", "size": 1000, "scenario": "FULL_MUTATION", "preparedCache": "false", "drawCache": "true"},
]

print("="*80)
print("Running All 18 Benchmark Configurations")
print("="*80)
print()

total_configs = len(configs)
for i, config in enumerate(configs, 1):
    print(f"[{i}/{total_configs}] Running: {config['name']}")
    print(f"  Size: {config['size']} | Scenario: {config['scenario']} | PreparedCache: {config['preparedCache']} | DrawCache: {config['drawCache']}")

    # Build ADB command
    cmd = [
        "adb", "shell", "am", "start", "-n",
        "io.fabianterhorst.isometric.benchmark/.BenchmarkActivity",
        "--ei", "sceneSize", str(config['size']),
        "--es", "scenario", config['scenario'],
        "--ez", "enablePreparedSceneCache", config['preparedCache'],
        "--ez", "enableDrawWithCache", config['drawCache'],
        "--es", "interaction", "NONE",
        "--ei", "runs", "1"
    ]

    # Stop any running instance first
    subprocess.run(["adb", "shell", "am", "force-stop", "io.fabianterhorst.isometric.benchmark"], check=False)
    time.sleep(3)

    # Clear memory - kill the process completely
    subprocess.run(["adb", "shell", "am", "kill", "io.fabianterhorst.isometric.benchmark"], check=False)
    time.sleep(2)

    # Run benchmark
    subprocess.run(cmd, check=True)

    # Wait for benchmark to complete (approx 25 seconds for 1 run)
    print("  Waiting for completion...")
    for wait_sec in range(5, 31, 5):
        time.sleep(5)
        print(f"  ... {wait_sec}s elapsed")

    # Wait extra time for CSV to be written
    print("  Waiting for results to be saved...")
    time.sleep(10)

    # Stop the activity after completion
    subprocess.run(["adb", "shell", "am", "force-stop", "io.fabianterhorst.isometric.benchmark"], check=False)
    time.sleep(2)

    # Kill process to free memory
    subprocess.run(["adb", "shell", "am", "kill", "io.fabianterhorst.isometric.benchmark"], check=False)
    time.sleep(1)

    print(f"  [OK] Completed\n")

print("="*80)
print("All Benchmarks Complete!")
print("="*80)
print("\nPulling results...")
subprocess.run(["adb", "shell", "cat", "/storage/emulated/0/Android/data/io.fabianterhorst.isometric.benchmark/files/benchmark_results.csv"], check=True)
