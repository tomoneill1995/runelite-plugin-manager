<#
.SYNOPSIS
  Start the stock RuneLite install with the sideloader agent attached.
.DESCRIPTION
  Runs the installed launcher jar on RuneLite's bundled JRE, so the launcher still updates
  and hash-verifies the client as normal. -javaagent goes through the launcher's
  RUNELITE_VMARGS environment variable, so no launcher settings are changed.

  RuneLite.exe isn't used: starting it with the agent exited before the client ran. Its packr
  config sets runelite.launcher.nojvm, which probably rules out the JVM launch mode used here.

  Telemetry is disabled unless -Telemetry is given.
  Jagex accounts: this starts RuneLite directly, without the Jagex Launcher's session.
#>
param(
    [switch]$Telemetry
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$home_ = Join-Path $env:USERPROFILE '.rl-sideloader'
$built = Join-Path $root 'agent\build\libs\rl-sideloader-agent.jar'
$install = Join-Path $env:LOCALAPPDATA 'RuneLite'
$java = Join-Path $install 'jre\bin\javaw.exe'
$launcher = Join-Path $install 'RuneLite.jar'

if (-not (Test-Path $built)) { throw "Agent not built. Run: .\gradlew.bat build" }
New-Item -ItemType Directory -Force (Join-Path $home_ 'plugins') | Out-Null

# Run from a copy so the build output isn't locked while the client is open
$agent = Join-Path $home_ 'rl-sideloader-agent.jar'
Copy-Item $built $agent -Force

$env:RUNELITE_VMARGS = "-javaagent:$agent"
if (-not $Telemetry) { $env:RUNELITE_ARGS = '--disable-telemetry' }

Start-Process -FilePath $java -WorkingDirectory $install `
    -ArgumentList '-Xmx768m', '-Xss2m', '-cp', "`"$launcher`"", 'net.runelite.launcher.Launcher'
Write-Host "Started RuneLite with $agent"
