<#
.SYNOPSIS
    一条命令跑完项目的全部自动化检查（本地 CI）

.DESCRIPTION
    为什么需要它：本项目的 CI 文件是 GitHub Actions（.github/workflows/ci.yml），
    而实际托管在 Gitee 上——Gitee 不会执行 GitHub Actions。在配好 Gitee Go 之前，
    这个脚本提供等价能力：把流水线里的六套检查按同样顺序在本地跑一遍。

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

# 1) Java：224 用例，离线、不需要 MySQL/Redis
Invoke-Check -Name 'java' -WorkDir 'fact-check' -Action { mvn @mvnArgs }

# 2) Python：111 用例，标准库 unittest，零第三方依赖
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
if (Get-Command docker -ErrorAction SilentlyContinue) {
    Invoke-Check -Name 'compose' -WorkDir '.' -Action { docker compose config }
} else {
    Write-Skip 'compose（未安装 docker CLI）'
}

# 7) 密钥泄露扫描（与 CI 的 secret-scan 作业同一段正则）
Write-Head 'secret-scan'
$pattern = 'LTAI[A-Za-z0-9]{8,}|tvly-[A-Za-z0-9]{10,}|sk-[A-Za-z0-9]{20,}|AKID[A-Za-z0-9]{10,}'
Push-Location $root
try {
    $hits = git grep -I -n -E $pattern -- . 2>$null | Where-Object { $_ -notmatch 'sk-xxxx' }
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

$failed = ($results | Where-Object { -not $_.Ok }).Count
Write-Host ""
if ($failed -gt 0) {
    Write-Host "$failed 项检查未通过。" -ForegroundColor Red
    exit 1
}
Write-Host '全部检查通过。' -ForegroundColor Green
exit 0
