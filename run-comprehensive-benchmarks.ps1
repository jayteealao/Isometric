# Comprehensive Benchmark Runner
# Test Matrix: 3 sizes × 2 scenarios × 3 optimizations = 18 configurations

$sizes = @(100, 500, 1000)
$scenarios = @("STATIC", "FULL_MUTATION")
$optimizations = @(
    @{Name="baseline"; PreparedCache=$false; DrawCache=$false},
    @{Name="preparedcache"; PreparedCache=$true; DrawCache=$false},
    @{Name="drawcache"; PreparedCache=$false; DrawCache=$true}
)

$totalConfigs = $sizes.Count * $scenarios.Count * $optimizations.Count
$currentConfig = 0

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Comprehensive Benchmark Suite" -ForegroundColor Cyan
Write-Host "Total Configurations: $totalConfigs" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan

# Clear logcat
adb logcat -c

foreach ($size in $sizes) {
    foreach ($scenario in $scenarios) {
        foreach ($opt in $optimizations) {
            $currentConfig++
            $configName = "$($opt.Name)_$($scenario.ToLower())_$($size)_noInteraction"

            Write-Host "[$currentConfig/$totalConfigs] Running: $configName" -ForegroundColor Yellow
            Write-Host "  Size: $size | Scenario: $scenario | Optimization: $($opt.Name)" -ForegroundColor Gray

            # Build intent extras
            $intent = "am start -n io.fabianterhorst.isometric.benchmark/.BenchmarkActivity " +
                      "--es name `"$configName`" " +
                      "--ei sceneSize $size " +
                      "--es scenario `"$scenario`" " +
                      "--es interactionPattern `"NONE`" " +
                      "--ez enablePreparedSceneCache $($opt.PreparedCache.ToString().ToLower()) " +
                      "--ez enableDrawWithCache $($opt.DrawCache.ToString().ToLower()) " +
                      "--ei numberOfRuns 3"

            # Launch benchmark
            adb shell $intent | Out-Null

            # Wait for benchmark to complete (watch for "Results:" in logcat)
            $timeout = 300  # 5 minutes max
            $elapsed = 0
            $completed = $false

            while ($elapsed -lt $timeout -and -not $completed) {
                Start-Sleep -Seconds 2
                $elapsed += 2

                # Check if results logged
                $logs = adb logcat -d -s BenchmarkActivity:I | Select-String "Results:"
                if ($logs) {
                    $completed = $true
                    Write-Host "  [OK] Completed in ${elapsed}s" -ForegroundColor Green

                    # Extract and display results
                    $result = $logs[-1].ToString()
                    if ($result -match "avgFrameMs,") {
                        # Skip header
                    } else {
                        $fields = $result.Split(",")
                        if ($fields.Count -ge 4) {
                            $avgMs = [math]::Round([double]$fields[4], 2)
                            $fps = [math]::Round(1000.0 / $avgMs, 1)
                            Write-Host "  Avg: ${avgMs}ms (${fps} FPS)" -ForegroundColor Cyan
                        }
                    }
                }

                # Show progress
                if ($elapsed % 10 -eq 0 -and -not $completed) {
                    Write-Host "  ... waiting ${elapsed}s" -ForegroundColor DarkGray
                }
            }

            if (-not $completed) {
                Write-Host "  [TIMEOUT] after ${timeout}s" -ForegroundColor Red
            }

            # Clear logcat for next run
            adb logcat -c

            # Brief pause between configs
            if ($currentConfig -lt $totalConfigs) {
                Start-Sleep -Seconds 2
            }

            Write-Host ""
        }
    }
}

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "Benchmark Suite Complete!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan

# Pull results file
Write-Host "`nPulling results file from device..." -ForegroundColor Yellow
adb pull /sdcard/Download/benchmark_results.csv ./docs/benchmark_results_comprehensive.csv

if (Test-Path "./docs/benchmark_results_comprehensive.csv") {
    Write-Host "[OK] Results saved to: docs/benchmark_results_comprehensive.csv" -ForegroundColor Green

    # Display summary
    Write-Host "`nResults Summary:" -ForegroundColor Cyan
    $csv = Import-Csv "./docs/benchmark_results_comprehensive.csv"
    $csv | Format-Table -Property name, sceneSize, scenario, avgFrameMs, drawCalls -AutoSize
} else {
    Write-Host "[FAIL] Failed to pull results file" -ForegroundColor Red
}
