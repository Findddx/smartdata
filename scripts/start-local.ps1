[CmdletBinding()]
param([int]$ApiPort = 8080, [int]$ConsolePort = 5173, [switch]$SkipBuild, [switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localRoot = Join-Path $projectRoot '.local'
$runtimeManifest = Join-Path $localRoot 'runtime.json'
$startedProcesses = @()

function Assert-FreePort([int]$PortNumber) {
    if ($PortNumber -lt 1024 -or $PortNumber -gt 65535) { throw '端口必须在 1024 到 65535 之间。' }
    if (Get-NetTCPConnection -State Listen -LocalPort $PortNumber -ErrorAction SilentlyContinue) {
        throw "端口 $PortNumber 已被占用。请先关闭已启动的实例，或指定其他 -ApiPort / -ConsolePort。"
    }
}
function Wait-ForEndpoint([string]$Address, [Diagnostics.Process]$Child) {
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        if ($Child.HasExited) { throw "服务提前退出，请查看 $localRoot 中的日志。" }
        try { $response = Invoke-WebRequest -Uri $Address -TimeoutSec 2; if ($response.StatusCode -eq 200) { return } } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "服务尚未就绪：$Address。请查看 $localRoot 中的日志。"
}
try {
    Assert-FreePort $ApiPort
    Assert-FreePort $ConsolePort
    if ($ApiPort -eq $ConsolePort) { throw 'API 与工作台必须使用不同端口。' }
    $javaCommand = (Get-Command java -ErrorAction Stop).Source
    $nodeCommand = (Get-Command node -ErrorAction Stop).Source
    $npmCommand = (Get-Command npm.cmd -ErrorAction Stop).Source
    $nodeMajor = [int]((& $nodeCommand --version).TrimStart('v').Split('.')[0])
    if ($nodeMajor -lt 24) { throw '请安装 Node.js 24 或更高版本，再运行本脚本。' }
    New-Item -ItemType Directory -Path $localRoot -Force | Out-Null
    if (-not $SkipBuild) {
        Write-Host '正在准备服务。首次构建会下载已锁定的依赖，请稍候。'
        Push-Location $projectRoot
        try {
            & (Join-Path $projectRoot 'gradlew.bat') --no-daemon --no-parallel --max-workers=1 :runtime-api:bootJar
            if ($LASTEXITCODE -ne 0) { throw '服务构建失败。请确认 JDK 25 和网络连接可用。' }
        } finally { Pop-Location }
        Push-Location (Join-Path $projectRoot 'console')
        try {
            & $npmCommand ci --no-fund
            if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败，请检查 npm 网络连接。' }
        } finally { Pop-Location }
    }
    $runtimeJar = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'runtime-api\build\libs') -Filter '*.jar' |
        Where-Object { $_.Name -notlike '*-plain.jar' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $runtimeJar) { throw '没有找到服务文件，请去掉 -SkipBuild 后重试。' }
    $viteScript = Join-Path $projectRoot 'console\node_modules\vite\bin\vite.js'
    if (-not (Test-Path -LiteralPath $viteScript)) { throw '没有找到工作台依赖，请去掉 -SkipBuild 后重试。' }
    $api = Start-Process -FilePath $javaCommand -WindowStyle Hidden -WorkingDirectory $projectRoot -PassThru `
        -ArgumentList @('-Xmx768m', '-jar', ('"{0}"' -f $runtimeJar.FullName), '--spring.profiles.active=local', '--server.address=127.0.0.1', "--server.port=$ApiPort") `
        -RedirectStandardOutput (Join-Path $localRoot 'api.log') -RedirectStandardError (Join-Path $localRoot 'api-error.log')
    $startedProcesses += $api
    Wait-ForEndpoint "http://127.0.0.1:$ApiPort/actuator/health/readiness" $api
    $previousApiOrigin = $env:SMARTDATA_API_ORIGIN
    try {
        $env:SMARTDATA_API_ORIGIN = "http://127.0.0.1:$ApiPort"
        $console = Start-Process -FilePath $nodeCommand -WindowStyle Hidden -WorkingDirectory (Join-Path $projectRoot 'console') -PassThru `
            -ArgumentList @(('"{0}"' -f $viteScript), '--host', '127.0.0.1', '--port', "$ConsolePort", '--strictPort') `
            -RedirectStandardOutput (Join-Path $localRoot 'console.log') -RedirectStandardError (Join-Path $localRoot 'console-error.log')
    } finally { $env:SMARTDATA_API_ORIGIN = $previousApiOrigin }
    $startedProcesses += $console
    $consoleAddress = "http://127.0.0.1:$ConsolePort"
    Wait-ForEndpoint $consoleAddress $console
    $manifest = @{ root = $projectRoot; url = $consoleAddress; processes = @($startedProcesses | ForEach-Object { @{ id = $_.Id; startTicks = $_.StartTime.ToUniversalTime().Ticks } }) }
    $manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $runtimeManifest -Encoding utf8
    Write-Host "工作空间已启动：$consoleAddress"
    Write-Host '首次使用可点击“先体验电商示例”，或连接自己的脱敏数据。'
    Write-Host '停止服务：.\scripts\stop-local.ps1。数据与日志保存在 .local，不会随 Git 上传。'
    if (-not $NoBrowser) { Start-Process $consoleAddress }
} catch {
    foreach ($child in $startedProcesses) { if (-not $child.HasExited) { Stop-Process -Id $child.Id -ErrorAction SilentlyContinue } }
    throw
}
