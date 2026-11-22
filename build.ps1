# Builds the ytm-alice remote APK with the Android SDK command line tools.
# No Gradle required: aapt2 -> javac -> d8 -> zipalign -> apksigner.
[CmdletBinding()]
param(
    [string]$VersionName = "1.0.0",
    [int]$VersionCode = 1,
    [string]$Keystore = "",
    [string]$KeyAlias = "ytm-alice-remote",
    [string]$StorePassword = "ytmalice",
    [string]$KeyPassword = "ytmalice"
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

# PowerShell 5.1 mangles non-ASCII arguments passed to native tools, so all
# native commands run with paths relative to the project directory.
Set-Location $root

function Invoke-Native {
    param([string]$File, [string[]]$Arguments)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    $output = & $File @Arguments 2>&1
    $ErrorActionPreference = $prev
    if ($LASTEXITCODE -ne 0) {
        $output | Out-Host
        throw "Command failed: $File (exit $LASTEXITCODE)"
    }
    return $output
}

$project = "app\src\main"
if (-not $Keystore) {
    $Keystore = "keystore\ytm-alice-remote.jks"
}

# ---------------------------------------------------------------- toolchain

function Find-JavaHome {
    $candidates = @(
        $env:JAVA_HOME,
        "C:\android-studio-portable\app\jbr",
        "C:\android-studio-portable\jbr",
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Java\jdk-21",
        "C:\Program Files\Java\jdk-17"
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate "bin\javac.exe"))) {
            return $candidate
        }
    }
    throw "JDK not found. Install JDK 17+ or set JAVA_HOME."
}

function Find-Sdk {
    $candidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate "platforms"))) {
            return $candidate
        }
    }
    throw "Android SDK not found. Set ANDROID_HOME."
}

$javaHome = Find-JavaHome
$env:JAVA_HOME = $javaHome
$env:PATH = (Join-Path $javaHome "bin") + ";" + $env:PATH

$sdk = Find-Sdk
$buildTools = Get-ChildItem (Join-Path $sdk "build-tools") -Directory |
    Sort-Object { [int]($_.Name.Split('.')[0]) } -Descending |
    Select-Object -First 1
if (-not $buildTools) { throw "Android build-tools not found in $sdk\build-tools" }
$bt = $buildTools.FullName

$platforms = Get-ChildItem (Join-Path $sdk "platforms") -Directory |
    Sort-Object {
        $n = $_.Name -replace '^android-', ''
        $parts = $n.Split('.')
        if ($parts[0] -match '^\d+$') { [int]$parts[0] } else { 0 }
    } -Descending
$preferred = Join-Path $sdk "platforms\android-34\android.jar"
$androidJar = $null
if (Test-Path $preferred) {
    $androidJar = $preferred
} else {
    foreach ($platform in $platforms) {
        $candidate = Join-Path $platform.FullName "android.jar"
        if ((Test-Path $candidate) -and $platform.Name -match '^android-\d+(\.\d+)?$') {
            $androidJar = $candidate; break
        }
    }
}
if (-not $androidJar) { throw "android.jar not found in $sdk\platforms" }

$aapt2 = Join-Path $bt "aapt2.exe"
$zipalign = Join-Path $bt "zipalign.exe"
$d8 = Join-Path $bt "d8.bat"
$apksigner = Join-Path $bt "apksigner.bat"
$javac = Join-Path $javaHome "bin\javac.exe"
$keytool = Join-Path $javaHome "bin\keytool.exe"

foreach ($tool in @($aapt2, $zipalign, $d8, $apksigner, $javac, $keytool)) {
    if (-not (Test-Path $tool)) { throw "Missing tool: $tool" }
}

Write-Host "JDK:        $javaHome"
Write-Host "Build tools: $($buildTools.Name)"
Write-Host "android.jar: $androidJar"

# ---------------------------------------------------------------- workspace

$build = "build"
$genDir = Join-Path $build "gen"
$classesDir = Join-Path $build "classes"
$dexDir = Join-Path $build "dex"
$dist = "dist"

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Path $genDir, $classesDir, $dexDir, $dist -Force | Out-Null

$resZip = Join-Path $build "resources.zip"
$unsigned = Join-Path $build "app-unsigned.apk"
$withDex = Join-Path $build "app-with-dex.apk"
$aligned = Join-Path $build "app-aligned.apk"
$final = Join-Path $dist ("ytm-alice-remote-{0}.apk" -f $VersionName)

$resDir = Join-Path $project "res"
$manifest = Join-Path $project "AndroidManifest.xml"

# ------------------------------------------------------------------- icons

if (-not (Test-Path (Join-Path $resDir "mipmap-xxxhdpi\ic_launcher.png"))) {
    Write-Host "Generating icons..."
    & (Join-Path $root "tools\make-icons.ps1")
}

# ----------------------------------------------------------------- compile

Write-Host "Compiling resources..."
Invoke-Native -File $aapt2 -Arguments @('compile', '--dir', $resDir, '-o', $resZip)

Write-Host "Linking resources..."
Invoke-Native -File $aapt2 -Arguments @('link', '-o', $unsigned, '--manifest', $manifest, '-I', $androidJar, '--java', $genDir, '--min-sdk-version', '23', '--target-sdk-version', '34', '--version-code', $VersionCode, '--version-name', $VersionName, '--auto-add-overlay', $resZip)

Write-Host "Compiling Java..."
$sources = @()
$sources += (Get-ChildItem (Join-Path $project "java") -Recurse -Filter *.java | ForEach-Object FullName)
$sources += (Get-ChildItem $genDir -Recurse -Filter *.java | ForEach-Object FullName)
Invoke-Native -File $javac -Arguments (@('-encoding', 'UTF-8', '--release', '11', '-classpath', $androidJar, '-Xlint:-options', '-d', $classesDir) + $sources)

Write-Host "Dexing..."
$classFiles = @(Get-ChildItem $classesDir -Recurse -Filter *.class | ForEach-Object FullName)
Invoke-Native -File $d8 -Arguments (@('--release', '--min-api', '23', '--lib', $androidJar, '--output', $dexDir) + $classFiles)

$dexFile = Join-Path $dexDir "classes.dex"
if (-not (Test-Path $dexFile)) { throw "classes.dex not produced" }

Write-Host "Packaging classes.dex..."
Copy-Item $unsigned $withDex -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($withDex, "Update")
try {
    $existing = $zip.GetEntry("classes.dex")
    if ($existing) { $existing.Delete() }
    $entry = $zip.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
    $stream = $entry.Open()
    $bytes = [System.IO.File]::ReadAllBytes($dexFile)
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Dispose()
} finally {
    $zip.Dispose()
}

Write-Host "Aligning..."
Invoke-Native -File $zipalign -Arguments @('-f', '-p', '4', $withDex, $aligned)

# ------------------------------------------------------------------ signing

$keystoreDir = Split-Path -Parent $Keystore
if (-not (Test-Path $keystoreDir)) { New-Item -ItemType Directory -Path $keystoreDir | Out-Null }
if (-not (Test-Path $Keystore)) {
    Write-Host "Creating signing key..."
    Invoke-Native -File $keytool -Arguments @('-genkeypair', '-keystore', $Keystore, '-alias', $KeyAlias, '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-storepass', $StorePassword, '-keypass', $KeyPassword, '-dname', 'CN=ytm-alice remote, O=ytm-alice, C=RU')
}

Write-Host "Signing..."
Invoke-Native -File $apksigner -Arguments @('sign', '--ks', $Keystore, '--ks-key-alias', $KeyAlias, '--ks-pass', "pass:$StorePassword", '--key-pass', "pass:$KeyPassword", '--out', $final, $aligned)
Invoke-Native -File $apksigner -Arguments @('verify', '--print-certs', $final) | Out-Null

$size = [math]::Round((Get-Item $final).Length / 1KB, 1)
Write-Host ""
Write-Host "APK: $final ($size KB)"
exit 0
