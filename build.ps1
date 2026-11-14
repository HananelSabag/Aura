# Builds Aura.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage: .\build.ps1 [-VersionCode 2] [-VersionName 1.1]
# Targets API 35 (Play Protect blocks sideloads that target old versions); runs from Android 11 (the Xiaomi).
param([int]$VersionCode = 1, [string]$VersionName = '1.0')
$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$bt = Join-Path $sdk 'build-tools\36.1.0'
$jar = Join-Path $sdk 'platforms\android-35\android.jar'
$jdk = Get-ChildItem 'C:\CodingProjects\_tools' -Directory | Where-Object Name -like 'jdk-21*' | Select-Object -First 1
if (-not $jdk) { throw 'JDK 21 not found in C:\CodingProjects\_tools' }
$env:JAVA_HOME = $jdk.FullName
$env:Path = "$($jdk.FullName)\bin;$env:Path"

function Step([string]$name, [scriptblock]$cmd) {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  & $cmd
  if ($LASTEXITCODE) { throw "$name failed (exit $LASTEXITCODE)" }
  '{0,-10} {1,6:N1}s' -f $name, $sw.Elapsed.TotalSeconds
}

$out = Join-Path $root 'build'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out, "$out\gen", "$out\classes", "$out\dex" | Out-Null

Step 'resources' { & "$bt\aapt2.exe" compile --dir "$root\res" -o "$out\res.zip" }
Step 'link' {
  & "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$root\AndroidManifest.xml" -R "$out\res.zip" -A "$root\assets" -0 ttf `
    --java "$out\gen" --min-sdk-version 30 --target-sdk-version 35 `
    --version-code $VersionCode --version-name $VersionName --auto-add-overlay
}
$sources = @(Get-ChildItem "$root\src", "$out\gen" -Recurse -Filter *.java | ForEach-Object FullName)
# core-lambda-stubs.jar supplies LambdaMetafactory, which android.jar does not have (d8 desugars lambdas).
$boot = "$jar;$bt\core-lambda-stubs.jar"
Step 'javac' { & javac -source 8 -target 8 -bootclasspath $boot -encoding UTF-8 -Xlint:-options -Xlint:deprecation -d "$out\classes" @sources }
$classes = @(Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName)
Step 'd8' { & "$bt\d8.bat" --release --min-api 30 --lib $jar --output "$out\dex" @classes }
Push-Location "$out\dex"
try { Step 'package' { & "$bt\aapt.exe" add "$out\base.apk" classes.dex | Out-Null } } finally { Pop-Location }
Step 'zipalign' { & "$bt\zipalign.exe" -f -p 4 "$out\base.apk" "$out\aligned.apk" }

# Signing key: signing.local.ps1 (not committed) sets $KeyStore and $KeyStorePass; without it the
# Android debug key is used. Keep one key per TV: an installed app only updates from the same key.
$KeyStore = Join-Path $env:USERPROFILE '.android\debug.keystore'; $KeyStorePass = 'android'
$signing = Join-Path $root 'signing.local.ps1'
if (Test-Path -LiteralPath $signing) { . $signing }
if (-not (Test-Path -LiteralPath $KeyStore)) { throw "signing key not found: $KeyStore (see signing.example.ps1)" }
Step 'sign' {
  & "$bt\apksigner.bat" sign --ks $KeyStore --ks-pass "pass:$KeyStorePass" --key-pass "pass:$KeyStorePass" `
    --out "$root\Aura.apk" "$out\aligned.apk"
}
& "$bt\apksigner.bat" verify "$root\Aura.apk"
if ($LASTEXITCODE) { throw 'signature verify failed' }
'Aura.apk  {0:N0} KB  (version {1} / {2})' -f ((Get-Item "$root\Aura.apk").Length / 1KB), $VersionCode, $VersionName
