param([string]$Src, [string]$Res)
Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'

$img = [Drawing.Bitmap]::FromFile($Src)
$W = $img.Width; $H = $img.Height
$cx = $W / 2.0; $cy = $H / 2.0

# --- 1. Separate the artwork from the vertical gradient background ---------------------
$white = @(255, 255, 255)
# ring / ".Net" blue: brightest blue on the ring at the left edge of the middle row
$blue = @(0x59, 0xAB, 0xFF)
$fg = New-Object Drawing.Bitmap $W, $H, ([Drawing.Imaging.PixelFormat]::Format32bppArgb)
$stat = New-Object Drawing.Bitmap $W, $H, ([Drawing.Imaging.PixelFormat]::Format32bppArgb)
for ($y = 0; $y -lt $H; $y++) {
    $bp = $img.GetPixel(2, $y); $b = @($bp.R, $bp.G, $bp.B)
    for ($x = 0; $x -lt $W; $x++) {
        $p = $img.GetPixel($x, $y); $c = @($p.R, $p.G, $p.B)
        $best = $null; $bestErr = 1e9; $bestA = 0
        foreach ($f in @($white, $blue)) {
            $num = 0.0; $den = 0.0
            for ($i = 0; $i -lt 3; $i++) { $num += ($c[$i] - $b[$i]) * ($f[$i] - $b[$i]); $den += ($f[$i] - $b[$i]) * ($f[$i] - $b[$i]) }
            $a = [Math]::Max(0.0, [Math]::Min(1.0, $num / $den))
            $err = 0.0
            for ($i = 0; $i -lt 3; $i++) { $d = $c[$i] - ($b[$i] + $a * ($f[$i] - $b[$i])); $err += $d * $d }
            if ($err -lt $bestErr) { $bestErr = $err; $best = $f; $bestA = $a }
        }
        # JPEG noise in the background: cut very weak alpha
        $alpha = [int][Math]::Round([Math]::Max(0.0, ($bestA - 0.06) / 0.94) * 255)
        $fg.SetPixel($x, $y, [Drawing.Color]::FromArgb($alpha, $best[0], $best[1], $best[2]))
        # Notification silhouette: ring + A, without ".Net" (unreadable at 24dp)
        $r = [Math]::Sqrt(($x - $cx) * ($x - $cx) + ($y - $cy) * ($y - $cy))
        $isText = ($y -gt 470 -and $r -lt 228)
        $sa = if ($isText) { 0 } else { $alpha }
        $stat.SetPixel($x, $y, [Drawing.Color]::FromArgb($sa, 255, 255, 255))
    }
}

function Resize([Drawing.Image]$src, [int]$canvas, [double]$content, [Drawing.Color]$bg) {
    $bmp = New-Object Drawing.Bitmap $canvas, $canvas, ([Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = 'HighQualityBicubic'; $g.SmoothingMode = 'AntiAlias'; $g.PixelOffsetMode = 'HighQuality'
    $g.Clear($bg)
    $o = ($canvas - $content) / 2.0
    $g.DrawImage($src, [single]$o, [single]$o, [single]$content, [single]$content)
    $g.Dispose(); return $bmp
}
function Save($bmp, $path) {
    $b = @($bmp) | Where-Object { $_ -is [Drawing.Bitmap] } | Select-Object -Last 1
    try { $b.Save($path, [Drawing.Imaging.ImageFormat]::Png) } catch { throw "save failed: $path ($(@($bmp).Count) objects): $_" }
    $b.Dispose()
}

# Adaptive icon: ring outer diameter (490 of 640 px) -> 64dp inside the 108dp canvas (66dp safe zone)
$contentDp = 64.0 / 490.0 * $W
$dens = [ordered]@{ 'mdpi' = 1.0; 'hdpi' = 1.5; 'xhdpi' = 2.0; 'xxhdpi' = 3.0; 'xxxhdpi' = 4.0 }
foreach ($k in $dens.Keys) {
    $s = $dens[$k]
    Save (Resize $fg ([int](108 * $s)) ($contentDp * $s) ([Drawing.Color]::Transparent)) "$Res\mipmap-$k\ic_launcher_foreground.png"
    # monochrome = alpha only
    $mono = New-Object Drawing.Bitmap $W, $H, ([Drawing.Imaging.PixelFormat]::Format32bppArgb)
    if ($k -eq 'mdpi') { }
    Save (Resize $stat ([int](108 * $s)) ($contentDp * $s) ([Drawing.Color]::Transparent)) "$Res\mipmap-$k\ic_launcher_monochrome.png"
    $mono.Dispose()

    # Legacy launcher icons (Android 7.x): the original artwork, rounded square and circle
    $size = [int](48 * $s)
    foreach ($round in @($false, $true)) {
        $bmp = New-Object Drawing.Bitmap $size, $size, ([Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $g = [Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = 'HighQualityBicubic'; $g.SmoothingMode = 'AntiAlias'; $g.Clear([Drawing.Color]::Transparent)
        $m = $size * 0.04; $d = $size - 2 * $m
        $gp = New-Object Drawing.Drawing2D.GraphicsPath
        if ($round) { $gp.AddEllipse([single]$m, [single]$m, [single]$d, [single]$d) }
        else {
            $rr = $d * 0.18
            $gp.AddArc($m, $m, 2 * $rr, 2 * $rr, 180, 90); $gp.AddArc($m + $d - 2 * $rr, $m, 2 * $rr, 2 * $rr, 270, 90)
            $gp.AddArc($m + $d - 2 * $rr, $m + $d - 2 * $rr, 2 * $rr, 2 * $rr, 0, 90); $gp.AddArc($m, $m + $d - 2 * $rr, 2 * $rr, 2 * $rr, 90, 90)
            $gp.CloseFigure()
        }
        $g.SetClip($gp)
        # slight zoom so the ring is not cut by the circle mask
        $z = if ($round) { $d * 1.0 } else { $d }
        $g.DrawImage($img, [single]($m + ($d - $z) / 2), [single]($m + ($d - $z) / 2), [single]$z, [single]$z)
        $g.Dispose()
        $name = if ($round) { 'ic_launcher_round.png' } else { 'ic_launcher.png' }
        Save $bmp "$Res\mipmap-$k\$name"
    }

    # Notification small icon: 24dp white silhouette, ring fills 22dp
    $ss = [int](24 * $s)
    Save (Resize $stat $ss (22.0 / 490.0 * $W * $s) ([Drawing.Color]::Transparent)) "$Res\drawable-$k\ic_stat_name.png"
    if (Test-Path "$Res\drawable-$k\ic_stat_name_black.png") {
        $bk = Resize $stat $ss (22.0 / 490.0 * $W * $s) ([Drawing.Color]::Transparent)
        for ($yy = 0; $yy -lt $ss; $yy++) { for ($xx = 0; $xx -lt $ss; $xx++) { $q = $bk.GetPixel($xx, $yy); $bk.SetPixel($xx, $yy, [Drawing.Color]::FromArgb($q.A, 0, 0, 0)) } }
        Save $bk "$Res\drawable-$k\ic_stat_name_black.png"
    }
}

# In-app logo (key screen, about dialog): the original artwork, 384px, circle-cropped
$logo = New-Object Drawing.Bitmap 384, 384, ([Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [Drawing.Graphics]::FromImage($logo); $g.InterpolationMode = 'HighQualityBicubic'; $g.SmoothingMode = 'AntiAlias'
$g.Clear([Drawing.Color]::Transparent)
$gp = New-Object Drawing.Drawing2D.GraphicsPath; $gp.AddEllipse(0, 0, 384, 384); $g.SetClip($gp)
$g.DrawImage($img, 0, 0, 384, 384); $g.Dispose()
New-Item -ItemType Directory -Force "$Res\drawable-nodpi" | Out-Null
Save $logo "$Res\drawable-nodpi\ic_amelia_logo.png"

# TV banner 320x180 (xhdpi): gradient + logo + name
$ban = New-Object Drawing.Bitmap 320, 180
$g = [Drawing.Graphics]::FromImage($ban); $g.InterpolationMode = 'HighQualityBicubic'; $g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAlias'
$rect = New-Object Drawing.Rectangle 0, 0, 320, 180
$lg = New-Object Drawing.Drawing2D.LinearGradientBrush $rect, ([Drawing.Color]::FromArgb(255, 0x11, 0x1C, 0x49)), ([Drawing.Color]::FromArgb(255, 0x48, 0x3E, 0xD5)), 90
$g.FillRectangle($lg, $rect)
$g.DrawImage($fg, 14, 18, 144, 144)
$font = New-Object Drawing.Font 'Segoe UI', 30, ([Drawing.FontStyle]::Bold), ([Drawing.GraphicsUnit]::Pixel)
$g.DrawString('Amelia.Net', $font, [Drawing.Brushes]::White, 150, 68)
$g.Dispose(); Save $ban "$Res\mipmap-xhdpi\ic_banner.png"

# App background watermark (1.0.2): the full-resolution artwork without its background,
# cropped to the ring, so the screen can show the avatar large and crisp.
$ringPx = [int]($W * 495 / 640); $o = [int](($W - $ringPx) / 2)
$wm = $fg.Clone((New-Object Drawing.Rectangle $o, $o, $ringPx, $ringPx), [Drawing.Imaging.PixelFormat]::Format32bppArgb)
Save $wm "$Res\drawable-nodpi\amelia_watermark.png"

$fg.Dispose(); $stat.Dispose(); $img.Dispose()
"done"
