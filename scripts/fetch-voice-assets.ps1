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
$asr = 'sherpa-onnx-moonshine-base-en-quantized-2026-02-27'
$asrHash = '43232c1d13013d37317163baec3135bd771a186a4356f28c889bab453bb0e891'
$asrArchive = Get-PinnedAsset 'moonshine.tar.bz2' 'asr-models' "$asr.tar.bz2" 363390510 $asrHash
$vadHash = '9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6'
$vad = Get-PinnedAsset 'silero_vad.onnx' 'asr-models' 'silero_vad.onnx' 271935959 $vadHash

$libs = Join-Path $project 'app/libs'
New-Item -ItemType Directory -Force $libs | Out-Null
Copy-Item -LiteralPath $runtime -Destination (Join-Path $libs 'sherpa-onnx-2.0.1.aar') -Force
Install-Model $kwsArchive $kws '-epoch-12-avg-2-chunk-16-left-64.int8.onnx' (Join-Path $assets 'kws')
$asrFiles = 'encoder_model.ort', 'decoder_model_merged.ort', 'tokens.txt'
tar -xf $asrArchive -C $staging @($asrFiles + 'LICENSE' | ForEach-Object { "$asr/$_" })
if ($LASTEXITCODE -ne 0) { throw "Extracting $asr failed" }
$asrTarget = Join-Path $assets 'asr'
New-Item -ItemType Directory -Force $asrTarget | Out-Null
foreach ($file in $asrFiles) { Copy-Item -LiteralPath (Join-Path $staging "$asr/$file") -Destination $asrTarget -Force }
Copy-Item -LiteralPath (Join-Path $staging "$asr/LICENSE") -Destination (Join-Path $assets 'LICENSE-moonshine.txt') -Force
$vadTarget = Join-Path $assets 'vad'
New-Item -ItemType Directory -Force $vadTarget | Out-Null
Copy-Item -LiteralPath $vad -Destination $vadTarget -Force

$tts = 'sherpa-onnx-supertonic-3-tts-int8-2026-05-11'
$ttsHash = '82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427'
$ttsArchive = Get-PinnedAsset 'supertonic.tar.bz2' 'tts-models' "$tts.tar.bz2" 418881054 $ttsHash
$ttsFiles = 'duration_predictor.int8.onnx', 'text_encoder.int8.onnx', 'vector_estimator.int8.onnx', 'vocoder.int8.onnx', 'tts.json', 'unicode_indexer.bin', 'voice.bin'
$entries = @($ttsFiles | ForEach-Object { "$tts/$_" })
tar -xf $ttsArchive -C $staging @entries
if ($LASTEXITCODE -ne 0) { throw "Extracting $tts failed" }
$ttsTarget = Join-Path $assets 'tts'
New-Item -ItemType Directory -Force $ttsTarget | Out-Null
foreach ($file in $ttsFiles) { Copy-Item -LiteralPath (Join-Path $staging "$tts/$file") -Destination $ttsTarget -Force }
Write-Output 'Installed verified offline voice assets.'
