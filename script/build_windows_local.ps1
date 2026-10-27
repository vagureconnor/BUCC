[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{40}$')][string]$SourceSha,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{40}$')][string]$SourceTree,
    [Parameter(Mandatory = $true)][string]$SourceArchive,
    [Parameter(Mandatory = $true)][string]$ProvenanceFile,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$ToolsDirectory = 'C:\BUCC-build-tools',
    [string]$VerifierScript = ''
)

$ErrorActionPreference = 'Stop'
$ToolsDirectory = [IO.Path]::GetFullPath($ToolsDirectory)
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$SourceArchive = [IO.Path]::GetFullPath($SourceArchive)
$ProvenanceFile = [IO.Path]::GetFullPath($ProvenanceFile)
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Refusing to overwrite an existing output directory.' }
if (-not (Test-Path -LiteralPath $SourceArchive -PathType Leaf)) { throw 'Source archive is missing.' }
if (-not (Test-Path -LiteralPath $ProvenanceFile -PathType Leaf)) { throw 'Source provenance manifest is missing.' }

function Resolve-FirstFile([string[]]$Candidates, [string]$Purpose) {
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }
    throw "Missing local $Purpose under $ToolsDirectory."
}
function Resolve-FirstDirectory([string[]]$Candidates, [string]$Purpose) {
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }
    throw "Missing local $Purpose under $ToolsDirectory."
}

$JdkHome = Resolve-FirstDirectory @(
    (Join-Path $ToolsDirectory 'jdk-21'),
    (Join-Path $ToolsDirectory 'jdk21'),
    (Join-Path $ToolsDirectory 'temurin-21')
) 'JDK 21'
$JavaExe = Resolve-FirstFile @((Join-Path $JdkHome 'bin\java.exe')) 'JDK 21 java.exe'
$PythonExe = Resolve-FirstFile @(
    (Join-Path $ToolsDirectory 'Python314\python.exe'),
    (Join-Path $ToolsDirectory 'python\python.exe')
) 'Python 3.14 x64'
$BashExe = Resolve-FirstFile @(
    (Join-Path $ToolsDirectory 'Git\bin\bash.exe'),
    (Join-Path $ToolsDirectory 'PortableGit\bin\bash.exe')
) 'Git Bash'
$GoExe = Resolve-FirstFile @(
    (Join-Path $ToolsDirectory 'go\bin\go.exe'),
    (Join-Path $ToolsDirectory 'Go\bin\go.exe'),
    (Join-Path $ToolsDirectory 'Git\mingw64\bin\go.exe'),
    (Join-Path $ToolsDirectory 'PortableGit\mingw64\bin\go.exe')
) 'Go x64'
$WixHome = Resolve-FirstDirectory @(
    (Join-Path $ToolsDirectory 'wix311'),
    (Join-Path $ToolsDirectory 'wix3\bin'),
    (Join-Path $ToolsDirectory 'WiX Toolset v3.14\bin')
) 'WiX Toolset 3'
foreach ($name in @('candle.exe', 'light.exe')) {
    $toolPath = Join-Path $WixHome $name
    if (-not (Test-Path -LiteralPath $toolPath -PathType Leaf)) { throw "WiX Toolset 3 is incomplete: $name is missing." }
    if ((Get-Item -LiteralPath $toolPath).VersionInfo.ProductVersion -notmatch '^3\.') { throw 'WiX Toolset 3 is required.' }
}

$oldEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'BUCC_BASH', 'BUCC_GO', 'BUCC_TD_PACKAGING_PYTHON', 'GOOS', 'GOARCH', 'WIX', 'PATH')) {
    $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

# Windows PowerShell 5 treats normal native stderr (including java -version) as
# NativeCommandError. Capture it without terminating, then check the exit code.
function Read-NativeInfo([string]$Executable, [string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $text = (& $Executable @Arguments 2>&1 | Out-String)
        $nativeExit = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previousPreference }
    if ($nativeExit -ne 0) { throw "Local tool failed: $Executable (exit $nativeExit)." }
    return $text
}
$javaInfo = Read-NativeInfo $JavaExe @('-XshowSettings:properties', '-version')
if ($javaInfo -notmatch 'os\.arch\s*=\s*amd64' -or $javaInfo -notmatch 'version "21([.]|")') { throw 'The configured runtime must be a working x64 JDK 21.' }
$pythonInfo = & $PythonExe -c 'import struct,sys,sysconfig; print(str(sys.version_info.major)+chr(46)+str(sys.version_info.minor)+chr(124)+sysconfig.get_platform()+chr(124)+str(struct.calcsize(chr(80))*8))'
if ($LASTEXITCODE -ne 0 -or $pythonInfo.Trim() -ne '3.14|win-amd64|64') { throw 'The configured Python must be Python 3.14 x64.' }
$goInfo = Read-NativeInfo $GoExe @('version')
if ($goInfo -notmatch 'windows/amd64') { throw 'The configured Go compiler must target Windows x64.' }
if ($goInfo -notmatch 'go(?<major>[0-9]+)\.(?<minor>[0-9]+)\.(?<patch>[0-9]+)') { throw 'Could not read the Go compiler version.' }
$goVersion = [Version]("$($Matches.major).$($Matches.minor).$($Matches.patch)")
if ($goVersion -lt [Version]'1.25.6') { throw 'Go 1.25.6 or newer is required.' }
$goEnv = & $GoExe env GOOS GOARCH 2>&1
if ($LASTEXITCODE -ne 0 -or $goEnv[0] -ne 'windows' -or $goEnv[1] -ne 'amd64') { throw 'Go target is not windows/amd64.' }

$VerifierScript = if ($VerifierScript) { [IO.Path]::GetFullPath($VerifierScript) } else { Join-Path $ToolsDirectory 'verify_git_archive.py' }
if (-not (Test-Path -LiteralPath $VerifierScript -PathType Leaf)) { throw 'Stage verify_git_archive.py beside the source archive before building.' }
$archiveHash = (Get-FileHash -LiteralPath $SourceArchive -Algorithm SHA256).Hash.ToLowerInvariant()
$provenance = Get-Content -LiteralPath $ProvenanceFile -Raw | ConvertFrom-Json
if ($provenance.source_sha -cne $SourceSha -or $provenance.source_tree -cne $SourceTree -or $provenance.archive_sha256 -cne $archiveHash) {
    throw 'Source provenance SHA/tree/archive digest does not match the requested build.'
}
$manifestHash = (Get-FileHash -LiteralPath $ProvenanceFile -Algorithm SHA256).Hash.ToLowerInvariant()

$env:JAVA_HOME = $JdkHome
$env:BUCC_BASH = $BashExe
$env:BUCC_GO = $GoExe
$env:BUCC_TD_PACKAGING_PYTHON = $PythonExe
$env:GOOS = 'windows'
$env:GOARCH = 'amd64'
$env:WIX = $WixHome
$env:PATH = (@((Join-Path $JdkHome 'bin'), (Split-Path -Parent $PythonExe), (Split-Path -Parent $GoExe), $WixHome, (Split-Path -Parent $BashExe), $oldEnvironment['PATH']) -join ';')
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$buildStart = [DateTime]::UtcNow
$sourceDirectory = Join-Path (Join-Path $ToolsDirectory 'workspaces') ("BUCC-source-{0}-{1}" -f $PID, $SourceSha)
$buildLog = Join-Path $OutputDirectory 'build.log'
try {
    & $PythonExe $VerifierScript --archive $SourceArchive --provenance $ProvenanceFile --destination $sourceDirectory --source-sha $SourceSha --source-tree $SourceTree
    if ($LASTEXITCODE -ne 0) { throw 'Source archive verification or extraction failed.' }
    $treeVerifyArgs = @('--archive', $SourceArchive, '--provenance', $ProvenanceFile, '--destination', $sourceDirectory, '--source-sha', $SourceSha, '--source-tree', $SourceTree, '--verify-existing')
    & $PythonExe $VerifierScript @treeVerifyArgs
    if ($LASTEXITCODE -ne 0) { throw 'Extracted source files do not match the requested Git tree.' }

    $root = $sourceDirectory
    $propertiesPath = Join-Path $root 'gradle.properties'
    $versionLine = Get-Content -LiteralPath $propertiesPath | Where-Object { $_ -match '^project\.version=' } | Select-Object -First 1
    $codeLine = Get-Content -LiteralPath $propertiesPath | Where-Object { $_ -match '^project\.version\.code=' } | Select-Object -First 1
    $version = ($versionLine -split '=', 2)[1]
    $versionCode = ($codeLine -split '=', 2)[1]
    if ($version -cne '2.0.1' -or $versionCode -cne '201') { throw 'Source version metadata must be BUCC 2.0.1 (201).' }
    $syncCheck = & $PythonExe (Join-Path $root '.github\scripts\version_sync.py') --check 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Cross-platform version metadata check failed.' }

    $composeBuild = Join-Path $root 'composeApp\build'
    $releaseExeDirectory = Join-Path $composeBuild 'compose\binaries\main-release\exe'
    # Clear only application launcher EXEs from this exact Release target directory.
    if (Test-Path -LiteralPath $releaseExeDirectory -PathType Container) {
        Get-ChildItem -LiteralPath $releaseExeDirectory -File -Filter 'BUCC-*.exe' | Remove-Item -Force
    }
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

    $gradle = Join-Path $root 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) { throw 'Gradle wrapper is missing from the verified source archive.' }
    Push-Location $root
    try {
        $ErrorActionPreference = 'Continue'
        & $gradle ':composeApp:packageReleaseExe' '--no-configuration-cache' '--console=plain' '--max-workers=2' 2>&1 | Tee-Object -FilePath $buildLog
        $gradleExit = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = 'Stop'; Pop-Location }
    if ($gradleExit -ne 0) { throw 'Windows Release EXE build failed; see sanitized build.log.' }

    & $PythonExe $VerifierScript @treeVerifyArgs
    if ($LASTEXITCODE -ne 0) { throw 'Tracked source files changed during the Windows build.' }
    $candidates = @(Get-ChildItem -LiteralPath $releaseExeDirectory -File -Filter "BUCC-$version.exe" -ErrorAction SilentlyContinue)
    if ($candidates.Count -ne 1) { throw 'The build did not produce exactly one BUCC Release EXE.' }
    $exe = $candidates[0]
    if ($exe.LastWriteTimeUtc -lt $buildStart) { throw 'Release EXE predates this build.' }

    $stream = [IO.File]::OpenRead($exe.FullName)
    try {
        $reader = [IO.BinaryReader]::new($stream)
        if ($reader.ReadUInt16() -ne 0x5A4D) { throw 'Release artifact is not a PE executable.' }
        $stream.Position = 0x3c
        $peOffset = $reader.ReadInt32()
        if ($peOffset -lt 64 -or $peOffset + 6 -gt $stream.Length) { throw 'Release EXE has an invalid PE header.' }
        $stream.Position = $peOffset
        if ($reader.ReadUInt32() -ne 0x00004550 -or $reader.ReadUInt16() -ne 0x8664) { throw 'Release EXE is not Windows x64 (PE machine 0x8664).' }
    }
    finally { $stream.Dispose() }
    $versionInfo = (Get-Item -LiteralPath $exe.FullName).VersionInfo
    if ($versionInfo.ProductVersion -notmatch '(^|[^0-9])2\.0\.1(\.0)?([^0-9]|$)') { throw 'Release EXE product version resource does not match 2.0.1.' }
    $auth = Get-AuthenticodeSignature -LiteralPath $exe.FullName
    if ($auth.Status -ne [Management.Automation.SignatureStatus]::NotSigned) {
        throw ("Release EXE has an Authenticode status outside the approved unsigned policy: " + $auth.Status)
    }

    $destinationExe = Join-Path $OutputDirectory $exe.Name
    Copy-Item -LiteralPath $exe.FullName -Destination $destinationExe
    $sha256 = (Get-FileHash -LiteralPath $destinationExe -Algorithm SHA256).Hash.ToLowerInvariant()
    $metadata = [ordered]@{
        platform = 'windows'
        file = [IO.Path]::GetFileName($destinationExe)
        version = $version
        version_code = [int]$versionCode
        source_sha = $SourceSha
        source_tree = $SourceTree
        source_archive_sha256 = $archiveHash
        source_manifest_sha256 = $manifestHash
        sha256 = $sha256
        architecture = 'x86_64'
        pe_machine = '0x8664'
        signing = 'unsigned'
        authenticode_status = [string]$auth.Status
        product_version = [string]$versionInfo.ProductVersion
    }
    $metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'metadata-windows.json') -Encoding UTF8
    Write-Output "Windows Release EXE built and inspected: $destinationExe"
}
finally {
    if (Test-Path -LiteralPath $sourceDirectory) { Remove-Item -LiteralPath $sourceDirectory -Recurse -Force }
    foreach ($name in $oldEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process') }
}
