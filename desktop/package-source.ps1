$ErrorActionPreference = 'Stop'
$workspaceDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$artifactDirectory = Join-Path $workspaceDirectory 'artifacts'
New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null
$sourceFiles = @()
foreach ($sourceDirectory in @('app/src', 'desktop', 'docs', 'gradle', 'licenses')) {
    $sourceFiles += Get-ChildItem -LiteralPath (Join-Path $workspaceDirectory $sourceDirectory) -Recurse -File |
        Where-Object { $_.FullName.Substring($workspaceDirectory.Length + 1).Replace('\', '/') -notmatch '(^|/)(bin|obj|build|\.gradle)/' }
}
foreach ($sourceFile in @('.gitignore', 'README.md', 'TESTING.md', 'THIRD_PARTY_NOTICES.md',
    'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'app/build.gradle')) {
    $sourceFiles += Get-Item -LiteralPath (Join-Path $workspaceDirectory $sourceFile)
}
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sourceArchive = Join-Path $artifactDirectory 'Relay-2.1-source.zip'
if (Test-Path -LiteralPath $sourceArchive) { Remove-Item -LiteralPath $sourceArchive }
$archive = [IO.Compression.ZipFile]::Open($sourceArchive, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in ($sourceFiles | Sort-Object FullName -Unique)) {
        $full = [IO.Path]::GetFullPath($file.FullName)
        if (-not $full.StartsWith($workspaceDirectory + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'A source file resolved outside the workspace.'
        }
        $relative = $full.Substring($workspaceDirectory.Length + 1).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $full, $relative,
            [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose() }
Write-Output ('Source package: ' + $sourceArchive)
