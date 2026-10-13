# snow_world 一键启动(Windows PowerShell)
# 用法: powershell -ExecutionPolicy Bypass -File scripts\start.ps1 [-Rebuild]
#  1. 启动 runtime\ 下的便携 PostgreSQL(端口 55432, 仅本机)
#  2. 首次或 -Rebuild 时构建前端(frontend\dist)与后端 jar
#  3. 以 dev 配置启动后端(端口 8080), 同时托管前端页面
param([switch]$Rebuild)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$pg = Join-Path $root 'runtime\pgsql\bin'
$data = Join-Path $root 'runtime\pgdata'

if (-not (Test-Path (Join-Path $pg 'pg_ctl.exe'))) {
    Write-Host '未找到 runtime\pgsql, 请先运行 scripts\setup-runtime.ps1' -ForegroundColor Red
    exit 1
}

& (Join-Path $pg 'pg_isready.exe') -h localhost -p 55432 -q
if ($LASTEXITCODE -ne 0) {
    Write-Host '启动 PostgreSQL ...'
    Start-Process -FilePath (Join-Path $pg 'pg_ctl.exe') -ArgumentList @('-D', "`"$data`"", '-l', "`"$root\runtime\pg.log`"", '-o', '"-p 55432"', 'start') -WindowStyle Hidden
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        & (Join-Path $pg 'pg_isready.exe') -h localhost -p 55432 -q
        if ($LASTEXITCODE -eq 0) { break }
    }
}

$jar = Join-Path $root 'backend\target\snow-world-backend-0.1.0.jar'
if ($Rebuild -or -not (Test-Path (Join-Path $root 'frontend\dist\index.html'))) {
    Write-Host '构建前端 ...'
    Push-Location (Join-Path $root 'frontend')
    if (-not (Test-Path 'node_modules')) { npm ci --no-audit --no-fund }
    npx vite build
    Pop-Location
}
if ($Rebuild -or -not (Test-Path $jar)) {
    Write-Host '构建后端 ...'
    Push-Location (Join-Path $root 'backend')
    mvn -q -B -DskipTests package
    Pop-Location
}

Write-Host ''
Write-Host '系统启动中: http://localhost:8080  (指挥大屏 /screen, 移动巡检 /m)' -ForegroundColor Cyan
Write-Host '演示账号见 README.md; 停止: Ctrl+C, 再运行 scripts\stop.ps1 关闭数据库' -ForegroundColor Cyan
Push-Location (Join-Path $root 'backend')
java -jar $jar --spring.profiles.active=dev
Pop-Location
