# Builds Convx's setup .exe: the WiX 5 bundle in bundle.wxs around the MSI jpackage made.
param(
    [Parameter(Mandatory)] [string] $Msi,
    # The bundle's own number, which Windows compares; it must go up from one setup to the next.
    [Parameter(Mandatory)] [string] $Version,
    # The version the setup window shows (Convx's version in About); the same as -Version if left out.
    [string] $DisplayVersion,
    [Parameter(Mandatory)] [string] $Icon,
    [Parameter(Mandatory)] [string] $Out
)
$ErrorActionPreference = 'Stop'
$wixVersion = '5.0.2'

function Native([string] $what) {
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit $LASTEXITCODE)" }
}

if (-not (Get-Command wix -ErrorAction SilentlyContinue)) {
    dotnet tool install --global wix --version $wixVersion
    Native 'Installing WiX'
    $env:PATH = "$env:PATH;$env:USERPROFILE\.dotnet\tools"
}
# The extensions straight from nuget.org: "wix extension add" left nothing behind on CI.
$extensions = Join-Path ([IO.Path]::GetTempPath()) "wix-extensions-$wixVersion"
$ext = foreach ($name in 'WixToolset.Bal.wixext', 'WixToolset.Util.wixext') {
    $dir = Join-Path $extensions $name
    if (-not (Test-Path $dir)) {
        $package = Join-Path $extensions "$name.zip"
        New-Item -ItemType Directory -Force $extensions | Out-Null
        Invoke-WebRequest "https://www.nuget.org/api/v2/package/$name/$wixVersion" -OutFile $package
        Expand-Archive $package -DestinationPath $dir -Force
    }
    # The extension is the one .dll under wixext5\ (the Bal one is WixToolset.BootstrapperApplications.wixext.dll).
    $dll = Get-ChildItem (Join-Path $dir 'wixext5') -Filter '*.wixext.dll' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $dll) {
        Get-ChildItem $dir -Recurse | ForEach-Object { Write-Host $_.FullName }
        throw "No extension .dll in the $name package"
    }
    Write-Host "Using $($dll.FullName)"
    '-ext'; $dll.FullName
}

$msiPath = (Resolve-Path $Msi).Path
$iconPath = (Resolve-Path $Icon).Path
New-Item -ItemType Directory -Force (Split-Path -Parent $Out) | Out-Null
if (-not $DisplayVersion) { $DisplayVersion = $Version }
wix build "$PSScriptRoot/bundle.wxs" @ext -d "Version=$Version" -d "DisplayVersion=$DisplayVersion" -d "Msi=$msiPath" -d "Icon=$iconPath" -o $Out
Native 'Building the setup'
Write-Host "Built $Out ($((Get-Item $Out).Length) bytes), Convx $DisplayVersion (bundle $Version)"
