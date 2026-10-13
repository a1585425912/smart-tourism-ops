# 停止便携 PostgreSQL
$root = Split-Path -Parent $PSScriptRoot
& (Join-Path $root 'runtime\pgsql\bin\pg_ctl.exe') -D (Join-Path $root 'runtime\pgdata') stop -m fast
