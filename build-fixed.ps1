# NotifyBridge fixed build script for Windows PowerShell.
# Xposed API is compile-only: it must never be packaged into classes.dex.
param(
    [string]$AndroidHome = "X:\build-env\sdk",
    [string]$JavaHome = "X:\build-env\jdk\jdk-17.0.20.1+1",
    [string]$Keystore = "$env:USERPROFILE\.android\debug.keystore",
    [string]$KeystorePass = "android"
)

$ErrorActionPreference = "Stop"
$Project = Split-Path -Parent $MyInvocation.MyCommand.Path
$Build = Join-Path $Project "build\fixed"
$BT = Join-Path $AndroidHome "build-tools\34.0.0"
$AndroidJar = Join-Path $AndroidHome "platforms\android-35\android.jar"
$XposedApi = Join-Path $Project "libs\xposed-api.jar"
$Javac = Join-Path $JavaHome "bin\javac.exe"
$Jar = Join-Path $JavaHome "bin\jar.exe"
$Keytool = Join-Path $JavaHome "bin\keytool.exe"
$Aapt2 = Join-Path $BT "aapt2.exe"
$D8 = Join-Path $BT "d8.bat"
$Zipalign = Join-Path $BT "zipalign.exe"
$Apksigner = Join-Path $BT "apksigner.bat"

if (!(Test-Path $AndroidJar)) { throw "android.jar not found: $AndroidJar" }
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"
if (!(Test-Path $XposedApi)) { throw "xposed-api.jar not found: $XposedApi" }

Remove-Item -LiteralPath $Build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$Build\gen", "$Build\obj" | Out-Null

& $Aapt2 compile --dir (Join-Path $Project "res") -o "$Build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

& $Aapt2 link -o "$Build\app-unsigned.apk" -I $AndroidJar --manifest (Join-Path $Project "AndroidManifest.xml") --java "$Build\gen" -A (Join-Path $Project "assets") "$Build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$sources = Get-ChildItem -Path "$Project\src", "$Build\gen" -Recurse -Filter *.java | ForEach-Object FullName
$sourceList = "$Build\sources.txt"
Set-Content -LiteralPath $sourceList -Value $sources -Encoding ASCII
& $Javac -encoding UTF-8 -source 8 -target 8 -classpath "$AndroidJar;$XposedApi" -d "$Build\obj" "@$sourceList"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# Important: pass only app classes to d8. xposed-api.jar is only a classpath dependency.
$appClasses = Get-ChildItem -LiteralPath "$Build\obj" -Recurse -Filter *.class | ForEach-Object FullName
$d8List = "$Build\d8-inputs.txt"
Set-Content -LiteralPath $d8List -Value $appClasses -Encoding ASCII
& $D8 --release --lib $AndroidJar --classpath $XposedApi --output $Build "@$d8List"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Copy-Item "$Build\app-unsigned.apk" "$Build\app-unaligned.apk" -Force
Push-Location $Build
try {
    & $Jar uf app-unaligned.apk classes.dex
    if ($LASTEXITCODE -ne 0) { throw "jar update failed" }
} finally { Pop-Location }

& $Zipalign -f 4 "$Build\app-unaligned.apk" "$Build\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

& $Apksigner sign --ks $Keystore --ks-pass "pass:$KeystorePass" --key-pass "pass:$KeystorePass" --ks-key-alias androiddebugkey --out "$Build\NotifyBridge-fixed.apk" "$Build\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& $Apksigner verify "$Build\NotifyBridge-fixed.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

Copy-Item "$Build\NotifyBridge-fixed.apk" (Join-Path $Project "build\NotifyBridge-fixed.apk") -Force
Write-Host "APK: $Build\NotifyBridge-fixed.apk"
