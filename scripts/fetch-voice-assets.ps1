#Requires -Version 7.4
param([switch]$UseGhApi)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$staging = Join-Path $project '.local/voice'
$assets = Join-Path $project 'app/src/main/assets/voice'
New-Item -ItemType Directory -Force $staging | Out-Null

function Get-PinnedAsset($FileName, $Release, $AssetName, $AssetId, $ExpectedHash) {
    $path = Join-Path $staging $FileName
    if ((Test-Path -LiteralPath $path) -and
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -eq $ExpectedHash) {
        return $path
    }

    $download = "$path.download"
    if ($UseGhApi) {
        gh api "repos/k2-fsa/sherpa-onnx/releases/assets/$AssetId" -H 'Accept: application/octet-stream' > $download
    } else {
        curl -fL --retry 2 -o $download "https://github.com/k2-fsa/sherpa-onnx/releases/download/$Release/$AssetName"
    }
    if ($LASTEXITCODE -ne 0) { throw "Download failed for $AssetName" }
    if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ne $ExpectedHash) {
        throw "SHA-256 mismatch for $AssetName"
    }
    Move-Item -LiteralPath $download -Destination $path -Force
    return $path
}

function Install-Model($Archive, $ArchiveRoot, $Suffix, $Destination) {
    $roles = 'encoder', 'decoder', 'joiner'
    $entries = @($roles | ForEach-Object { "$ArchiveRoot/$_$Suffix" }) + "$ArchiveRoot/tokens.txt"
    tar -xf $Archive -C $staging @entries
    if ($LASTEXITCODE -ne 0) { throw "Extracting $ArchiveRoot failed" }

    New-Item -ItemType Directory -Force $Destination | Out-Null
    foreach ($role in $roles) {
        $source = Join-Path $staging "$ArchiveRoot/$role$Suffix"
        Copy-Item -LiteralPath $source -Destination (Join-Path $Destination "$role.onnx") -Force
    }
    Copy-Item -LiteralPath (Join-Path $staging "$ArchiveRoot/tokens.txt") -Destination $Destination -Force
}

$runtimeHash = 'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7'
$runtime = Get-PinnedAsset 'sherpa.aar' 'v2.0.1' 'sherpa-onnx-2.0.1.aar' 678742021 $runtimeHash
$kws = 'sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01'
$kwsHash = 'f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a'
$kwsArchive = Get-PinnedAsset 'kws.tar.bz2' 'kws-models' "$kws.tar.bz2" 166405338 $kwsHash
$asr = 'sherpa-onnx-streaming-zipformer-en-20M-2023-02-17'
$asrHash = '9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9'
$asrArchive = Get-PinnedAsset 'asr.tar.bz2' 'asr-models' "$asr.tar.bz2" 143510207 $asrHash

$libs = Join-Path $project 'app/libs'
New-Item -ItemType Directory -Force $libs | Out-Null
Copy-Item -LiteralPath $runtime -Destination (Join-Path $libs 'sherpa-onnx-2.0.1.aar') -Force
Install-Model $kwsArchive $kws '-epoch-12-avg-2-chunk-16-left-64.int8.onnx' (Join-Path $assets 'kws')
Install-Model $asrArchive $asr '-epoch-99-avg-1.int8.onnx' (Join-Path $assets 'asr')
Write-Output 'Installed verified offline voice assets.'
