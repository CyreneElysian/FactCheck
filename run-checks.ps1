<#
.SYNOPSIS
    一条命令跑完项目的全部自动化检查（本地 CI）

.DESCRIPTION
    为什么需要它：本项目的 CI 文件是 GitHub Actions（.github/workflows/ci.yml），
    而实际托管在 Gitee 上——Gitee 不会执行 GitHub Actions。在配好 Gitee Go 之前，
    这个脚本提供等价能力：把流水线里的八套检查按同样顺序在本地跑一遍。

    用法：
      .\run-checks.ps1              跑全部检查
      .\run-checks.ps1 -Online      Maven 走在线仓库（默认 -o 离线模式）
      .\run-checks.ps1 -Only java,web   只跑指定检查

    退出码：全部通过为 0，任一失败为 1（可直接用于 CI 或提交前钩子）。

    注意：本文件必须保存为 UTF-8 with BOM —— Windows PowerShell 5.1 对无 BOM 的 .ps1
    按 ANSI/GBK 解析，中文会变乱码并直接导致语法错误。
#>
[CmdletBinding()]
param(
    [switch]$Online,
    [string[]]$Only = @()
)

$root = $PSScriptRoot

# -Only 的两种传参方式都要能用：
#   在 PowerShell 里：          .\run-checks.ps1 -Only mini,web-type
#   通过 powershell -File 传参：  powershell -File .\run-checks.ps1 -Only mini,web-type
# 后者会把 "mini,web-type" 当成**一个字符串**，因此这里统一按逗号再拆一次。
$OnlyList = @($Only | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$results = [System.Collections.ArrayList]::new()

function Write-Head($text) { Write-Host ""; Write-Host "=== $text ===" -ForegroundColor Cyan }
function Write-Pass($text) { Write-Host "  [PASS] $text" -ForegroundColor Green }
function Write-Fail($text) { Write-Host "  [FAIL] $text" -ForegroundColor Red }
function Write-Skip($text) { Write-Host "  [SKIP] $text" -ForegroundColor Yellow }

function Invoke-Check {
    param(
        [string]$Name,
        [string]$WorkDir,
        [scriptblock]$Action
    )

    if ($OnlyList.Count -gt 0 -and ($OnlyList -notcontains $Name)) {
        Write-Skip "$Name（未在 -Only 中指定）"
        return
    }

    Write-Head $Name
    $dir = Join-Path $root $WorkDir
    if (-not (Test-Path $dir)) {
        Write-Fail "目录不存在：$dir"
        [void]$results.Add([pscustomobject]@{ Check = $Name; Ok = $false; Detail = '目录不存在' })
        return
    }

    Push-Location $dir
    try {
        $output = & $Action 2>&1
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    # 从输出里挑一行摘要，便于一眼看出结论
    $summary = ($output | Select-String -Pattern 'Tests run:|^OK|Ran \d+ tests|结果：|RESULT:|built in|退出码|AssertionError' |
        Select-Object -Last 1)
    if (-not $summary) { $summary = ($output | Select-Object -Last 1) }
    $detail = if ($summary) { ($summary.Line -replace '\s+', ' ').Trim() }
             elseif ($code -eq 0) { '(无输出，退出码 0 即通过)' }
             else { '' }

    if ($code -eq 0) {
        Write-Pass $detail
        [void]$results.Add([pscustomobject]@{ Check = $Name; Ok = $true; Detail = $detail })
    } else {
        Write-Fail $detail
        Write-Host "  —— 最近 15 行输出 ——" -ForegroundColor DarkGray
        $output | Select-Object -Last 15 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
        [void]$results.Add([pscustomobject]@{ Check = $Name; Ok = $false; Detail = $detail })
    }
}

$mvnArgs = if ($Online) { @('-B', 'test') } else { @('-o', '-B', 'test') }
$mvnHint = if ($Online) { '（在线仓库）' } else { '（离线 -o）' }

Write-Host '事实检测平台 —— 本地全量检查' -ForegroundColor White
Write-Host "项目根：$root"
Write-Host "Maven 模式：$mvnHint"

# 1) Java：299 个用例（268 个测试方法，含 13 个参数化；离线、不需要 MySQL/Redis）
Invoke-Check -Name 'java' -WorkDir 'fact-check' -Action { mvn @mvnArgs }

# 2) Python：117 用例，标准库 unittest，零第三方依赖
Invoke-Check -Name 'python' -WorkDir 'fact-agent' -Action {
    & (Join-Path $PWD '.venv\Scripts\python.exe') -m unittest discover -s tests -t .
}

# 3) 小程序：静态自检 110 项（语法 / JSON / 页面齐套 / 跳转目标 / 事件绑定）
Invoke-Check -Name 'mini' -WorkDir 'fact-check-mini' -Action {
    & (Join-Path $root 'fact-agent\.venv\Scripts\python.exe') check_mini.py
}

# 4) 前端类型检查：vue-tsc（含模板类型检查），不需要额外依赖
Invoke-Check -Name 'web-type' -WorkDir 'fact-check-web' -Action { npm run type-check }

# 5) 前端单元测试：零依赖运行器跑 vitest 的同一批 spec
#    （装好 vitest 后把这里换成 npm run test:run 即可，两者跑的是同一批 spec）
Invoke-Check -Name 'web-test' -WorkDir 'fact-check-web' -Action { npm run test:offline }

# 6) Docker Compose 配置校验（不需要 daemon 与网络）
#
#    分两层，缺一不可：
#      a) `compose config` —— 语法、变量插值、必填项（${VAR:?}）
#      b) **语义交叉验证** —— compose 注入的环境变量名，必须真的被 application*.yml 引用
#
#    为什么需要 (b)：容器化时踩过一个坑 —— compose 里写的是
#    `FACTCHECK_DATASOURCE_URL`，而 application.yml 读的是 `${FACTCHECK_DB_HOST/PORT/NAME}`，
#    变量名对不上，`compose config` **依然返回 0**（它只关心语法），
#    到运行时才发现后端一直连 localhost。这一层专门拦这类"名字写错但不报错"的问题。
Write-Head 'compose'
if (Get-Command docker -ErrorAction SilentlyContinue) {
    # ${VAR:?} 是必填项（见 docker-compose.yml 注释：密钥为空会导致登录静默失败）。
    # 但**配置校验**不该要求用户先准备真实密钥，否则干净环境/CI 上这一项永远 FAIL。
    # 因此只在本次校验期间注入占位密钥，跑完立即还原。
    $savedUserSecret = $env:FACTCHECK_JWT_USER_SECRET
    $savedAdminSecret = $env:FACTCHECK_JWT_ADMIN_SECRET
    if (-not $env:FACTCHECK_JWT_USER_SECRET) { $env:FACTCHECK_JWT_USER_SECRET = 'x' * 64 }
    if (-not $env:FACTCHECK_JWT_ADMIN_SECRET) { $env:FACTCHECK_JWT_ADMIN_SECRET = 'x' * 64 }
    try {
        Invoke-Check -Name 'compose' -WorkDir '.' -Action { docker compose config --quiet }
    } finally {
        $env:FACTCHECK_JWT_USER_SECRET = $savedUserSecret
        $env:FACTCHECK_JWT_ADMIN_SECRET = $savedAdminSecret
    }
} else {
    Write-Skip 'compose（未安装 docker CLI）'
}

Write-Head 'compose-env'
$composeEnvOk = $true
$composeEnvDetail = ''
try {
    $composeFile = Join-Path $root 'docker-compose.yml'
    $appYml = Join-Path $root 'fact-check\fact-check-server\src\main\resources\application.yml'
    $devYml = Join-Path $root 'fact-check\fact-check-server\src\main\resources\application-dev.yml'

    if ((Test-Path $composeFile) -and (Test-Path $appYml)) {
        # 1. 收集 compose 里 server 服务注入的 FACTCHECK_* 变量名
        #    先剔除注释行：本文件的注释里为了说明历史缺陷，会提到 FACTCHECK_DATASOURCE_URL
        #    这类"错误示例"变量名，若不排除会被当成真的注入项（踩过）。
        $composeLines = @(Get-Content -LiteralPath $composeFile | Where-Object { $_ -notmatch '^\s*#' })
        $composeText = $composeLines -join "`n"
        $injected = [regex]::Matches($composeText, '(?m)^\s+(FACTCHECK_[A-Z0-9_]+)\s*:') |
            ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique

        # 2. 收集 Spring 配置里引用的占位符名与自定义占位（fact-check.* → FACTCHECK_*）
        $ymlText = ''
        foreach ($f in @($appYml, $devYml)) {
            if (Test-Path $f) { $ymlText += (Get-Content -LiteralPath $f -Raw) }
        }
        $referenced = [regex]::Matches($ymlText, '\$\{(FACTCHECK_[A-Z0-9_]+)') |
            ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique

        # 3. 例外白名单：compose 传给**其它服务**或仅供 compose 自身使用的变量
        $allowed = @(
            'FACTCHECK_DB_HOST', 'FACTCHECK_DB_PORT', 'FACTCHECK_DB_NAME',
            'FACTCHECK_DB_USERNAME', 'FACTCHECK_DB_PASSWORD'   # 由 application-dev.yml 引用
        )

        $unknown = @($injected | Where-Object { $referenced -notcontains $_ -and $allowed -notcontains $_ })

        # 4. 反向检查：FACTCHECK_DB_* 这类关键变量是否真被 dev 配置引用（防止改错名字）
        $dbKeysAllReferenced = $true
        foreach ($k in @('FACTCHECK_DB_HOST', 'FACTCHECK_DB_PORT', 'FACTCHECK_DB_NAME')) {
            if ($injected -contains $k -and $referenced -notcontains $k) { $dbKeysAllReferenced = $false }
        }

        if ($unknown.Count -gt 0) {
            $composeEnvOk = $false
            $composeEnvDetail = "compose 注入了配置里不存在的变量：$($unknown -join ', ')"
            Write-Fail $composeEnvDetail
            Write-Host '  —— 这类问题 compose config 不会报错，但运行时会连错地址/用错密钥' -ForegroundColor DarkGray
        } elseif (-not $dbKeysAllReferenced) {
            $composeEnvOk = $false
            $composeEnvDetail = 'FACTCHECK_DB_* 未被 application-dev.yml 引用（数据源变量名对不上）'
            Write-Fail $composeEnvDetail
        } else {
            $composeEnvDetail = "compose 注入 $($injected.Count) 个变量，均被配置引用"
            Write-Pass $composeEnvDetail
        }
    } else {
        Write-Skip 'compose-env（缺少 docker-compose.yml 或 application.yml）'
    }
} catch {
    $composeEnvOk = $false
    $composeEnvDetail = "校验异常：$($_.Exception.Message)"
    Write-Fail $composeEnvDetail
}
if ($OnlyList.Count -eq 0 -or $OnlyList -contains 'compose-env') {
    [void]$results.Add([pscustomobject]@{ Check = 'compose-env'; Ok = $composeEnvOk; Detail = $composeEnvDetail })
}

# 7) 密钥泄露扫描（与 CI 的 secret-scan 作业同一段正则）
Write-Head 'secret-scan'
$pattern = 'LTAI[A-Za-z0-9]{8,}|tvly-[A-Za-z0-9]{10,}|sk-[A-Za-z0-9]{20,}|AKID[A-Za-z0-9]{10,}'
Push-Location $root
try {
    $hits = @(git grep -I -n -E $pattern -- . 2>$null | Where-Object { $_ -notmatch 'sk-xxxx' })
} finally {
    Pop-Location
}
if ($hits) {
    Write-Fail '检出疑似真实密钥：'
    $hits | Select-Object -First 5 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
    [void]$results.Add([pscustomobject]@{ Check = 'secret-scan'; Ok = $false; Detail = '检出疑似密钥' })
} else {
    Write-Pass '未发现明显的密钥泄露特征'
    [void]$results.Add([pscustomobject]@{ Check = 'secret-scan'; Ok = $true; Detail = '干净' })
}

# --- 汇总 ---
Write-Host ""
Write-Host '=== 汇总 ===' -ForegroundColor Cyan
$results | ForEach-Object {
    $mark = if ($_.Ok) { 'PASS' } else { 'FAIL' }
    $color = if ($_.Ok) { 'Green' } else { 'Red' }
    Write-Host ("  {0,-14} {1,-5} {2}" -f $_.Check, $mark, $_.Detail) -ForegroundColor $color
}

$ARG_NOT_REGISTERED = $false
if ($OnlyList.Count -gt 0) {
    $names = @($results | ForEach-Object { $_.Check })
    $unknown = @($OnlyList | Where-Object { $names -notcontains $_ })
    if ($unknown.Count -gt 0) {
        Write-Host ""
        Write-Host ("!! -Only 中存在未注册的检查名：" + ($unknown -join ', ')) -ForegroundColor Red
        Write-Host ("   可用名称：" + ($names -join ', ')) -ForegroundColor DarkGray
        Write-Host "   （旧版本会静默跳过并报“全部检查通过”，这是一个假绿路径，已修正）" -ForegroundColor DarkGray
        $ARG_NOT_REGISTERED = $true
    }
}

# ⚠️ 必须用 @() 包裹：
#    PowerShell 5.1 **不会**给管道输出的"单个对象"自动加 .Count 属性
#    （PS 7 才会），因此 `(... | Where-Object).Count` 在只有 1 个结果时返回 $null，
#    于是 `$null -gt 0` 为 false —— 表现为"明明 FAIL 了却 exit 0"。
#    这个坑在本脚本里潜伏已久（$hits / $unknown / $failed 都是这个写法），
#    直到 compose-env 这项检查第一次产出"恰好 1 条失败"才暴露出来。
$failed = @($results | Where-Object { -not $_.Ok }).Count
Write-Host ""
if ($ARG_NOT_REGISTERED) {
    Write-Host "存在未注册的检查名，已判定为失败（避免把“静默跳过”当成“通过”）。" -ForegroundColor Red
    exit 1
}
if ($failed -gt 0) {
    Write-Host "$failed 项检查未通过。" -ForegroundColor Red
    exit 1
}
Write-Host '全部检查通过。' -ForegroundColor Green
exit 0
