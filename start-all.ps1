<#
    重要：本文件必须保存为 **UTF-8 with BOM**（Windows PowerShell 5.1 对无 BOM 的 .ps1
    会按 ANSI/GBK 解析，脚本里的中文注释与提示串会被拆坏，直接导致语法错误无法运行）。
    若用编辑器改过本文件，请确认其仍带 BOM。
#>
<#
.SYNOPSIS
    事实检测平台一键启动 / 自检脚本（P4-47）

.DESCRIPTION
    把"新机器起项目"从查文档变成跑一条命令。三种用法：

      .\start-all.ps1 check     只做环境自检，不启动任何服务（推荐先跑这个）
      .\start-all.ps1 all       依次启动 Agent(8001) 与 Java(8080)，并等待就绪
      .\start-all.ps1 stop      停止由本脚本启动的进程（按 PID 文件）

    设计取舍（都是踩过的坑，写进脚本以免重犯）：
    1. 启动 Python **一律用 `python -m app.main`**，不要用 `.venv\Scripts\uvicorn.exe`
       —— venv 不可重定位（项目移动过目录），旧 launcher 会报
       "Fatal error in launcher: Unable to create process"。
    2. 判断 Java 是否就绪**要看日志里的 `Started FactCheckApplication`**，
       而不是"进程还在"——Spring 启动失败时进程可能仍在，只看进程会误报成功。
    3. nginx 在本机通常是手工安装的（`nginx-1.20.2`），脚本只做检查与提示；
       容器化部署请用 `docker compose up`（见 docker-compose.yml）。
    4. 启动子进程用 `-WindowStyle Hidden` 而不是 `-NoNewWindow`：后者会让子进程继承父进程的
       stdout 管道，导致调用方捕获脚本输出时一直不返回（服务不退、脚本就不结束）。
    5. MySQL / Redis 本机没起时脚本**不代启动**，只明确提示——它们是外部依赖，
       服务本身在连不上时会降级（限流/热度榜 fail-open），但检测落库会失败。
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('all', 'check', 'agent', 'server', 'stop')]
    [string]$Target = 'all',

    # 跳过 Maven 打包（默认不打包，直接用已有 jar；需要时用 -Build 显式打包）
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$logDir = Join-Path $root 'logs'
$pidDir = Join-Path $root 'logs'
$agentDir = Join-Path $root 'fact-agent'
$serverDir = Join-Path $root 'fact-check'
$venvPython = Join-Path $agentDir '.venv\Scripts\python.exe'
$serverJar = Join-Path $serverDir 'fact-check-server\target\fact-check-server-1.0-SNAPSHOT.jar'

function Write-Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Write-Ok($text) { Write-Host "  [OK]   $text" -ForegroundColor Green }
function Write-Warn2($text) { Write-Host "  [WARN] $text" -ForegroundColor Yellow }
function Write-Err($text) { Write-Host "  [ERR]  $text" -ForegroundColor Red }

function Test-PortOpen([int]$Port, [string]$Host_ = '127.0.0.1', [int]$TimeoutMs = 800) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync($Host_, $Port)
        return $task.Wait($TimeoutMs) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Test-HttpOk([string]$Url, [int]$TimeoutSec = 5) {
    try {
        $resp = Invoke-WebRequest -Uri $Url -TimeoutSec $TimeoutSec -UseBasicParsing
        return $resp.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Invoke-Check {
    Write-Step '环境自检'
    $problems = @()

    # --- 工具链 ---
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCmd) { Write-Ok "java: $($javaCmd.Source)" } else { Write-Err 'java 未找到（需要 JDK 11+）'; $problems += 'java' }

    $mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvnCmd) { Write-Ok "maven: $($mvnCmd.Source)" } else { Write-Warn2 'maven 未找到（只有需要重新打包时才用得上）' }

    if (Test-Path $venvPython) { Write-Ok "python venv: $venvPython" }
    else { Write-Err "python venv 不存在：$venvPython（先按 README 建虚拟环境）"; $problems += 'venv' }

    if (Test-Path $serverJar) { Write-Ok "后端 jar: $(Split-Path $serverJar -Leaf)" }
    else { Write-Warn2 "后端 jar 不存在，需要先打包：cd fact-check; mvn -o package -DskipTests" }

    # --- 外部依赖 ---
    if (Test-PortOpen 3306) { Write-Ok 'MySQL 3306 可连接' }
    else { Write-Warn2 'MySQL 3306 未连接：检测结果无法落库（服务仍能启动）'; }

    if (Test-PortOpen 6379) { Write-Ok 'Redis 6379 可连接' }
    else { Write-Warn2 'Redis 6379 未连接：限流与热度榜会走 fail-open（不阻断，但能力降级）' }

    # --- 端口占用 ---
    foreach ($p in @(8001, 8080)) {
        if (Test-PortOpen $p) { Write-Warn2 "端口 $p 已被占用（服务可能已在运行，或需要先停止）" }
        else { Write-Ok "端口 $p 空闲" }
    }

    # --- nginx ---
    $nginxExe = Join-Path $root 'nginx-1.20.2\nginx.exe'
    if (Test-Path $nginxExe) {
        if (Test-PortOpen 80) { Write-Ok 'nginx 80 端口在监听' }
        else { Write-Warn2 'nginx 已安装但未启动：cd nginx-1.20.2; .\nginx.exe' }
    } else {
        Write-Warn2 '未发现本机 nginx（可用 docker compose up 走容器版）'
    }

    # --- 关键配置 ---
    $agentEnv = Join-Path $agentDir '.env'
    if (Test-Path $agentEnv) {
        $token = (Select-String -Path $agentEnv -Pattern '^INTERNAL_TOKEN=(.*)$' -ErrorAction SilentlyContinue |
            Select-Object -First 1).Matches.Groups[1].Value.Trim()
        if ($token) { Write-Ok "Agent 内部令牌已配置（长度 $($token.Length)）" }
        else { Write-Warn2 'Agent .env 未配置 INTERNAL_TOKEN：Python 侧 fail-closed，Java 调用会全量 401' }
    } else {
        Write-Warn2 "未找到 $agentEnv（可从 .env.example 复制一份）"
    }

    if ($problems.Count) {
        Write-Host "`n自检未通过，缺失：$($problems -join ', ')" -ForegroundColor Red
        return $false
    }
    Write-Host "`n自检通过。" -ForegroundColor Green
    return $true
}

function Start-Agent {
    Write-Step '启动 Python Agent（8001）'
    if (Test-PortOpen 8001) { Write-Warn2 '8001 已在监听，跳过启动'; return $true }
    if (-not (Test-Path $venvPython)) { Write-Err 'venv 不存在，无法启动'; return $false }

    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $outLog = Join-Path $logDir 'agent.out.log'
    $errLog = Join-Path $logDir 'agent.err.log'
    # 关键：`python -m app.main`（见脚本头部说明 1）
    $proc = Start-Process -FilePath $venvPython -ArgumentList '-m', 'app.main' -WorkingDirectory $agentDir `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru -WindowStyle Hidden
    Set-Content -Path (Join-Path $pidDir 'agent.pid') -Value $proc.Id -Encoding ascii

    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 1
        if (Test-HttpOk 'http://127.0.0.1:8001/health') { Write-Ok "Agent 就绪（pid $($proc.Id)，日志 logs/agent.err.log）"; return $true }
        if ($proc.HasExited) { Write-Err "Agent 启动失败（退出码 $($proc.ExitCode)），看 logs/agent.err.log"; return $false }
    }
    Write-Err 'Agent 60 秒内未就绪，看 logs/agent.err.log'
    return $false
}

function Start-Server {
    Write-Step '启动 Java 后端（8080）'
    if (Test-PortOpen 8080) { Write-Warn2 '8080 已在监听，跳过启动'; return $true }
    if (-not (Test-Path $serverJar)) { Write-Err "jar 不存在：$serverJar（先执行 mvn -o package -DskipTests）"; return $false }

    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $outLog = Join-Path $logDir 'server.out.log'
    $errLog = Join-Path $logDir 'server.err.log'
    $proc = Start-Process -FilePath 'java' -ArgumentList '-jar', $serverJar -WorkingDirectory $serverDir `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru -WindowStyle Hidden
    Set-Content -Path (Join-Path $pidDir 'server.pid') -Value $proc.Id -Encoding ascii

    # 关键：看日志里的 Started FactCheckApplication，而不是"进程还在"（见脚本头部说明 2）
    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Seconds 1
        if (Test-Path $outLog) {
            $log = Get-Content $outLog -Raw -ErrorAction SilentlyContinue
            if ($log -match 'Started FactCheckApplication') { Write-Ok "后端就绪（pid $($proc.Id)，日志 logs/server.out.log）"; return $true }
            if ($log -match 'APPLICATION FAILED TO START') { Write-Err 'Spring 启动失败，看 logs/server.out.log 的 APPLICATION FAILED TO START 段'; return $false }
        }
        if ($proc.HasExited) { Write-Err "后端进程已退出（退出码 $($proc.ExitCode)），看 logs/server.out.log"; return $false }
    }
    Write-Err '后端 90 秒内未就绪，看 logs/server.out.log'
    return $false
}

function Stop-All {
    Write-Step '停止由本脚本启动的进程'
    foreach ($name in @('agent', 'server')) {
        $pidFile = Join-Path $pidDir "$name.pid"
        if (-not (Test-Path $pidFile)) { Write-Warn2 "$name 无 PID 文件，跳过"; continue }
        $procId = (Get-Content $pidFile -Raw).Trim()
        try {
            $proc = Get-Process -Id $procId -ErrorAction Stop
            Stop-Process -Id $proc.Id -Force
            Write-Ok "$name 已停止（pid $procId）"
        } catch {
            Write-Warn2 "$name 的进程 $procId 已不在运行"
        }
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    }
}

# --- 主流程 ---
Write-Host '事实检测平台 一键启动' -ForegroundColor White
Write-Host "项目根：$root"

switch ($Target) {
    'check' { Invoke-Check | Out-Null }
    'agent' { Invoke-Check | Out-Null; Start-Agent | Out-Null }
    'server' { Invoke-Check | Out-Null; Start-Server | Out-Null }
    'stop' { Stop-All }
    'all' {
        if (-not (Invoke-Check)) { Write-Host '自检未通过，已中止。' -ForegroundColor Red; exit 1 }
        if ($Build) {
            Write-Step '打包后端（mvn -o package -DskipTests）'
            Push-Location $serverDir
            try { & mvn -o package -DskipTests -B | Out-Null } finally { Pop-Location }
        }
        $agentOk = Start-Agent
        $serverOk = Start-Server
        Write-Host ''
        if ($agentOk -and $serverOk) {
            Write-Host '服务已就绪：' -ForegroundColor Green
            Write-Host '  Agent 健康检查   http://127.0.0.1:8001/health'
            Write-Host '  接口文档（knife4j）http://127.0.0.1:8080/api/doc.html'
            Write-Host '  前端开发服务器     cd fact-check-web; npm run dev  （或由 nginx 提供 dist）'
            Write-Host '  停止服务           .\start-all.ps1 stop'
        } else {
            Write-Host '启动过程中有失败项，见上面日志路径。' -ForegroundColor Red
            exit 1
        }
    }
}
