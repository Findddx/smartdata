[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeManifest = Join-Path $projectRoot '.local\runtime.json'
if (-not (Test-Path -LiteralPath $runtimeManifest)) { Write-Host '没有找到由启动脚本登记的服务。'; exit 0 }
$manifest = Get-Content -Raw -LiteralPath $runtimeManifest | ConvertFrom-Json
if ($manifest.root -ne $projectRoot) { throw '启动记录不属于当前项目，未停止任何进程。' }
foreach ($entry in $manifest.processes) {
    $child = Get-Process -Id $entry.id -ErrorAction SilentlyContinue
    if ($child -and $child.StartTime.ToUniversalTime().Ticks -eq $entry.startTicks) {
        Stop-Process -Id $child.Id
        Write-Host "已停止本项目登记的服务进程 $($child.Id)。"
    }
}
Write-Host '工作空间已停止。来源、证据与协作记录仍保留在 .local/workspace。'
