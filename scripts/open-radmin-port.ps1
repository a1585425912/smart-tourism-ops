# 在管理员 PowerShell 中运行；允许同一 Radmin VPN 网络访问演示端口。
[CmdletBinding()]
param([ValidateRange(1024, 65535)][int]$Port = 0)
$ErrorActionPreference = 'Stop'
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw '请使用管理员 PowerShell 运行此脚本，以添加 Windows 防火墙规则。'
}
if ($Port -eq 0) {
    $Port = 18080
    $envFile = Join-Path (Split-Path $PSScriptRoot -Parent) '.env'
    if (Test-Path -LiteralPath $envFile) {
        $entry = Get-Content -LiteralPath $envFile | Where-Object { $_ -match '^RADMIN_PORT=\d+$' } | Select-Object -Last 1
        if ($entry) { $Port = [int]($entry.Split('=')[1]) }
    }
}
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'RADMIN_PORT 必须在 1024–65535 之间。' }
$adapter = Get-NetAdapter | Where-Object { $_.InterfaceDescription -like '*Radmin*' -or $_.Name -like '*Radmin*' } | Select-Object -First 1
if (-not $adapter) { throw '未找到 Radmin VPN 网卡，请先安装并启动 Radmin VPN。' }
$ruleName = "SnowWorld-Radmin-$Port"
if (Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue) {
    Remove-NetFirewallRule -Name $ruleName
}
New-NetFirewallRule -Name $ruleName -DisplayName "Snow World Radmin VPN TCP $Port" -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port -InterfaceAlias $adapter.Name -RemoteAddress '26.0.0.0/8' -Profile Any | Out-Null
Write-Host "已允许 Radmin VPN 网络访问 TCP $Port。"
