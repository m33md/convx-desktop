# Opens a Convx build the way it opens after a restart (a song in the queue, paused) and measures
# what it costs while it sits there: CPU over a stretch of idle time, memory as Task Manager counts
# it, the frames Convx says it drew, the JVM's own memory breakdown, and a CPU profile of where the
# time went. Then it scrolls the page for a while, as a person looking around does, and measures
# what that costs and how much of it is handed back afterwards.
# For CI runners: run it on the new build and on the last release to compare the two.
# Runs in Windows PowerShell 5.1 (System.Drawing). Kept ASCII-only for that reason.
param(
    [Parameter(Mandatory)] [string] $App,
    [Parameter(Mandatory)] [string] $Name,
    [Parameter(Mandatory)] [string] $OutDir,
    # A JDK whose jcmd and jfr can attach to the app's runtime; skipped if missing.
    [string] $Jdk = '',
    [int] $Settle = 50,
    [int] $Seconds = 30,
    # How long the page is scrolled for, and how long Convx is left alone after it; 0 skips the scroll.
    [int] $Scroll = 15,
    [int] $Rest = 75
)
$ErrorActionPreference = 'Continue'
New-Item -ItemType Directory -Force $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path
$exe = Join-Path (Resolve-Path $App).Path 'Convx.exe'
$report = Join-Path $OutDir "$Name.txt"
function Say([string] $line) { Write-Host $line; Add-Content -Path $report -Value $line }
Set-Content -Path $report -Value "Idle check: $Name ($exe)"

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
function Shot([string] $file) {
    try {
        $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
        $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size)
        $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
        $g.Dispose(); $bmp.Dispose()
    } catch { Say "Screenshot failed: $_" }
}

Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class ConvxInput {
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, int data, UIntPtr extra);
    public static void Wheel(int delta) { mouse_event(0x0800, 0, 0, delta, UIntPtr.Zero); }
}
"@

# Memory as Task Manager counts it, and the graphics memory behind the drawing surfaces: on a PC
# without its own graphics card (and on this runner) the shared part is ordinary RAM.
function Memory($p, [string] $label) {
    $p.Refresh()
    $privateWs = $null
    try {
        # Two Convx.exe run, so the counter's instance is found by its process id.
        $samples = (Get-Counter '\Process(Convx*)\ID Process', '\Process(Convx*)\Working Set - Private' -ErrorAction Stop).CounterSamples
        $instance = ($samples | Where-Object { $_.Path -like '*\id process' -and $_.CookedValue -eq $p.Id } | Select-Object -First 1).InstanceName
        $privateWs = ($samples | Where-Object { $_.Path -like '*\working set - private' -and $_.InstanceName -eq $instance } | Select-Object -First 1).CookedValue
    } catch { }
    $gpu = @()
    foreach ($kind in 'Dedicated', 'Shared') {
        try {
            $sum = (Get-Counter "\GPU Process Memory(pid_$($p.Id)_*)\$kind Usage" -ErrorAction Stop).CounterSamples | Measure-Object CookedValue -Sum
            $gpu += ('{0} {1:N0} MB' -f $kind.ToLower(), ($sum.Sum / 1MB))
        } catch { $gpu += "$($kind.ToLower()) ?" }
    }
    Say ("{0}: working set {1:N0} MB, private working set {2} MB (Task Manager's column), private bytes {3:N0} MB; graphics memory {4}" -f `
        $label, ($p.WorkingSet64 / 1MB), $(if ($privateWs) { '{0:N0}' -f ($privateWs / 1MB) } else { '?' }), ($p.PrivateMemorySize64 / 1MB), ($gpu -join ', '))
}

# A profile of its own: settings with a paused song, as Convx keeps them between runs.
$root = Join-Path $env:RUNNER_TEMP "idle-$Name"
Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
$roaming = Join-Path $root 'Roaming'
$local = Join-Path $root 'Local'
New-Item -ItemType Directory -Force (Join-Path $roaming 'Convx'), (Join-Path $local 'Convx') | Out-Null
$settings = '{"lastQueue":[{"id":"4NRXx6U8ABQ","title":"Blinding Lights","artists":[{"name":"The Weeknd"}],"duration":200,' +
    '"thumbnail":"https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg"}],"lastIndex":0,"lastPositionMs":62000,' +
    '"checkForUpdates":false,"windowMaximized":true}'
[System.IO.File]::WriteAllText((Join-Path $roaming 'Convx\settings.json'), $settings)

$saved = @{ APPDATA = $env:APPDATA; LOCALAPPDATA = $env:LOCALAPPDATA }
$env:APPDATA = $roaming
$env:LOCALAPPDATA = $local
$env:CONVX_PERF_EVERY = "$Seconds"
$env:JAVA_TOOL_OPTIONS = '-XX:NativeMemoryTracking=summary'
$process = Start-Process -FilePath $exe -PassThru
$env:APPDATA = $saved.APPDATA
$env:LOCALAPPDATA = $saved.LOCALAPPDATA
Remove-Item Env:JAVA_TOOL_OPTIONS
Remove-Item Env:CONVX_PERF_EVERY

Start-Sleep -Seconds $Settle
if ($process.HasExited) {
    Say "Convx exited during start-up (code $($process.ExitCode))"
} else {
    Shot (Join-Path $OutDir "$Name.png")
    # Convx.exe starts Java in a second Convx.exe and waits for it: that one is the app.
    $p = Get-Process -Id $process.Id
    $kids = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($process.Id)" -ErrorAction SilentlyContinue |
        ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue } | Sort-Object WorkingSet64 -Descending)
    if ($kids.Count -gt 0) { $p = $kids[0] }
    Say ("Measured process {0} ({1}), started by {2}" -f $p.Id, $p.ProcessName, $process.Id)
    $cpu0 = $p.TotalProcessorTime.TotalMilliseconds
    $t0 = Get-Date
    Start-Sleep -Seconds $Seconds
    $p.Refresh()
    $cpu1 = $p.TotalProcessorTime.TotalMilliseconds
    $elapsed = ((Get-Date) - $t0).TotalMilliseconds
    $cores = [Environment]::ProcessorCount
    $oneCore = 100.0 * ($cpu1 - $cpu0) / $elapsed
    Say ("CPU while idle: {0:N1}% of all {1} threads ({2:N1}% of one)" -f ($oneCore / $cores), $cores, $oneCore)
    Memory $p 'Memory'
    Say ("Threads: {0}" -f $p.Threads.Count)

    $jcmd = Join-Path $Jdk 'bin\jcmd.exe'
    $jfr = Join-Path $Jdk 'bin\jfr.exe'
    if ($Jdk -and (Test-Path $jcmd)) {
        & $jcmd $p.Id VM.native_memory summary scale=MB 2>&1 | Out-File -Encoding utf8 (Join-Path $OutDir "$Name-native-memory.txt")
        & $jcmd $p.Id GC.heap_info 2>&1 | Out-File -Encoding utf8 (Join-Path $OutDir "$Name-heap.txt")
        $recording = Join-Path $OutDir "$Name.jfr"
        & $jcmd $p.Id JFR.start name=idle settings=profile duration=25s "filename=$recording" 2>&1 | Out-Null
        Start-Sleep -Seconds 30
        if ((Test-Path $recording) -and (Test-Path $jfr)) {
            $json = Join-Path $OutDir "$Name-samples.json"
            & $jfr print --json --stack-depth 48 --events 'jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ThreadCPULoad' $recording | Out-File -Encoding utf8 $json
            python (Join-Path $PSScriptRoot 'jfr_top.py') $json 2>&1 | Out-File -Encoding utf8 (Join-Path $OutDir "$Name-profile.txt")
            Remove-Item $json, $recording -ErrorAction SilentlyContinue
        } else {
            Say 'No CPU profile was recorded'
        }
    } else {
        Say 'No JDK given: skipped the memory breakdown and the CPU profile'
    }

    if ($Scroll -gt 0) {
        $profiled = $Jdk -and (Test-Path $jcmd)
        $recording = Join-Path $OutDir "$Name-scroll.jfr"
        if ($profiled) { & $jcmd $p.Id JFR.start name=scroll settings=profile "duration=$($Scroll)s" "filename=$recording" 2>&1 | Out-Null }
        $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
        [ConvxInput]::SetCursorPos([int]($b.Width * 0.55), [int]($b.Height * 0.5)) | Out-Null
        $p.Refresh()
        $cpu0 = $p.TotalProcessorTime.TotalMilliseconds
        $t0 = Get-Date
        $until = $t0.AddSeconds($Scroll)
        $notch = 0
        while ((Get-Date) -lt $until) {
            # Down the page, with a short way back up now and then.
            [ConvxInput]::Wheel($(if (($notch % 60) -ge 50) { 120 } else { -120 }))
            $notch++
            Start-Sleep -Milliseconds 60
        }
        $p.Refresh()
        $oneCore = 100.0 * ($p.TotalProcessorTime.TotalMilliseconds - $cpu0) / ((Get-Date) - $t0).TotalMilliseconds
        Say ("CPU while scrolling for {0} s: {1:N1}% of all {2} threads ({3:N1}% of one)" -f $Scroll, ($oneCore / $cores), $cores, $oneCore)
        Memory $p 'Memory right after scrolling'
        Shot (Join-Path $OutDir "$Name-scrolled.png")
        if ($profiled) {
            & $jcmd $p.Id GC.heap_info 2>&1 | Out-File -Encoding utf8 (Join-Path $OutDir "$Name-scrolled-heap.txt")
            Start-Sleep -Seconds 5
            if ((Test-Path $recording) -and (Test-Path $jfr)) {
                $json = Join-Path $OutDir "$Name-scroll-samples.json"
                & $jfr print --json --stack-depth 48 --events 'jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ThreadCPULoad' $recording | Out-File -Encoding utf8 $json
                python (Join-Path $PSScriptRoot 'jfr_top.py') $json 2>&1 | Out-File -Encoding utf8 (Join-Path $OutDir "$Name-scroll-profile.txt")
                Remove-Item $json, $recording -ErrorAction SilentlyContinue
            }
        }
        Start-Sleep -Seconds $Rest
        Memory $p "Memory $Rest s after scrolling"
        if ($profiled) {
            & $jcmd $p.Id VM.native_memory summary scale=MB 2>&1 | Out-File -Encoding utf8 (Join-Path $OutDir "$Name-rested-native-memory.txt")
            & $jcmd $p.Id GC.heap_info 2>&1 | Out-File -Encoding utf8 (Join-Path $OutDir "$Name-rested-heap.txt")
        }
    }
    Stop-Process -Id $p.Id, $process.Id -Force -ErrorAction SilentlyContinue
}

# Convx's own log: its Perf lines say how many frames it drew while idle.
Get-ChildItem (Join-Path $local 'Convx\logs') -Filter *.log -ErrorAction SilentlyContinue | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $OutDir "$Name-$($_.Name)")
    Select-String -Path $_.FullName -Pattern 'Perf|I/Memory' | ForEach-Object { Say $_.Line }
}
