param(
    [int]$Port = 8765,
    [string]$Serial,
    [string]$Adb,
    [string]$Package = 'ai.opencyvis.standard',
    [switch]$AutoSync
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $viewerArgs = @('-m', 'tools.trace_viewer', '--port', $Port, '--package', $Package)
    # Load existing exports without copying or changing them.
    $legacyRecords = Join-Path $projectRoot '测试log日志'
    if (Test-Path -LiteralPath $legacyRecords) { $viewerArgs += @('--records', $legacyRecords) }
    if ($Serial) { $viewerArgs += @('--serial', $Serial) }
    if ($Adb) { $viewerArgs += @('--adb', $Adb) }
    if ($AutoSync) { $viewerArgs += '--auto-sync' }
    & python @viewerArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
