param(
  [string]$SourceUrl = "https://raw.githubusercontent.com/gabomdq/SDL_GameControllerDB/master/gamecontrollerdb.txt"
)

$ErrorActionPreference = "Stop"

function Write-DbFile([string]$Path, [string]$Content) {
  $dir = Split-Path -Parent $Path
  if ($dir -and !(Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

  # SDL's parser is happiest with UTF-8 without BOM.
  $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
  $bytes = $utf8NoBom.GetByteCount($Content)
  Write-Host ("Wrote {0} ({1} bytes)" -f $Path, $bytes)
}

Write-Host ("Downloading SDL_GameControllerDB from {0}..." -f $SourceUrl)
$headers = @{ "User-Agent" = "Super3-update-gamecontrollerdb" }
$irm = Get-Command Invoke-RestMethod -ErrorAction Stop
if ($irm.Parameters.ContainsKey("UseBasicParsing")) {
  $db = Invoke-RestMethod -UseBasicParsing -Uri $SourceUrl -Headers $headers
} else {
  $db = Invoke-RestMethod -Uri $SourceUrl -Headers $headers
}
$db = [string]$db
if (-not $db -or $db.Length -lt 1000) {
  throw "Downloaded database looks too small; aborting."
}

$stamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$header = @(
  "# Super3 snapshot of SDL_GameControllerDB"
  ("# Source: {0}" -f $SourceUrl)
  ("# Updated: {0}" -f $stamp)
  ""
) -join "`n"

$content = $header + $db.TrimEnd() + "`n"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Write-DbFile (Join-Path $repoRoot "Config/gamecontrollerdb.txt") $content
Write-DbFile (Join-Path $repoRoot "android/app/src/main/assets/Config/gamecontrollerdb.txt") $content

Write-Host "Done."
