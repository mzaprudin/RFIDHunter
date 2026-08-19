param(
    [string]$OutputDirectory = "models/whisper"
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

$models = @{
    "ggml-base-q5_1.bin" = "422F1AE452ADE6F30A004D7E5C6A43195E4433BC370BF23FAC9CC591F01A8898"
    "ggml-base-q8_0.bin" = "C577B9A86E7E048A0B7EADA054F4DD79A56BBFA911FBDACF900AC5B567CBB7D9"
}

foreach ($entry in $models.GetEnumerator()) {
    $target = Join-Path $OutputDirectory $entry.Key
    if (-not (Test-Path $target)) {
        Invoke-WebRequest -Uri "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$($entry.Key)" -OutFile $target -UseBasicParsing
    }
    $actual = (Get-FileHash -Algorithm SHA256 $target).Hash
    if ($actual -ne $entry.Value) { throw "Checksum mismatch for $($entry.Key): $actual" }
    Write-Host "$($entry.Key): $actual"
}
