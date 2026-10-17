<#
.SYNOPSIS
  Send a command to the sideloader agent running inside RuneLite.
.EXAMPLE
  .\rlctl.ps1 list
  .\rlctl.ps1 load C:\path\to\plugin.jar
  .\rlctl.ps1 reload hello-sideload
  .\rlctl.ps1 unload hello-sideload
#>
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Command,
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Arguments
)

$ErrorActionPreference = 'Stop'
$infoFile = Join-Path $env:USERPROFILE '.rl-sideloader\agent.json'
if (-not (Test-Path $infoFile)) { throw "Agent not running ($infoFile not found). Start RuneLite with tools\launch.ps1." }
$info = Get-Content $infoFile -Raw | ConvertFrom-Json

$client = New-Object System.Net.Sockets.TcpClient
try {
    $client.Connect('127.0.0.1', [int]$info.port)
    $stream = $client.GetStream()
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $reader = New-Object System.IO.StreamReader($stream, $utf8)
    $writer = New-Object System.IO.StreamWriter($stream, $utf8)
    $writer.AutoFlush = $true

    $writer.WriteLine("AUTH $($info.token)")
    $auth = $reader.ReadLine()
    if ($auth -ne 'OK') { throw "Auth failed: $auth" }

    $line = (@($Command.ToUpper()) + $Arguments) -join ' '
    $writer.WriteLine($line.Trim())
    $reply = $reader.ReadLine()

    if ($reply -like 'ERR*') { Write-Error $reply; exit 1 }
    $payload = $reply.Substring(2).Trim()
    if ($payload) { $payload } else { 'OK' }
}
finally {
    $client.Close()
}
