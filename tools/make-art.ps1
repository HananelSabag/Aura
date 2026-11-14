# Generates the app icon and the 320x180 TV banner (xhdpi) into res\drawable-xhdpi:
# Aura's drifting-light backdrop with a glass ring, matching the launcher.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$dir = Join-Path (Split-Path $PSScriptRoot -Parent) 'res\drawable-xhdpi'
New-Item -ItemType Directory -Force $dir | Out-Null

function Blob($g, [float]$cx, [float]$cy, [float]$r, [int]$a, [int]$cr, [int]$cg, [int]$cb) {
  $path = New-Object Drawing.Drawing2D.GraphicsPath
  $path.AddEllipse($cx - $r, $cy - $r, 2 * $r, 2 * $r)
  $br = New-Object Drawing.Drawing2D.PathGradientBrush $path
  $br.CenterColor = [Drawing.Color]::FromArgb($a, $cr, $cg, $cb)
  $br.SurroundColors = @([Drawing.Color]::FromArgb(0, $cr, $cg, $cb))
  $g.FillPath($br, $path)
}

function New-Art([int]$w, [int]$h, [string]$path, [bool]$withText) {
  $bmp = New-Object Drawing.Bitmap $w, $h
  $g = [Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAliasGridFit'
  $g.Clear([Drawing.Color]::FromArgb(255, 5, 6, 10))
  Blob $g ($w * 0.2) ($h * 0.1) ($w * 0.62) 200 25 211 197
  Blob $g ($w * 0.88) ($h * 0.3) ($w * 0.5) 190 124 77 255
  Blob $g ($w * 0.5) ($h * 1.05) ($w * 0.55) 170 47 123 255
  Blob $g ($w * 0.05) ($h * 0.9) ($w * 0.4) 150 255 79 163
  $s = [Math]::Min($w, $h) * ($(if ($withText) { 0.5 } else { 0.62 }))
  $x = if ($withText) { $w - $h * 0.18 - $s } else { ($w - $s) / 2 }
  $y = ($h - $s) / 2
  $disc = New-Object Drawing.Drawing2D.LinearGradientBrush (New-Object Drawing.RectangleF $x, $y, $s, $s), ([Drawing.Color]::FromArgb(120, 255, 255, 255)), ([Drawing.Color]::FromArgb(18, 255, 255, 255)), 60
  $g.FillEllipse($disc, $x, $y, $s, $s)
  $rim = New-Object Drawing.Pen ([Drawing.Color]::FromArgb(235, 255, 255, 255)), ([Math]::Max(1.5, $s * 0.045))
  $g.DrawEllipse($rim, $x + $s * 0.18, $y + $s * 0.18, $s * 0.64, $s * 0.64)
  $thin = New-Object Drawing.Pen ([Drawing.Color]::FromArgb(140, 255, 255, 255)), ([Math]::Max(1, $s * 0.018))
  $g.DrawEllipse($thin, $x, $y, $s, $s)
  if ($withText) {
    $font = New-Object Drawing.Font 'Segoe UI Semibold', ($h * 0.26), ([Drawing.FontStyle]::Regular), ([Drawing.GraphicsUnit]::Pixel)
    $br = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(255, 255, 255, 255))
    $tw = $g.MeasureString('Aura', $font).Width
    $g.DrawString('Aura', $font, $br, ($x - $h * 0.1 - $tw), ($h * 0.31))
  }
  $bmp.Save($path, [Drawing.Imaging.ImageFormat]::Png)
  $g.Dispose(); $bmp.Dispose()
}
New-Art 320 180 (Join-Path $dir 'banner.png') $true
New-Art 96 96 (Join-Path $dir 'icon.png') $false
"art written to $dir"
