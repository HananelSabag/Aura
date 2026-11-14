# Prepares a wallpaper folder for the TVs and copies it to Aura's own storage on them.
# Each picture is centre-cropped to 16:9 and saved as a 1920x1080 JPEG (q88): ~0.4 MB instead of up
# to 13 MB, so the TV decodes it in a moment. Aura finds them in Android/data/com.sabag.aura/files/
# wallpapers/<collection>/ (no permission needed), or in Pictures/Aura/ as a fallback.
param(
    [Parameter(Mandatory)] [string]$Source,   # a folder of pictures
    [string]$Collection = ((Split-Path $Source -Leaf).ToLower() -replace '[^a-z0-9]+', '-'),
    [string[]]$Tvs = @(),                     # adb addresses; default: every TV in devices.local.ps1
    # Also make it the background (Aura picks it up the next time it starts).
    [switch]$Apply
)
if (-not $Tvs) { . "$PSScriptRoot\common.ps1"; $Tvs = @($Devices.Values | ForEach-Object { $_.Address }) }
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$out = Join-Path $PSScriptRoot "wallpapers\$Collection"
New-Item -ItemType Directory -Force -Path $out | Out-Null

$jpeg = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
$params = New-Object System.Drawing.Imaging.EncoderParameters 1
$params.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality), 88L

$i = 0
Get-ChildItem -LiteralPath $Source -File | Where-Object { $_.Extension -match '^\.(jpe?g|png|webp|bmp)$' } | Sort-Object Name | ForEach-Object {
    $i++
    $dst = Join-Path $out ('{0:D2}.jpg' -f $i)
    if (Test-Path $dst) { return }
    $img = [System.Drawing.Image]::FromFile($_.FullName)
    try {
        $scale = [Math]::Max(1920 / $img.Width, 1080 / $img.Height)
        $cw = [int][Math]::Round(1920 / $scale); $ch = [int][Math]::Round(1080 / $scale)
        $cx = [int](($img.Width - $cw) / 2); $cy = [int](($img.Height - $ch) / 2)
        $bmp = New-Object System.Drawing.Bitmap 1920, 1080
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $g.DrawImage($img, (New-Object System.Drawing.Rectangle 0, 0, 1920, 1080), (New-Object System.Drawing.Rectangle $cx, $cy, $cw, $ch), [System.Drawing.GraphicsUnit]::Pixel)
        $g.Dispose()
        $bmp.Save($dst, $jpeg, $params)
        $bmp.Dispose()
    } finally { $img.Dispose() }
}
$size = (Get-ChildItem $out -File | Measure-Object Length -Sum).Sum / 1MB
"{0} pictures ready, {1:N1} MB" -f (Get-ChildItem $out -File).Count, $size

foreach ($tv in $Tvs) {
    & $adb connect $tv | Out-Null
    $state = (& $adb -s $tv get-state 2>$null)
    if ($state -ne 'device') { "$tv not reachable, skipped"; continue }
    # Android 13+ (the TCL): files adb writes into an app's own folder belong to adb, and the app can't
    # read them; Pictures/Aura works there through Aura's photos permission. Android 11 (the Xiaomi):
    # the app's own folder works (no permission needed).
    $sdk = [int](& $adb -s $tv shell getprop ro.build.version.sdk)
    $own = '/sdcard/Android/data/com.sabag.aura/files/wallpapers'
    $target = if ($sdk -ge 33) { "/sdcard/Pictures/Aura/$Collection" } else { "$own/$Collection" }
    & $adb -s $tv shell "mkdir -p '$target' '$own'" 2>$null
    $pushed = & $adb -s $tv push "$out\." $target 2>&1
    if ($Apply) { & $adb -s $tv shell "touch '$own/.apply'" }
    "$tv -> $target : " + ($pushed | Select-Object -Last 1)
}
