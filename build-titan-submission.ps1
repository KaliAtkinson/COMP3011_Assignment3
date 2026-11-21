# Build COMP3011 Assignment 3 for TITAN's root-level JavaFX Maven launcher.
# Repository layout: pom.xml and src/ at root; Assignment1/ is the server project.
# Only allowlisted source/documentation and one freshly built server JAR are packaged.
[CmdletBinding()]
param(
    [string]$OutputZip
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$server = Join-Path $repo 'Assignment1'

if (-not (Test-Path -LiteralPath (Join-Path $repo 'pom.xml')) -or
    -not (Test-Path -LiteralPath (Join-Path $repo 'src/main/java/comp3011/VideoPlayerApp.java'))) {
    throw 'Expected the JavaFX pom.xml and src/ at the repository root.'
}
if (-not (Test-Path -LiteralPath (Join-Path $server 'pom.xml'))) {
    throw 'Assignment1/pom.xml is missing from this repository.'
}

if ([string]::IsNullOrWhiteSpace($OutputZip)) {
    # Keep the submission outside the Git repository by default.
    $OutputZip = Join-Path (Split-Path -Parent $repo) 'COMP3011_TITAN_REBUILT.zip'
}
$OutputZip = [System.IO.Path]::GetFullPath($OutputZip)
$outputDirectory = Split-Path -Parent $OutputZip

$maven = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
if ($null -eq $maven) {
    $maven = Get-Command 'mvn' -ErrorAction Stop
}

function Invoke-Verify([string]$pom) {
    Write-Host "Running tests: $pom" -ForegroundColor Cyan
    & $maven.Source '-f' $pom 'clean' 'verify'
    if ($LASTEXITCODE -ne 0) {
        throw "Maven verification failed for $pom (exit $LASTEXITCODE)."
    }
}

# Rebuild instead of copying a potentially stale root-level JAR.
Invoke-Verify (Join-Path $server 'pom.xml')
Invoke-Verify (Join-Path $repo 'pom.xml')

$serverJars = @(Get-ChildItem -LiteralPath (Join-Path $server 'target') -File -Filter '*.jar' |
    Where-Object { $_.Name -notmatch '(?i)(-sources|-javadoc|-tests|-plain)\.jar$' })
if ($serverJars.Count -ne 1) {
    throw "Expected one executable server JAR in Assignment1/target; found $($serverJars.Count)."
}

$stage = Join-Path ([System.IO.Path]::GetTempPath()) ("comp3011-titan-" + [Guid]::NewGuid().ToString('N'))
$temporaryZip = $null
New-Item -ItemType Directory -Path $stage | Out-Null
try {
    # Root project: TITAN invokes 'mvn javafx:run' from the ZIP root.
    foreach ($relative in @('pom.xml', 'src', 'README.md', 'docs', 'scripts', '.gitignore')) {
        $source = Join-Path $repo $relative
        if (Test-Path -LiteralPath $source) {
            Copy-Item -LiteralPath $source -Destination (Join-Path $stage $relative) -Recurse -Force
        }
    }

    # Server source for inspection, but NOT its target/ directory.
    $serverStage = Join-Path $stage 'Assignment1'
    New-Item -ItemType Directory -Path $serverStage | Out-Null
    foreach ($relative in @('pom.xml', 'src', 'README.md', 'DESIGN.md', 'WHISPER_LIVE_TESTING.md',
                            '.mvn', 'mvnw', 'mvnw.cmd', '.gitignore', '.gitattributes')) {
        $source = Join-Path $server $relative
        if (Test-Path -LiteralPath $source) {
            Copy-Item -LiteralPath $source -Destination (Join-Path $serverStage $relative) -Recurse -Force
        }
    }

    # TITAN starts the runnable Spring Boot JAR from the ZIP root.
    Copy-Item -LiteralPath $serverJars[0].FullName -Destination (Join-Path $stage $serverJars[0].Name)

    if (-not (Test-Path -LiteralPath $outputDirectory)) {
        New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    }

    # Validate a temporary archive before replacing an existing good submission.
    $temporaryZip = Join-Path $outputDirectory ('.titan-' + [Guid]::NewGuid().ToString('N') + '.zip')
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $stage, $temporaryZip, [System.IO.Compression.CompressionLevel]::Optimal, $false
    )

    $archive = [System.IO.Compression.ZipFile]::OpenRead($temporaryZip)
    try {
        $names = @($archive.Entries | ForEach-Object { $_.FullName })
        if ($names -notcontains 'pom.xml' -or
            $names -notcontains 'src/main/java/comp3011/VideoPlayerApp.java' -or
            $names -notcontains 'Assignment1/pom.xml') {
            throw 'ZIP validation failed: root JavaFX project or Assignment1 sources are missing.'
        }
        if (@($names | Where-Object { $_ -match '^[^/]+\.jar$' }).Count -ne 1) {
            throw 'ZIP validation failed: expected exactly one server JAR at ZIP root.'
        }
        if (@($names | Where-Object { $_ -match '(^|/)(target|\.git)/|(^|/)\.env($|/)|\.(mp4|mov|mkv)$' }).Count -gt 0) {
            throw 'ZIP validation failed: build output, Git data, credentials or media included.'
        }
    }
    finally {
        $archive.Dispose()
    }

    if (Test-Path -LiteralPath $OutputZip) {
        Remove-Item -LiteralPath $OutputZip -Force
    }
    Move-Item -LiteralPath $temporaryZip -Destination $OutputZip
    $temporaryZip = $null

    $sizeMB = [math]::Round((Get-Item -LiteralPath $OutputZip).Length / 1MB, 1)
    Write-Host "TITAN submission ready: $OutputZip ($sizeMB MB)" -ForegroundColor Green
    Write-Host 'ZIP root contains pom.xml, src/, Assignment1/, and one executable server JAR.'
}
finally {
    if (Test-Path -LiteralPath $stage) {
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
    if ($null -ne $temporaryZip -and (Test-Path -LiteralPath $temporaryZip)) {
        Remove-Item -LiteralPath $temporaryZip -Force
    }
}
