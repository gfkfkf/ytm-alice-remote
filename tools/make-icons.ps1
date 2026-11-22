# Generates launcher icons and the in-app logo from the ytm-alice SVG mark.
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$res = Join-Path $root "app\src\main\res"

function New-BlobPath {
    param([double]$Scale, [double]$OffsetX, [double]$OffsetY)

    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $points = @(
        @(20.0, 5.0),
        @(15.8, 5.0), @(9.6, 12.5), @(5.7, 20.1),
        @(2.2, 27.0), @(4.2, 32.2), @(11.7, 34.2),
        @(16.7, 35.5), @(23.3, 35.5), @(28.3, 34.2),
        @(35.8, 32.2), @(37.8, 27.0), @(34.3, 20.1),
        @(30.4, 12.5), @(24.2, 5.0), @(20.0, 5.0)
    )
    $f = New-Object 'System.Collections.Generic.List[System.Drawing.PointF]'
    foreach ($p in $points) {
        $f.Add([System.Drawing.PointF]::new(
            [float]($OffsetX + $p[0] * $Scale),
            [float]($OffsetY + $p[1] * $Scale)))
    }
    $path.StartFigure()
    $path.AddBezier($f[0], $f[1], $f[2], $f[3])
    $path.AddBezier($f[3], $f[4], $f[5], $f[6])
    $path.AddBezier($f[6], $f[7], $f[8], $f[9])
    $path.AddBezier($f[9], $f[10], $f[11], $f[12])
    $path.AddBezier($f[12], $f[13], $f[14], $f[15])
    $path.CloseFigure()
    return $path
}

function New-RoundedRect {
    param([double]$Size, [double]$Radius)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $Radius * 2
    $path.AddArc(0, 0, [float]$d, [float]$d, 180, 90)
    $path.AddArc([float]($Size - $d), 0, [float]$d, [float]$d, 270, 90)
    $path.AddArc([float]($Size - $d), [float]($Size - $d), [float]$d, [float]$d, 0, 90)
    $path.AddArc(0, [float]($Size - $d), [float]$d, [float]$d, 90, 90)
    $path.CloseFigure()
    return $path
}

function New-Canvas {
    param([int]$Size, [bool]$Round)
    $bmp = New-Object System.Drawing.Bitmap($Size, $Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.Clear([System.Drawing.Color]::Transparent)

    if ($Round) {
        $shape = New-Object System.Drawing.Drawing2D.GraphicsPath
        $shape.AddEllipse(0, 0, $Size, $Size)
    } else {
        $shape = New-RoundedRect -Size $Size -Radius ($Size * 0.235)
    }
    $rect = New-Object System.Drawing.RectangleF(0, 0, [float]$Size, [float]$Size)
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        $rect,
        [System.Drawing.Color]::FromArgb(255, 30, 30, 40),
        [System.Drawing.Color]::FromArgb(255, 15, 15, 20),
        90.0)
    $g.FillPath($brush, $shape)
    $brush.Dispose()

    $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 255, 87, 74), 1.0)
    $pen.Color = [System.Drawing.Color]::FromArgb(255, 46, 46, 58)
    $g.DrawPath($pen, $shape)
    $pen.Dispose()

    return @{ Bitmap = $bmp; Graphics = $g }
}

function Draw-Logo {
    param([System.Drawing.Graphics]$Graphics, [int]$Size, [double]$Coverage)
    $scale = ($Size * $Coverage) / 40.0
    $offset = ($Size - 40.0 * $scale) / 2.0
    $path = New-BlobPath -Scale $scale -OffsetX $offset -OffsetY $offset
    $width = [float](3.2 * $scale)
    $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 255, 87, 74), $width)
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $Graphics.DrawPath($pen, $path)
    $pen.Dispose()
    $path.Dispose()
}

function Save-Icon {
    param([int]$Size, [string]$Path, [bool]$Round, [double]$Coverage = 0.62)
    $canvas = New-Canvas -Size $Size -Round $Round
    Draw-Logo -Graphics $canvas.Graphics -Size $Size -Coverage $Coverage
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    $canvas.Bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $canvas.Graphics.Dispose()
    $canvas.Bitmap.Dispose()
}

$densities = @{
    "mdpi"    = 48
    "hdpi"    = 72
    "xhdpi"   = 96
    "xxhdpi"  = 144
    "xxxhdpi" = 192
}
foreach ($name in $densities.Keys) {
    $size = $densities[$name]
    $dir = Join-Path $res "mipmap-$name"
    Save-Icon -Size $size -Path (Join-Path $dir "ic_launcher.png") -Round $false
    Save-Icon -Size $size -Path (Join-Path $dir "ic_launcher_round.png") -Round $true
}

# Transparent brand mark for the setup screen and toolbar.
$logoPath = Join-Path $res "drawable-nodpi\logo.png"
$logoDir = Split-Path -Parent $logoPath
if (-not (Test-Path $logoDir)) { New-Item -ItemType Directory -Path $logoDir | Out-Null }
$bmp = New-Object System.Drawing.Bitmap(512, 512, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
$g.Clear([System.Drawing.Color]::Transparent)
Draw-Logo -Graphics $g -Size 512 -Coverage 0.92
$bmp.Save($logoPath, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()

Write-Host "Icons written to $res"
