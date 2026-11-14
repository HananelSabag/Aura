# Xiaomi Mi TV 4S (MiTV-MSSP3 / machuca, Android 11, 2 GB): second review of what runs, 2026-10-23.
# Adds to the 19 packages already disabled on 2026-10-18..21 (PatchWall, analytics, Google tvlauncher, ...).
# Everything is reversible (pm enable) and logged to this TV's undo file. Safe to run again.
#
# Never disable here: com.mi.bluetooth + com.mediatek.hotkey.dispatcher (remote), mitv.service + xiaomi.tvservice
# (picture/sound), com.mediatek.tvinput + com.mediatek.wwtv.tvcenter (HDMI/Live TV), com.mstar.netflixobserver
# (Netflix button), com.google.android.apps.mediashell (Chromecast, used), YouTube/Netflix/yes+/Prime/YT Music,
# com.xiaomi.mitv.mediaexplorer + com.mitv.videoplayer (USB media), Play Store/Services, keyboard.
. "$PSScriptRoot\common.ps1"
Use-Device 'xiaomi'
Connect-Tv | Out-Null
$u = Read-Undo
$before = Get-FreeMb

$junk = [ordered]@{
  'com.google.android.tungsten.setupwraith'           = 'Setup wizard (kept running in the background, ~23 MB; not a home fallback here)'
  'android.autoinstalls.config.xiaomi.amelie'         = 'Play Auto Install (re-installs promoted apps)'
  'com.google.android.leanbacklauncher.partnercustomizer' = "Partner rows for Google's TV home (that home is off)"
  'fusion.android.tv.demo'                            = 'Retail store demo'
  'com.xiaomi.mimusic2'                               = 'Mi Music (never opened)'
  'com.xm.webcontent'                                 = 'Xiaomi web content for PatchWall (PatchWall is off)'
}
"Turning off:"
foreach ($p in $junk.Keys) {
  $state = (Sh "pm list packages -d $p" | Out-String)
  if ($state -match "package:$([regex]::Escape($p))\s") { "  already off: $p"; continue }
  Sh "am force-stop $p" | Out-Null; Disable-Pkg $u $p; "        ($($junk[$p]))"
}
# Disabled on 2026-10-21 but its process was still alive (system uid): stop it once.
Sh 'am force-stop com.xiaomi.android.tvsetup.partnercustomizer' | Out-Null

"Settings:"
# The 2026-10-21 value (6) was lost (device_config can be reset on this firmware). Aura re-applies it
# on start when it finds it unset.
Set-TvSetting $u 'device_config activity_manager max_cached_processes' '6'
Save-Undo $u

Start-Sleep 3
$cur = (Sh 'dumpsys activity settings' | Select-String 'CUR_MAX_CACHED_PROCESSES' | Select-Object -First 1).Line.Trim()
$after = Get-FreeMb
"Free memory: $before MB -> $after MB   ($cur)"
