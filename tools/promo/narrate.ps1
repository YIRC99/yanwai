param([string]$Version = '2.6.3')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$promoRoot = Join-Path (Get-Location) "output/yanwai-$Version"
$scenes = Get-Content (Join-Path $promoRoot 'storyboard.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$speaker = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $speaker.SelectVoice('Microsoft Huihui Desktop')
    $speaker.Rate = 1
    $speaker.Volume = 100
    foreach ($scene in $scenes) {
        $speaker.SetOutputToWaveFile((Join-Path $promoRoot "audio/$($scene.key).wav"))
        $speaker.Speak($scene.voice)
        $speaker.SetOutputToNull()
    }
} finally { $speaker.Dispose() }
