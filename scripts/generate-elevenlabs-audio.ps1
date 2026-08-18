param(
    [string]$ApiKey = $env:ELEVENLABS_API_KEY,
    [string]$VoiceId = $env:ELEVENLABS_VOICE_ID,
    [string]$OutputDirectory = "app/src/main/assets/voice"
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    throw "Set ELEVENLABS_API_KEY in the environment before running this script."
}
if ([string]::IsNullOrWhiteSpace($VoiceId)) {
    throw "Set ELEVENLABS_VOICE_ID to a Russian-capable ElevenLabs voice."
}

$phrases = [ordered]@{
    "what_to_find" = "Что найти?"
    "listen_now" = "Говорите."
    "new_tag_name" = "Новая метка. Как назвать вещь?"
    "name_item" = "Как назвать вещь?"
    "take_photo" = "Сделайте фотографию кнопкой громкости."
    "check_photo" = "Проверьте фотографию."
    "bring_new_tag" = "Поднесите одну новую метку и нажмите курок."
    "hold_trigger" = "Удерживайте курок и перемещайте считыватель."
    "item_found" = "Вещь найдена."
    "item_added" = "Вещь добавлена."
    "tag_changed" = "Метка изменена."
    "photo_updated" = "Фотография обновлена."
    "item_deleted" = "Вещь удалена."
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$headers = @{
    "xi-api-key" = $ApiKey
    "Accept" = "audio/mpeg"
}

foreach ($entry in $phrases.GetEnumerator()) {
    $body = @{
        text = $entry.Value
        model_id = "eleven_multilingual_v2"
        voice_settings = @{
            stability = 0.65
            similarity_boost = 0.8
            style = 0.15
            use_speaker_boost = $true
        }
    } | ConvertTo-Json -Depth 5
    $target = Join-Path $OutputDirectory ($entry.Key + ".mp3")
    Write-Host "Generating $($entry.Key)..."
    Invoke-WebRequest `
        -Method Post `
        -Uri "https://api.elevenlabs.io/v1/text-to-speech/$VoiceId" `
        -Headers $headers `
        -ContentType "application/json" `
        -Body $body `
        -OutFile $target `
        -UseBasicParsing
}

Write-Host "Generated $($phrases.Count) prompts in $OutputDirectory"
