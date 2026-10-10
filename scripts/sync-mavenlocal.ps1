# NROF: pre-seed mavenLocal with artifacts that only exist on maven.neoforged.net.
# maven.neoforged.net is intermittently unreachable (TLS resets) on this network,
# so we fetch every whitelisted-group artifact (jar + pom, plus parent poms) with
# retries and install them into %USERPROFILE%\.m2\repository. Gradle then resolves
# them from mavenLocal() and never needs to touch the flaky host.
# Non-whitelisted deps (ASM, toml4j, etc.) resolve from Maven Central / Aliyun.

param(
    [string]$RepoUrl = "https://maven.neoforged.net/releases",
    [string]$M2 = "$env:USERPROFILE\.m2\repository"
)

$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"

# Groups that are only hosted on maven.neoforged.net -> must be mirrored.
$Whitelist = @("net.neoforged", "net.neoforged.fancymodloader", "cpw.mods")
# Root artifacts to seed (matches NeoForge 21.1.249 / MC 1.21.1 dependency set).
$Roots = @(
    "net.neoforged.fancymodloader:loader:4.0.44",
    "net.neoforged.fancymodloader:spi:3.0.9",
    "net.neoforged:coremods:7.0.3",
    "net.neoforged:accesstransformers:10.0.1",
    "net.neoforged.accesstransformers:at-modlauncher:10.0.1",
    "net.neoforged:mergetool:2.0.0",
    "net.neoforged:JarJarSelector:0.4.1",
    "net.neoforged:JarJarMetadata:0.4.1",
    "net.neoforged:JarJarFileSystems:0.4.1",
    "cpw.mods:modlauncher:11.0.3",
    "cpw.mods:modlauncher:11.0.5",
    "cpw.mods:securejarhandler:3.0.7"
)

$script:Downloaded = New-Object System.Collections.Generic.HashSet[string]
$script:Failed = New-Object System.Collections.Generic.HashSet[string]

function Fetch($url, $outFile) {
    # curl.exe is far more reliable than Invoke-WebRequest against this host.
    foreach ($i in 1..10) {
        curl.exe -sf --max-time 40 -o "$outFile.tmp" $url 2>$null
        $code = $LASTEXITCODE
        if ($code -eq 0) {
            Move-Item -Force "$outFile.tmp" $outFile
            return $true
        }
        if ($code -eq 22) { return $false }  # HTTP 404 with -f
        Start-Sleep -Milliseconds (300 * $i)
    }
    return $false
}

function GroupPath($groupId) { return $groupId.Replace(".", "\") }

function Get-PomProperties($pomText) {
    $props = @{}
    $m = [regex]::Match($pomText, "(?s)<properties>(.*?)</properties>")
    if ($m.Success) {
        foreach ($p in [regex]::Matches($m.Groups[1].Value, "<([A-Za-z0-9\.\-_]+)>([^<]+)</\1>")) {
            $props[$p.Groups[1].Value] = $p.Groups[2].Value.Trim()
        }
    }
    return $props
}

function Resolve-Version($raw, $props, $parentProps, $parentVersion) {
    $v = $raw
    $v = $v -replace '\$\{project\.version\}', $parentVersion
    $v = $v -replace '\$\{project\.groupId\}', ''
    foreach ($k in $props.Keys) { $v = $v -replace [regex]::Escape("${$k}"), $props[$k] }
    foreach ($k in $parentProps.Keys) { $v = $v -replace [regex]::Escape("${$k}"), $parentProps[$k] }
    return $v
}

function Sync-Artifact($groupId, $artifactId, $version) {
    $key = "$groupId`:$artifactId`:$version"
    if ($script:Downloaded.Contains($key)) { return }
    [void]$script:Downloaded.Add($key)

    $dir = Join-Path (Join-Path $M2 (GroupPath $groupId)) (Join-Path $artifactId $version)
    $base = "$RepoUrl/$($groupId.Replace('.', '/'))/$artifactId/$version"

    $pomFile = Join-Path $dir "$artifactId-$version.pom"
    if (-not (Test-Path $pomFile)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        if (-not (Fetch "$base/$artifactId-$version.pom" $pomFile)) {
            Write-Host "[MISS ] pom $key"
            [void]$script:Failed.Add("$key (pom)")
            return
        }
    }
    Write-Host "[OK   ] pom $key"

    $jarFile = Join-Path $dir "$artifactId-$version.jar"
    if (-not (Test-Path $jarFile)) {
        if (Fetch "$base/$artifactId-$version.jar" $jarFile) {
            Write-Host "[OK   ] jar $key"
        } else {
            Write-Host "[MISS ] jar $key (maybe packaging=pom)"
            [void]$script:Failed.Add("$key (jar)")
        }
    }

    # Gradle Module Metadata + common variant jars (api/all/dist) so Gradle can
    # resolve variant-aware dependencies (e.g. mergetool-api) from mavenLocal.
    foreach ($variant in @("", "-api", "-all", "-dist")) {
        $suffix = $variant
        if (-not (Test-Path (Join-Path $dir "$artifactId-$version$suffix.module"))) {
            if ($suffix -eq "") {
                $moduleFile = Join-Path $dir "$artifactId-$version.module"
                $moduleUrl = "$base/$artifactId-$version.module"
            } else {
                $moduleFile = Join-Path $dir "$artifactId-$version$suffix.module"
                $moduleUrl = "$base/$artifactId-$version$suffix.module"
            }
            Fetch $moduleUrl $moduleFile | Out-Null
        }
        $vjar = Join-Path $dir "$artifactId-$version$suffix.jar"
        if ($suffix -ne "" -and -not (Test-Path $vjar)) {
            if (Fetch "$base/$artifactId-$version$suffix.jar" $vjar) {
                Write-Host "[OK   ] jar($suffix) $key"
            }
        }
    }

    $pomText = [IO.File]::ReadAllText($pomFile)

    # Parent poms must also exist locally for Gradle's pom parsing.
    $pm = [regex]::Match($pomText, "(?s)<parent>(.*?)</parent>")
    $parentProps = @{}
    $parentVersion = $version
    if ($pm.Success) {
        $pg = [regex]::Match($pm.Groups[1].Value, "<groupId>([^<]+)</groupId>").Groups[1].Value.Trim()
        $pa = [regex]::Match($pm.Groups[1].Value, "<artifactId>([^<]+)</artifactId>").Groups[1].Value.Trim()
        $pv = [regex]::Match($pm.Groups[1].Value, "<version>([^<]+)</version>").Groups[1].Value.Trim()
        if ($pg -and $pa -and $pv) {
            $parentVersion = $pv
            if ($Whitelist -contains ($pg -replace '\.fancymodloader$','') -or ($Whitelist | Where-Object { $pg -eq $_ -or $pg.StartsWith("$_ ") })) {
                Sync-Artifact $pg $pa $pv
            }
            # inherit parent properties (best effort: read parent pom if we have it)
            $ppom = Join-Path (Join-Path (Join-Path $M2 (GroupPath $pg)) (Join-Path $pa $pv)) "$pa-$pv.pom"
            if (Test-Path $ppom) { $parentProps = Get-PomProperties ([IO.File]::ReadAllText($ppom)) }
        }
    }

    $props = Get-PomProperties $pomText

    # Dependencies: recurse only into whitelisted groups.
    $dm = [regex]::Match($pomText, "(?s)<dependencies>(.*?)</dependencies>")
    if ($dm.Success) {
        foreach ($d in [regex]::Matches($dm.Groups[1].Value, "(?s)<dependency>(.*?)</dependency>")) {
            $dep = $d.Groups[1].Value
            $dg = [regex]::Match($dep, "<groupId>([^<]+)</groupId>").Groups[1].Value.Trim()
            $da = [regex]::Match($dep, "<artifactId>([^<]+)</artifactId>").Groups[1].Value.Trim()
            $dv = [regex]::Match($dep, "<version>([^<]+)</version>").Groups[1].Value.Trim()
            $scope = [regex]::Match($dep, "<scope>([^<]+)</scope>").Groups[1].Value.Trim()
            $optional = [regex]::Match($dep, "<optional>true</optional>").Success
            if (-not $dg -or -not $da -or -not $dv) { continue }
            if ($scope -eq "test" -or $scope -eq "provided" -or $optional) { continue }
            if ($dv -like '$*' -or $dv -match 'LATEST|\[') {
                Write-Host "[WARN ] unresolved version $dg`:$da`:$dv - skipping (let Gradle/aliyun handle)"
                continue
            }
            $target = $Whitelist | Where-Object { $dg -eq $_ -or $dg.StartsWith("$_ ") -or $dg.StartsWith("$_.") -or $dg -like "$_*" -and $dg.StartsWith("$_") }
            $isWhitelisted = $false
            foreach ($w in $Whitelist) {
                if ($dg -eq $w -or $dg.StartsWith("$w.") -or ($w -eq "net.neoforged" -and ($dg -eq "net.neoforged.fancymodloader" -or $dg.StartsWith("net.neoforged.fancymodloader.")))) { $isWhitelisted = $true }
            }
            if ($isWhitelisted) {
                $rv = Resolve-Version $dv $props $parentProps $parentVersion
                if ($rv -match '^\d') {
                    Sync-Artifact $dg $da $rv
                } else {
                    Write-Host "[WARN ] cannot resolve $dg`:$da`:$dv"
                }
            }
        }
    }
}

Write-Host "=== NROF mavenLocal sync (repo: $RepoUrl) ==="
foreach ($r in $Roots) {
    $p = $r.Split(":")
    Sync-Artifact $p[0] $p[1] $p[2]
}
Write-Host "=== done. artifacts touched: $($script:Downloaded.Count); failures: $($script:Failed.Count) ==="
$script:Failed | ForEach-Object { Write-Host "FAILED: $_" }
