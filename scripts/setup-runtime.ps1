# 在 runtime\ 下准备便携运行环境(PostgreSQL 17 + PostGIS 3.6), 只需执行一次。
# 用法: powershell -ExecutionPolicy Bypass -File scripts\setup-runtime.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$rt = Join-Path $root 'runtime'
$dl = Join-Path $rt 'downloads'
New-Item -ItemType Directory -Force $dl | Out-Null

$pgZip = Join-Path $dl 'pg.zip'
$gisZip = Join-Path $dl 'postgis.zip'
if (-not (Test-Path $pgZip)) {
    Invoke-WebRequest 'https://get.enterprisedb.com/postgresql/postgresql-17.11-1-windows-x64-binaries.zip' -OutFile $pgZip
}
if (-not (Test-Path $gisZip)) {
    Invoke-WebRequest 'https://download.osgeo.org/postgis/windows/pg17/postgis-bundle-pg17-3.6.2x64.zip' -OutFile $gisZip
}
if (-not (Test-Path (Join-Path $rt 'pgsql\bin\postgres.exe'))) {
    Expand-Archive $pgZip -DestinationPath $rt -Force
    $gisDir = Join-Path $dl 'postgis_x'
    Expand-Archive $gisZip -DestinationPath $gisDir -Force
    $bundle = Get-ChildItem $gisDir -Directory | Select-Object -First 1
    foreach ($d in 'bin', 'lib', 'share') {
        Copy-Item (Join-Path $bundle.FullName "$d\*") (Join-Path $rt "pgsql\$d") -Recurse -Force
    }
    Remove-Item (Join-Path $rt 'pgsql\pgAdmin 4'), (Join-Path $rt 'pgsql\StackBuilder') -Recurse -Force -ErrorAction SilentlyContinue
}

$bin = Join-Path $rt 'pgsql\bin'
$data = Join-Path $rt 'pgdata'
if (-not (Test-Path (Join-Path $data 'PG_VERSION'))) {
    $pw = Join-Path $rt 'pw.tmp'
    Set-Content -Path $pw -Value 'snow_dev_pg' -NoNewline -Encoding ascii
    & (Join-Path $bin 'initdb.exe') -D $data -U postgres -E UTF8 --locale=C -A scram-sha-256 "--pwfile=$pw"
    Remove-Item $pw
}
Start-Process -FilePath (Join-Path $bin 'pg_ctl.exe') -ArgumentList @('-D', "`"$data`"", '-l', "`"$rt\pg.log`"", '-o', '"-p 55432"', 'start') -WindowStyle Hidden
Start-Sleep -Seconds 4
$env:PGPASSWORD = 'snow_dev_pg'
$psql = Join-Path $bin 'psql.exe'
& $psql -h localhost -p 55432 -U postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='snow'" | Out-String | ForEach-Object {
    if (-not $_.Trim()) { & $psql -h localhost -p 55432 -U postgres -c "CREATE ROLE snow LOGIN PASSWORD 'snow_dev'" }
}
foreach ($db in 'snow_world', 'snow_test') {
    $exists = (& $psql -h localhost -p 55432 -U postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$db'" | Out-String).Trim()
    if (-not $exists) {
        & $psql -h localhost -p 55432 -U postgres -c "CREATE DATABASE $db OWNER snow"
        & $psql -h localhost -p 55432 -U postgres -d $db -c 'CREATE EXTENSION postgis'
    }
}
Write-Host '运行环境就绪: PostgreSQL 55432, 数据库 snow_world / snow_test' -ForegroundColor Green
