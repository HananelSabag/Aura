# Installs Aura, grants what it needs (all optional extras) and makes it the home screen.
#   .\install.ps1            the TCL (Google TV 14) — full Aura
#   .\install.ps1 -Xiaomi    the Mi TV 4S (Android 11, 2 GB) — Aura switches itself to lite mode there
# Google's home stays disabled on both (TCL: ShketPro\tv\set-home.ps1 -Restore to undo).
param([switch]$NoHome, [switch]$Xiaomi)
. "$PSScriptRoot\common.ps1"
if ($Xiaomi) { Use-Device 'xiaomi' }
Connect-Tv | Out-Null
$apk = Join-Path (Split-Path $PSScriptRoot -Parent) 'Aura.apk'
& $Adb -s $Tv install -r $apk
$pkg = 'com.sabag.aura'
$sdk = [int]((Sh 'getprop ro.build.version.sdk') | Select-Object -First 1)
Sh "pm grant $pkg android.permission.ACCESS_FINE_LOCATION" | Out-Null      # Wi-Fi name in the network chip
Sh "pm grant $pkg android.permission.ACCESS_COARSE_LOCATION" | Out-Null
if ($sdk -ge 33) { Sh "pm grant $pkg android.permission.READ_MEDIA_IMAGES" | Out-Null }   # optional photo background
else { Sh "pm grant $pkg android.permission.READ_EXTERNAL_STORAGE" | Out-Null }
Sh "appops set $pkg GET_USAGE_STATS allow" | Out-Null                     # "opened today at 20:18"
Sh "cmd package compile -m speed -f $pkg" | Out-Null                      # AOT: smooth from the first launch
if (-not $NoHome) {
  Sh "cmd role add-role-holder --user 0 android.app.role.HOME $pkg" | Out-Null
  if ($sdk -lt 31) { Sh "cmd package set-home-activity --user 0 $pkg/.HomeActivity" | Out-Null }
  $u = Read-Undo; $u.Home = "$pkg/.HomeActivity"; Save-Undo $u
}
Sh 'input keyevent KEYCODE_HOME' | Out-Null
Start-Sleep 3
"home: " + ((Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME') | Select-Object -Last 1)
Get-Focus
