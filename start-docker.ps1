# ============================================================================
# FactCheck 容器化一键启动（Windows / PowerShell）
#
# 解决的问题：容器化部署需要两个 JWT 密钥，而 docker-compose.yml 里已改为
#   `${VAR:?...}` 强制要求 —— 不设置就直接报错。手工设置两个随机串很麻烦，
#   忘了设又会看到"登录返回未知错误"这种极难定位的现象（详见 docs/部署文档.md §11.6）。
#
# 用法：
#   .\start-docker.ps1            # 生成/复用密钥 → 构建镜像 → 起容器 → 健康检查
#   .\start-docker.ps1 check      # 只做环境自检，不做任何变更
#   .\start-docker.ps1 down       # 停止容器（保留数据卷）
#   .\start-docker.ps1 down -PurgeData   # 停止并删除数据卷（演示数据全丢）
#
# 设计要点：
#   1. **密钥不落盘**：只在当前进程内生成，脚本结束即失效。重新开会话再跑会生成新密钥
#      （旧 token 失效，但账号密码不受影响，重新登录即可）。
#   2. 已存在的密钥会被**复用**，不会每次覆盖（避免旧 token 突然失效）。
#   3. 自动探测 80 端口是否可用：被占（例如深信服 aTrust 之类的零信任客户端）则自动
#      改用 8081，并在结尾提示用哪个地址访问。
#   4. 有 docker-compose.offline.yml 时优先使用它 —— 那份会让 server 直接用本地镜像，
#      不触发容器内 Maven 构建（网络受限环境的推荐路径，见 docs/部署文档.md §11.4）。
# ============================================================================

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('up', 'check', 'down')]
    [string]$Action = 'up',

    # down 时是否连数据卷一起删（会清空 MySQL/Redis/Agent 数据，不可恢复）
    [switch]$PurgeData,

    # 强制重新生成 JWT 密钥（默认复用已存在的）
    [switch]$NewSecrets,

    # up 时强制重建容器（换了密钥后必须重建才会注入新值）
    [switch]$Recreate,

    # 健康检查等待上限（秒）
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location -LiteralPath $root

# ---------------------------------------------------------------- 输出辅助
function Write-Step([string]$m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }
function Write-Ok([string]$m)   { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Write-Warn2([string]$m){ Write-Host "  [警告] $m" -ForegroundColor Yellow }
function Write-Err([string]$m)  { Write-Host "  [错误] $m" -ForegroundColor Red }

# 项目里所有 compose 命令都要用的文件组合
$composeBase = 'docker-compose.yml'
$composeOffline = 'docker-compose.offline.yml'
$composeFiles = @('-f', $composeBase)
if (Test-Path -LiteralPath (Join-Path $root $composeOffline)) {
    $composeFiles += @('-f', $composeOffline)
}

# 统一的外部命令调用封装，返回 @{ Code; Out }
#
# 为什么要封装（两个 PowerShell 陷阱，这里都踩过）：
#   1. 本脚本设了 $ErrorActionPreference='Stop'，而 docker 把正常的错误信息写到 **stderr**
#      （例如"引擎不可达"）。PS 5.1 会把它包成 NativeCommandError 并**直接终止脚本**，
#      自检就没法优雅地报告问题。所以在调用期间临时切成 'Continue'。
#   2. $LASTEXITCODE 一旦接管道（如 `| Select-Object`）就会被重置为 -1。
#      所以必须**先执行、立刻取退出码**，再对输出做后续处理。
function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$Exe,
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments
    )
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $Exe @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    return @{ Code = $code; Out = @($out) }
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    # compose 输出直接透传给用户（保留实时输出），同样要避免 stderr 触发 Stop
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & docker compose @composeFiles @Args
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
}

# ---------------------------------------------------------------- 密钥
function Initialize-Secrets {
    Write-Step 'JWT 密钥'

    $needUser  = $NewSecrets -or [string]::IsNullOrWhiteSpace($env:FACTCHECK_JWT_USER_SECRET)
    $needAdmin = $NewSecrets -or [string]::IsNullOrWhiteSpace($env:FACTCHECK_JWT_ADMIN_SECRET)

    if ($needUser) {
        $env:FACTCHECK_JWT_USER_SECRET = -join ((1..64) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
        Write-Ok "已生成用户端密钥（64 字符）"
    } else {
        Write-Ok "复用已存在的用户端密钥"
    }
    if ($needAdmin) {
        $env:FACTCHECK_JWT_ADMIN_SECRET = -join ((1..64) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
        Write-Ok "已生成管理端密钥（64 字符）"
    } else {
        Write-Ok "复用已存在的管理端密钥"
    }

    Write-Host '  （密钥只存在于当前终端会话，不会写入任何文件）' -ForegroundColor DarkGray
}

# 检测"运行中的容器里的密钥"与"当前 shell 的密钥"是否一致
#
# 为什么需要：密钥是**容器创建时**注入的环境变量。若换了密钥却没重建容器，
# server 仍用旧密钥签发 token，而新开的终端/其他客户端可能拿另一份 —— 表现为
# token 校验莫名其妙失败。这里提前发现并明确提示，而不是让人去猜。
function Test-SecretDrift {
    $live = Invoke-Native docker inspect fc-server --format '{{range .Config.Env}}{{println .}}{{end}}'
    if ($live.Code -ne 0) { return }   # 没有运行中的容器 → 无需检查

    $liveUser = ($live.Out | Where-Object { $_ -like 'FACTCHECK_JWT_USER_SECRET=*' }) -replace '^FACTCHECK_JWT_USER_SECRET=', ''
    $liveAdmin = ($live.Out | Where-Object { $_ -like 'FACTCHECK_JWT_ADMIN_SECRET=*' }) -replace '^FACTCHECK_JWT_ADMIN_SECRET=', ''

    $drift = $false
    if ($liveUser -and $liveUser -ne $env:FACTCHECK_JWT_USER_SECRET) { $drift = $true }
    if ($liveAdmin -and $liveAdmin -ne $env:FACTCHECK_JWT_ADMIN_SECRET) { $drift = $true }

    if ($drift) {
        Write-Warn2 '检测到密钥不一致：运行中的 fc-server 用的还是**上一份**密钥'
        Write-Host '         影响：改用新密钥后，旧 token 会失效（账号密码不受影响）。' -ForegroundColor DarkGray
        Write-Host '         处理：执行  .\start-docker.ps1 up -Recreate  重建容器以注入新密钥；' -ForegroundColor DarkGray
    } else {
        Write-Ok '运行中的容器与当前 shell 密钥一致'
    }
}

# ---------------------------------------------------------------- 环境自检
function Test-Environment {
    Write-Step '环境自检'
    $problems = @()

    # docker CLI
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($docker) { Write-Ok "docker: $($docker.Source)" }
    else { Write-Err 'docker 未找到'; $problems += 'docker'; return $problems }

    # compose 子命令（常见坑：插件不在 CLI 搜索路径，报 unknown shorthand flag）
    $r = Invoke-Native docker compose version
    if ($r.Code -eq 0) {
        Write-Ok "docker compose: $($r.Out | Select-Object -First 1)"
    } else {
        Write-Err 'docker compose 子命令不可用（报 unknown shorthand flag: ''d'' 之类）'
        Write-Host '         修法见 docs/部署文档.md §3.1.1（cliPluginsExtraDirs）；' -ForegroundColor DarkGray
        Write-Host '         应急可用独立二进制：docker-compose（参数完全一致）' -ForegroundColor DarkGray
        $problems += 'compose'
    }

    # 引擎
    $r = Invoke-Native docker info --format '{{.ServerVersion}}'
    if ($r.Code -eq 0) {
        Write-Ok "Docker 引擎: $($r.Out | Select-Object -First 1)"
    } else {
        Write-Err 'Docker 引擎不可达 —— 请先启动 Docker Desktop 并等托盘图标变绿'
        $r.Out | Select-Object -First 2 | ForEach-Object { Write-Host "         $_" -ForegroundColor DarkGray }
        $problems += 'engine'
    }

    # nginx 端口：80 常被零信任/VPN 客户端占用（如深信服 aTrust）
    if (-not $env:NGINX_PORT) {
        $port80Busy = Get-NetTCPConnection -LocalPort 80 -State Listen -ErrorAction SilentlyContinue
        if ($port80Busy) {
            $env:NGINX_PORT = '8081'
            Write-Warn2 "80 端口已被占用（pid $($port80Busy[0].OwningProcess)）→ 自动改用 8081"
            Write-Host '         占用者常见于零信任/VPN 客户端，见 docs/部署文档.md §11.2' -ForegroundColor DarkGray
        } else {
            $env:NGINX_PORT = '80'
            Write-Ok '端口 80 可用'
        }
    } else {
        Write-Ok "沿用已设置的 NGINX_PORT=$($env:NGINX_PORT)"
    }

    # 其它映射端口
    foreach ($p in @(8080, 3306, 6379, 8001)) {
        $busy = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
        if ($busy) {
            $proc = (Get-Process -Id $busy[0].OwningProcess -ErrorAction SilentlyContinue).ProcessName
            Write-Warn2 "端口 $p 已被占用（$proc）—— 若是本项目容器则正常，否则需改端口"
        }
    }

    # 前端产物（compose 只挂载 dist，不在容器里构建）
    if (Test-Path -LiteralPath (Join-Path $root 'fact-check-web\dist\index.html')) {
        Write-Ok '前端产物 fact-check-web/dist 已存在'
    } else {
        Write-Warn2 '前端产物不存在：先在 fact-check-web 里 npm run build，否则页面会 404'
    }

    # 编排文件能否解析（JWT 密钥是 ${VAR:?} 强制项，所以本段必须在 Initialize-Secrets 之后跑）
    if ($problems -notcontains 'compose' -and $problems -notcontains 'engine') {
        $cfg = Invoke-Compose config --quiet
        if ($cfg -eq 0) {
            Write-Ok '编排文件解析通过（docker compose config）'
        } else {
            Write-Err '编排文件解析失败 —— 最常见原因是 JWT 密钥未设置（compose 里是 ${VAR:?} 强制项）'
            $problems += 'compose-config'
        }
    }

    return $problems
}

# ---------------------------------------------------------------- 等待健康
function Wait-Healthy {
    Write-Step "等待服务就绪（上限 $TimeoutSeconds 秒）"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-Compose ps --format '{{.Name}}|{{.Status}}'
        $ps = $r.Out
        if ($ps) {
            $rows = $ps | Where-Object { $_ -match '\|' }
            $total = $rows.Count
            $ok = ($rows | Where-Object { $_ -match '\(healthy\)' -or $_ -match '\bUp\b' }).Count
            $unhealthy = ($rows | Where-Object { $_ -match 'unhealthy|Restarting|Exited' }).Count
            Write-Host ("`r  就绪 {0}/{1}（异常 {2}）      " -f $ok, $total, $unhealthy) -NoNewline
            if ($unhealthy -eq 0 -and $total -ge 5 -and $ok -ge 5) {
                Write-Host ''
                Write-Ok '五个服务全部就绪'
                return $true
            }
        }
        Start-Sleep -Seconds 3
    }
    Write-Host ''
    Write-Warn2 "等待超时 —— 请执行 docker compose ps 与 docker compose logs <服务名> 查看"
    return $false
}

# ---------------------------------------------------------------- 验收
function Invoke-Acceptance {
    param([string]$BaseUrl)

    Write-Step '冒烟验收'

    function Get-Code([string]$url, [string]$method = 'GET', [string]$body = $null, [string]$headerName = $null, [string]$headerValue = $null) {
        try {
            $h = @{}
            if ($headerName) { $h[$headerName] = $headerValue }
            $args = @{ Uri = $url; Method = $method; TimeoutSec = 15; SkipHttpErrorCheck = $true; Headers = $h }
            if ($body) { $args['ContentType'] = 'application/json'; $args['Body'] = $body }
            $r = Invoke-WebRequest @args
            return @{ Code = [int]$r.StatusCode; Body = $r.Content }
        } catch {
            return @{ Code = -1; Body = $_.Exception.Message }
        }
    }

    $r = Get-Code "$BaseUrl/api/doc.html"
    if ($r.Code -eq 200) { Write-Ok "接口文档 /api/doc.html → 200" } else { Write-Warn2 "接口文档 → $($r.Code)" }

    $r = Get-Code "$BaseUrl/"
    if ($r.Code -eq 200) { Write-Ok "前端首页 / → 200" } else { Write-Warn2 "前端首页 → $($r.Code)（dist 是否存在？）" }

    $r = Get-Code "$BaseUrl/api/user/login" 'POST' '{"username":"admin","password":"123456"}'
    $loginOk = $false
    if ($r.Code -eq 200 -and $r.Body -match '"code"\s*:\s*1') {
        Write-Ok '管理员登录 → code=1（已签发 token）'
        $loginOk = $true
    } elseif ($r.Body -match '未知错误') {
        Write-Err '登录返回「未知错误」：多半是 JWT 密钥为空，见 docs/部署文档.md §11.6'
    } else {
        Write-Warn2 "登录 → HTTP $($r.Code) $($r.Body)"
    }

    # 管理端鉴权：无 token 必须 401
    $r = Get-Code "$BaseUrl/api/admin/stats/overview"
    if ($r.Code -eq 401) { Write-Ok '管理端无 token → 401（鉴权生效）' }
    else { Write-Warn2 "管理端无 token → $($r.Code)（期望 401）" }

    # 有 token 必须 200
    if ($loginOk) {
        $m = [regex]::Match($r.Body, '"token"\s*:\s*"([^"]+)"')
        $login = Get-Code "$BaseUrl/api/user/login" 'POST' '{"username":"admin","password":"123456"}'
        $tm = [regex]::Match($login.Body, '"token"\s*:\s*"([^"]+)"')
        if ($tm.Success) {
            $r2 = Get-Code "$BaseUrl/api/admin/stats/overview" 'GET' $null 'token' $tm.Groups[1].Value
            if ($r2.Code -eq 200) { Write-Ok '管理端带 token → 200' }
            else { Write-Warn2 "管理端带 token → $($r2.Code)" }
        }
    }
}

# ---------------------------------------------------------------- 主流程
switch ($Action) {

    'check' {
        # 顺序有意：先生成/复用密钥，Test-Environment 结尾的 compose config 校验才能通过
        Initialize-Secrets
        $problems = Test-Environment
        Write-Host ''
        if ($problems.Count -eq 0) {
            Write-Ok '自检通过，可以执行 .\start-docker.ps1'
        } else {
            Write-Err "自检发现 $($problems.Count) 个问题：$($problems -join ', ')"
            exit 1
        }
    }

    'up' {
        Initialize-Secrets
        $problems = Test-Environment
        if ($problems -contains 'docker' -or $problems -contains 'engine' -or $problems -contains 'compose' -or $problems -contains 'compose-config') {
            Write-Err '环境不满足，先解决上面的问题'
            exit 1
        }

        # server 镜像不存在时先构建（离线路线：本机 jar → 镜像，零网络）
        $img = Invoke-Native docker images -q fact-check-server:latest
        $hasServerImage = ($img.Out | Where-Object { $_ }) -join ''
        if (-not $hasServerImage) {
            Write-Step '构建 server 镜像'
            if (Test-Path -LiteralPath (Join-Path $root 'fact-check\Dockerfile.server.local')) {
                $jar = Get-ChildItem -Path (Join-Path $root 'fact-check\fact-check-server\target') -Filter 'fact-check-server-*.jar' -ErrorAction SilentlyContinue |
                       Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
                if (-not $jar) {
                    Write-Warn2 '本机 jar 不存在，先离线打包'
                    Push-Location (Join-Path $root 'fact-check')
                    & mvn -o clean package -DskipTests
                    Pop-Location
                }
                Write-Host '  使用离线 Dockerfile（只拷 jar，不联网跑 Maven）...' -ForegroundColor DarkGray
                $b = Invoke-Native docker build -f fact-check/Dockerfile.server.local -t fact-check-server:latest ./fact-check
                $b.Out | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
                if ($b.Code -ne 0) { Write-Err '镜像构建失败'; exit 1 }
                Write-Ok 'server 镜像已构建'
            } else {
                Write-Warn2 '未找到离线 Dockerfile，将走容器内 Maven 构建（首次需要联网，可能较慢）'
            }
        } else {
            Write-Ok 'server 镜像已存在，跳过构建'
        }

        Write-Step '启动容器'
        if ($Recreate) {
            Write-Host '  -Recreate：先删除容器（保留数据卷），再按当前密钥重建' -ForegroundColor DarkGray
            $downCode = Invoke-Compose down
            if ($downCode -ne 0) { Write-Err '停止旧容器失败'; exit 1 }
        }
        $upCode = Invoke-Compose up -d
        if ($upCode -ne 0) { Write-Err '启动失败'; exit 1 }

        Test-SecretDrift
        $healthy = Wait-Healthy
        Invoke-Compose ps

        $url = "http://127.0.0.1:$($env:NGINX_PORT)"
        if ($healthy) { Invoke-Acceptance -BaseUrl $url }

        Write-Step '完成'
        Write-Host "  访问地址：$url" -ForegroundColor Green
        Write-Host '  提示：一律用 127.0.0.1，不要用 localhost —— 有的零信任/VPN 客户端会劫持 localhost' -ForegroundColor DarkGray
        Write-Host "  演示账号：admin / 123456（管理员）" -ForegroundColor DarkGray
        Write-Host "  查看日志：docker compose logs -f server" -ForegroundColor DarkGray
        Write-Host "  停止服务：.\start-docker.ps1 down" -ForegroundColor DarkGray
    }

    'down' {
        Write-Step '停止容器'
        if ($PurgeData) {
            Write-Warn2 '将同时删除数据卷（MySQL / Redis / Agent 数据全部丢失）'
            $confirm = Read-Host '  确认请输入 yes'
            if ($confirm -ne 'yes') { Write-Host '  已取消'; exit 0 }
            Invoke-Compose down -v
        } else {
            Invoke-Compose down
        }
        Write-Ok '已停止（数据卷保留）'
    }
}
