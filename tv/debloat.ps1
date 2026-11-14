# TCL (G15, Google TV 14): turns off promotions, store-demo apps and services the user doesn't use.
# Everything is reversible (pm disable-user / settings) and logged to the undo file. Safe to run again.
#
# Never disable (checked 2026-10-22):
#   com.tcl.ttvs            system updates (also TCL telemetry, but update menus break without it)
#   com.tcl.suspension      Inputs/Source menu on the remote
#   com.tcl.tv, tvinput     HDMI and antenna
#   com.tcl.providers.config, com.tcl.initsetup   Wi-Fi drops without them
#   com.tcl.autopair, com.google.android.tv.remote.service   remote control
#   com.android.location.fused   boot loop
#   com.mirakulo.astrotv.service used by the Live TV app (com.tcl.tv holds its signature permission)
#   com.google.android.apps.mediashell   Chromecast
#   com.tcl.guard (limits auto-start), com.tcl.dashboard, AirPlay, Miracast, Play Store/Services, keyboard
param([int]$CachedLimit = 10)
. "$PSScriptRoot\common.ps1"
Connect-Tv | Out-Null
$u = Read-Undo
$before = Get-FreeMb

$junk = [ordered]@{
  'com.tcl.waterfall.overseas'           = 'TCL Channel (promoted content)'
  'com.tcl.messagebox'                   = 'TCL Message Box (promotions)'
  'com.tcl.usercenter'                   = 'TCL User Center (account for TCL Channel)'
  'com.tcl.esticker'                     = 'Showmode (store demo)'
  'com.tcl.exhibit'                      = 'T-Exhibition (store demo)'
  'com.tcl.hotelmenu'                    = 'Hotel menu'
  'com.tcl.magiconnectfree'              = 'MagiConnect'
  'com.tcl.logkit'                       = 'TCL log collection'
  'com.android.tv.feedbackconsent'       = 'Feedback consent'
  'com.google.android.feedback'          = 'Google feedback'
  'com.google.android.marvin.talkback'   = 'TalkBack'
  'com.android.printspooler'             = 'Printing'
  'com.google.android.play.games'        = 'Play Games'
  'com.google.android.syncadapters.calendar' = 'Calendar sync'
  'com.google.android.apps.tv.dreamx'    = 'Ambient mode screensaver (shows suggestions)'
  'com.google.android.katniss'           = 'Voice search / Assistant (user does not use it)'
  # Added 2026-10-22 after the second review:
  'com.tcl.channelplus'                  = 'TCL Channel+ / TCL Live (ad-supported channels, starts at boot)'
  'com.tcl.partnercustomizer'            = "TCL's rows and ads for the Google TV home (unused with our launcher)"
  'android.autoinstalls.config.google.gtvpai' = 'Play Auto Install (re-installs promoted apps such as games)'
  'com.android.hotwordenrollment.okgoogle'    = '"Ok Google" voice enrollment (voice search is off)'
  'com.tcl.ocean.instructions'           = 'TCL e-manual (starts at boot)'
}
"Turning off:"
foreach ($p in $junk.Keys) {
  $state = (Sh "pm list packages -d $p" | Out-String)
  if ($state -match "package:$([regex]::Escape($p))\s") { "  already off: $p"; continue }
  Sh "am force-stop $p" | Out-Null; Disable-Pkg $u $p; "        ($($junk[$p]))"
}

"Settings:"
foreach ($k in 'global window_animation_scale','global transition_animation_scale','global animator_duration_scale') { Set-TvSetting $u $k '0.5' }
Set-TvSetting $u 'global wifi_scan_always_enabled' '0'
Set-TvSetting $u 'global ble_scan_always_enabled' '0'
Set-TvSetting $u 'device_config activity_manager max_cached_processes' "$CachedLimit"
Save-Undo $u

Start-Sleep 3
$cur = (Sh 'dumpsys activity settings' | Select-String 'CUR_MAX_CACHED_PROCESSES' | Select-Object -First 1).Line.Trim()
$after = Get-FreeMb
"Free memory: $before MB -> $after MB   ($cur)"
