param(
    [switch]$Publish,
    [switch]$SkipChecks,
    [string]$DotnetPath
)
$ErrorActionPreference = 'Stop'
$workspaceDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $DotnetPath) {
    $privateSdkPath = Join-Path $env:USERPROFILE '.codex\tools\dotnet10\dotnet.exe'
    $sdkCommand = Get-Command dotnet -ErrorAction SilentlyContinue
    if ($sdkCommand -and ((& $sdkCommand.Source --list-sdks) -match '^10\.').Count -gt 0) {
        $DotnetPath = $sdkCommand.Source
    } elseif (Test-Path -LiteralPath $privateSdkPath) {
        $DotnetPath = $privateSdkPath
    } else {
        throw 'Install the .NET 10 SDK, or pass -DotnetPath with the full path to its dotnet.exe.'
    }
}
function Invoke-Dotnet([string[]]$Arguments) {
    & $DotnetPath @Arguments
    if ($LASTEXITCODE -ne 0) { throw "dotnet failed with exit code $LASTEXITCODE" }
}
Push-Location -LiteralPath $PSScriptRoot
try {
    Invoke-Dotnet -Arguments @('build', 'Relay.Desktop/Relay.Desktop.csproj', '-c', 'Release')
    Invoke-Dotnet -Arguments @('build', 'Relay.Hub.Host/Relay.Hub.Host.csproj', '-c', 'Release')
    if (-not $SkipChecks) {
        Invoke-Dotnet -Arguments @('run', '--project', 'Relay.Hub.Tests/Relay.Hub.Tests.csproj', '-c', 'Release')
        Invoke-Dotnet -Arguments @('run', '--project', 'Relay.Desktop.Checks/Relay.Desktop.Checks.csproj', '-c', 'Release')
        Invoke-Dotnet -Arguments @('run', '--project', 'Relay.Interop.Checks/Relay.Interop.Checks.csproj', '-c', 'Release', '--', $workspaceDirectory)
    }
    if ($Publish) {
        $artifactDirectory = Join-Path $workspaceDirectory 'artifacts'
        Invoke-Dotnet -Arguments @('publish', 'Relay.Desktop/Relay.Desktop.csproj', '-c', 'Release', '-r', 'win-x64', '--self-contained', 'true',
            '-p:PublishSingleFile=true', '-p:IncludeNativeLibrariesForSelfExtract=true', '-p:DebugType=None', '-p:DebugSymbols=false',
            '-o', (Join-Path $artifactDirectory 'RelayDesktop'))
        Invoke-Dotnet -Arguments @('publish', 'Relay.Hub.Host/Relay.Hub.Host.csproj', '-c', 'Release', '-r', 'win-x64', '--self-contained', 'true',
            '-p:PublishSingleFile=true', '-p:IncludeNativeLibrariesForSelfExtract=true', '-p:DebugType=None', '-p:DebugSymbols=false',
            '-o', (Join-Path $artifactDirectory 'RelayHubHost'))
        Copy-Item -LiteralPath (Join-Path $workspaceDirectory 'docs/DESKTOP-SETUP.md') -Destination (Join-Path $artifactDirectory 'RelayDesktop/SETUP.md')
        Copy-Item -LiteralPath (Join-Path $workspaceDirectory 'docs/DESKTOP-SETUP.md') -Destination (Join-Path $artifactDirectory 'RelayHubHost/SETUP.md')
        Copy-Item -LiteralPath (Join-Path $workspaceDirectory 'docs/HUB-PROTOCOL.md') -Destination (Join-Path $artifactDirectory 'RelayHubHost/PROTOCOL.md')
        foreach ($packageDirectory in @('RelayDesktop', 'RelayHubHost')) {
            $licenseDirectory = Join-Path $artifactDirectory "$packageDirectory/licenses"
            New-Item -ItemType Directory -Force -Path $licenseDirectory | Out-Null
            Copy-Item -Path (Join-Path $workspaceDirectory 'licenses/Dotnet-*.txt') -Destination $licenseDirectory
            Copy-Item -LiteralPath (Join-Path $workspaceDirectory 'licenses/QRCoder-MIT.txt') -Destination $licenseDirectory
            Copy-Item -LiteralPath (Join-Path $workspaceDirectory 'licenses/NAudio-MIT.txt') -Destination $licenseDirectory
            Copy-Item -LiteralPath (Join-Path $workspaceDirectory 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $artifactDirectory "$packageDirectory/THIRD_PARTY_NOTICES.md")
            foreach ($guide in @('DESKTOP-ARCHITECTURE.md', 'DESKTOP-VALIDATION.md', 'HUB-PROTOCOL.md', 'DISPATCH-PROTOCOL.md')) {
                Copy-Item -LiteralPath (Join-Path $workspaceDirectory "docs/$guide") -Destination (Join-Path $artifactDirectory "$packageDirectory/$guide")
            }
        }
        Compress-Archive -Path (Join-Path $artifactDirectory 'RelayDesktop/*') -DestinationPath (Join-Path $artifactDirectory 'RelayDesktop-2.1-win-x64.zip') -Force
        Compress-Archive -Path (Join-Path $artifactDirectory 'RelayHubHost/*') -DestinationPath (Join-Path $artifactDirectory 'RelayHubHost-2.1-win-x64.zip') -Force
        & (Join-Path $PSScriptRoot 'package-source.ps1')
    }
} finally { Pop-Location }
