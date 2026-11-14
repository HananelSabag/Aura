# Shared helpers for the TCL scripts: connect, run shell commands, keep an undo log.
# 'Continue': adb reports progress on stderr, which Windows PowerShell would otherwise turn into errors.
$ErrorActionPreference = 'Continue'
$Adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
# Your TVs live in tv\devices.local.ps1 (not committed): copy devices.example.ps1 and fill it in.
$DevicesFile = Join-Path $PSScriptRoot 'devices.local.ps1'
if (-not (Test-Path -LiteralPath $DevicesFile)) {
  throw "Copy tv\devices.example.ps1 to tv\devices.local.ps1 and put your TV's serial number and address in it."
}
. $DevicesFile
if (-not $UndoFolder) { $UndoFolder = Join-Path $PSScriptRoot 'undo' }
New-Item -ItemType Directory -Force -Path $UndoFolder | Out-Null

# Picks the TV a script talks to: the first one in devices.local.ps1 unless the script asks for another.
function Use-Device([string]$Name) {
  if (-not $Devices.Contains($Name)) { throw "No TV named '$Name' in devices.local.ps1" }
  $script:TvSerialNo = $Devices[$Name].Serial
  $script:TvAddress = $Devices[$Name].Address
  $script:UndoPath = Join-Path $UndoFolder "undo-$($Devices[$Name].Serial).json"
}
Use-Device (@($Devices.Keys)[0])
$Tv = $null

# Classic network adb (port 5555) survives standby. Wireless debugging (TLS, port changes) is the fallback.
function Connect-Tv {
  $candidates = @($TvAddress)
  foreach ($line in (& $Adb mdns services 2>$null)) {
    if ($line -match "adb-$TvSerialNo\S*\s+_adb-tls-connect\._tcp\s+(\S+)") { $candidates += $Matches[1] }
  }
  foreach ($t in $candidates) {
    & $Adb connect $t 2>$null | Out-Null
    if ((& $Adb -s $t get-state 2>$null | Out-String).Trim() -eq 'device' -and (& $Adb -s $t shell getprop ro.serialno).Trim() -eq $TvSerialNo) {
      $script:Tv = $t; return $t
    }
  }
  throw "TV $TvAddress not found. Is it on, with network debugging enabled?"
}

function Sh([string]$cmd) { (& $Adb -s $script:Tv shell $cmd 2>$null) }

function Read-Undo {
  $u = @{ Packages = @(); Settings = @{}; Home = '' }
  if (Test-Path -LiteralPath $UndoPath) {
    $j = Get-Content -LiteralPath $UndoPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $u.Packages = @($j.Packages | Where-Object { $_ } | ForEach-Object { @{ Pkg = $_.Pkg; Method = $_.Method; When = $_.When } })
    if ($j.Settings) { foreach ($p in $j.Settings.PSObject.Properties) { $u.Settings[$p.Name] = $p.Value } }
    if ($j.Home) { $u.Home = $j.Home }
  }
  $u
}
function Save-Undo($u) { ConvertTo-Json -InputObject $u -Depth 5 | Set-Content -LiteralPath $UndoPath -Encoding UTF8 }

function Disable-Pkg($u, [string]$pkg) {
  $o = (Sh "pm disable-user --user 0 $pkg" | Out-String).Trim()
  if ($o -match 'new state: disabled') {
    if (-not ($u.Packages | Where-Object { $_.Pkg -eq $pkg })) { $u.Packages += @{ Pkg = $pkg; Method = 'disable'; When = (Get-Date).ToString('s') } }
    "  off: $pkg"
  } else { "  could not disable $pkg : $o" }
}

# Changes a setting ('global key' / 'secure key' / 'device_config namespace key') and remembers the original once.
function Set-TvSetting($u, [string]$key, [string]$value) {
  $parts = $key -split ' '
  if (-not $u.Settings.ContainsKey($key)) {
    $old = if ($parts[0] -eq 'device_config') { (Sh "device_config get $($parts[1]) $($parts[2])" | Out-String).Trim() } else { (Sh "settings get $key" | Out-String).Trim() }
    $u.Settings[$key] = $old
  }
  if ($parts[0] -eq 'device_config') { Sh "device_config put $($parts[1]) $($parts[2]) $value" | Out-Null } else { Sh "settings put $key $value" | Out-Null }
  "  set: $key = $value (was $($u.Settings[$key]))"
}

function Get-FreeMb { [int](((Sh 'grep MemAvailable /proc/meminfo') -split '\s+')[1] / 1024) }
function Get-Pss([string]$pkg) {
  $line = Sh "dumpsys meminfo $pkg" | Select-String 'TOTAL PSS:' | Select-Object -First 1
  if ($line -and $line.Line -match 'TOTAL PSS:\s+(\d+)') { [math]::Round([int]$Matches[1] / 1024, 1) } else { $null }
}
function Get-Focus { ((Sh 'dumpsys window') | Select-String 'mCurrentFocus' | Select-Object -First 1).Line.Trim() }
