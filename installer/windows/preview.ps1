# Runs Convx's setup .exe the way a person would and takes a screenshot of each page, so the setup
# window can be checked without a Windows PC. It also checks that installing, updating and
# uninstalling really put Convx in place and take it away. For CI runners: it installs Convx.
# Runs in Windows PowerShell 5.1 (System.Drawing). Kept ASCII-only for that reason.
param(
    [Parameter(Mandatory)] [string] $Setup,
    [Parameter(Mandatory)] [string] $Msi,
    [Parameter(Mandatory)] [string] $OutDir
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $OutDir | Out-Null
$Setup = (Resolve-Path $Setup).Path
$Msi = (Resolve-Path $Msi).Path
$OutDir = (Resolve-Path $OutDir).Path

Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Text;

public static class SetupUi {
    delegate bool EnumProc(IntPtr h, IntPtr p);
    [DllImport("user32")] static extern bool EnumWindows(EnumProc proc, IntPtr p);
    [DllImport("user32")] static extern bool EnumChildWindows(IntPtr parent, EnumProc proc, IntPtr p);
    [DllImport("user32", CharSet = CharSet.Unicode)] static extern int GetWindowTextW(IntPtr h, StringBuilder sb, int n);
    [DllImport("user32")] static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32")] static extern bool IsWindowEnabled(IntPtr h);
    [DllImport("user32")] static extern int GetDlgCtrlID(IntPtr h);
    [DllImport("user32")] static extern bool PostMessageW(IntPtr h, uint msg, IntPtr w, IntPtr l);
    [StructLayout(LayoutKind.Sequential)] struct RECT { public int L, T, R, B; }
    [DllImport("user32")] static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32")] static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
    [DllImport("user32", CharSet = CharSet.Unicode)] static extern int GetClassNameW(IntPtr h, StringBuilder sb, int n);

    static string Text(IntPtr h) {
        var sb = new StringBuilder(1024);
        GetWindowTextW(h, sb, sb.Capacity);
        return sb.ToString();
    }

    public static IntPtr Find(string title) {
        IntPtr found = IntPtr.Zero;
        EnumWindows((h, p) => {
            if (IsWindowVisible(h) && Text(h) == title) { found = h; return false; }
            return true;
        }, IntPtr.Zero);
        return found;
    }

    public static string[] Texts(IntPtr parent) {
        var all = new List<string>();
        EnumChildWindows(parent, (h, p) => {
            if (IsWindowVisible(h)) { var t = Text(h); if (t.Length > 0) all.Add(t); }
            return true;
        }, IntPtr.Zero);
        return all.ToArray();
    }

    // Clicks the visible, enabled button whose text is exactly this, as Windows would report it.
    public static bool Click(IntPtr parent, string text) {
        IntPtr button = IntPtr.Zero;
        EnumChildWindows(parent, (h, p) => {
            if (IsWindowVisible(h) && IsWindowEnabled(h) && Text(h) == text) { button = h; return false; }
            return true;
        }, IntPtr.Zero);
        if (button == IntPtr.Zero) return false;
        PostMessageW(parent, 0x0111 /* WM_COMMAND, BN_CLICKED */, (IntPtr)(GetDlgCtrlID(button) & 0xFFFF), button);
        return true;
    }

    // A message box with this title (Windows' dialog class), such as the error the setup shows
    // before its Failure page.
    public static IntPtr FindBox(string title) {
        IntPtr found = IntPtr.Zero;
        EnumWindows((h, p) => {
            if (!IsWindowVisible(h) || Text(h) != title) return true;
            var sb = new StringBuilder(64);
            GetClassNameW(h, sb, sb.Capacity);
            if (sb.ToString() == "#32770") { found = h; return false; }
            return true;
        }, IntPtr.Zero);
        return found;
    }

    // Presses a message box's button by its id (1 OK, 2 Cancel, 3 Abort).
    public static void Answer(IntPtr box, int id) { PostMessageW(box, 0x0111, (IntPtr)id, IntPtr.Zero); }

    public static void Close(IntPtr h) { PostMessageW(h, 0x0010 /* WM_CLOSE */, IntPtr.Zero, IntPtr.Zero); }

    public static void Shot(IntPtr h, string path) {
        RECT r;
        GetWindowRect(h, out r);
        using (var bmp = new Bitmap(r.R - r.L, r.B - r.T, PixelFormat.Format32bppArgb)) {
            using (var g = Graphics.FromImage(bmp)) {
                IntPtr hdc = g.GetHdc();
                PrintWindow(h, hdc, 2 /* PW_RENDERFULLCONTENT */);
                g.ReleaseHdc(hdc);
            }
            bmp.Save(path, ImageFormat.Png);
        }
    }
}
'@

$exe = Join-Path $env:LOCALAPPDATA 'Convx\Convx.exe'
$script:failures = @()

function Start-Setup([string] $log, [string[]] $more = @()) {
    $arguments = @('-log', (Join-Path $OutDir $log)) + $more
    $process = Start-Process -FilePath $Setup -ArgumentList $arguments -PassThru
    for ($i = 0; $i -lt 120; $i++) {
        $window = [SetupUi]::Find('Convx Setup')
        if ($window -ne [IntPtr]::Zero) { return @{ Process = $process; Window = $window } }
        Start-Sleep -Milliseconds 250
    }
    throw 'The setup window never opened'
}

function Wait-For($setup, [string] $pattern, [int] $seconds = 60) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $texts = [SetupUi]::Texts($setup.Window)
        if ($texts | Where-Object { $_ -like $pattern }) { return }
        Start-Sleep -Milliseconds 300
    }
    throw "The setup never showed '$pattern'. It shows: $(([SetupUi]::Texts($setup.Window)) -join ' | ')"
}

function Shot($setup, [string] $name) {
    Start-Sleep -Milliseconds 800
    [SetupUi]::Shot($setup.Window, (Join-Path $OutDir "$name.png"))
    Write-Host "Screenshot $name shows: $(([SetupUi]::Texts($setup.Window)) -join ' | ')"
}

function Click($setup, [string] $text) {
    if (-not [SetupUi]::Click($setup.Window, $text)) {
        throw "No '$text' button. It shows: $(([SetupUi]::Texts($setup.Window)) -join ' | ')"
    }
}

function Finish($setup) {
    if (-not $setup.Process.HasExited) {
        [SetupUi]::Close($setup.Window)
        if (-not $setup.Process.WaitForExit(30000)) { $setup.Process.Kill() }
    }
    Write-Host "Setup exited with $($setup.Process.ExitCode)"
}

function Step([string] $name, [scriptblock] $body) {
    Write-Host "== $name"
    try { & $body } catch {
        Write-Host "::warning::$name failed: $($_.Exception.Message)"
        $script:failures += $name
    }
    Get-Process | Where-Object { $_.Path -eq $Setup } | Stop-Process -Force -ErrorAction SilentlyContinue
}

function Entries {
    Get-ChildItem 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall' |
        ForEach-Object { Get-ItemProperty $_.PSPath } |
        Where-Object { $_.DisplayName -like 'Convx*' } |
        ForEach-Object { "$($_.DisplayName) $($_.DisplayVersion) (hidden: $([bool] $_.SystemComponent))" }
}

Step 'An older install from the .msi, then the setup updates it' {
    $msiLog = Join-Path $OutDir 'msi-only.log'
    $p = Start-Process msiexec.exe -ArgumentList @('/i', "`"$Msi`"", '/qn', '/l*v', "`"$msiLog`"") -PassThru -Wait
    Write-Host "msiexec exited with $($p.ExitCode); Convx.exe there: $(Test-Path $exe)"
    $s = Start-Setup 'update.log'
    Wait-For $s 'Update Convx'
    Shot $s '1-update'
    Click $s 'Update'
    Wait-For $s 'Updating Convx*' 30
    Shot $s '2-updating'
    Wait-For $s 'Convx is ready' 300
    Shot $s '3-ready'
    Write-Host "Convx.exe there: $(Test-Path $exe)"
    Write-Host "Installed apps: $((Entries) -join '; ')"
    Click $s 'Close'
    $s.Process.WaitForExit(30000) | Out-Null
    Finish $s
}

Step 'Run again, then uninstall' {
    $s = Start-Setup 'uninstall.log'
    Wait-For $s 'Convx is installed'
    Shot $s '4-installed'
    Click $s 'Uninstall'
    Wait-For $s 'Removing Convx*' 30
    Shot $s '5-removing'
    Wait-For $s 'Convx was removed' 300
    Shot $s '6-removed'
    Write-Host "Convx.exe still there: $(Test-Path $exe)"
    Write-Host "Installed apps: $((Entries) -join '; ')"
    Finish $s
}

Step 'On 32-bit Windows: a plain message, nothing installed' {
    $s = Start-Setup 'needs-64-bit.log' @('ConvxCheckAs32Bit=1')
    Wait-For $s 'Needs 64-bit Windows' 30
    Wait-For $s 'This PC runs 32-bit Windows*' 10
    Shot $s '11-needs-64-bit'
    Finish $s
    if (Test-Path $exe) { throw 'Convx was installed anyway' }
}

Step 'A fresh install that fails (a drive that does not exist)' {
    $s = Start-Setup 'failure.log' @('InstallFolder=Q:\Nowhere\Convx')
    Wait-For $s 'Install Convx'
    Shot $s '7-install'
    Click $s 'Install'
    # Windows Installer's own error comes first, in a message box; answer it as a person would.
    $deadline = (Get-Date).AddSeconds(180)
    $answered = $false
    while ((Get-Date) -lt $deadline -and -not ([SetupUi]::Texts($s.Window) | Where-Object { $_ -like 'Setup didn*' })) {
        $box = [SetupUi]::FindBox('Convx Setup')
        if ($box -ne [IntPtr]::Zero -and -not $answered) {
            Start-Sleep -Milliseconds 500
            [SetupUi]::Shot($box, (Join-Path $OutDir '8-error-box.png'))
            Write-Host "Error box says: $(([SetupUi]::Texts($box)) -join ' | ')"
            foreach ($id in 2, 3, 1) {
                [SetupUi]::Answer($box, $id)
                Start-Sleep -Milliseconds 800
                if ([SetupUi]::FindBox('Convx Setup') -eq [IntPtr]::Zero) { break }
            }
            $answered = $true
        }
        Start-Sleep -Milliseconds 300
    }
    Wait-For $s 'Setup didn*' 30
    Shot $s '9-failed'
    Finish $s
}

Step 'The updater way: /passive with the install folder' {
    $s = Start-Setup 'passive.log' @('/passive', '/norestart', "InstallFolder=$(Split-Path -Parent $exe)")
    Wait-For $s 'Installing Convx*' 60
    Shot $s '10-passive'
    if (-not $s.Process.WaitForExit(300000)) { throw 'The passive install never finished' }
    Write-Host "Passive setup exited with $($s.Process.ExitCode); Convx.exe there: $(Test-Path $exe)"
}

Get-ChildItem $env:TEMP -Filter '*.log' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'Convx*' } | Copy-Item -Destination $OutDir -ErrorAction SilentlyContinue
if ($script:failures.Count) { throw "Failed: $($script:failures -join ', ')" }
Write-Host 'Every step worked'
