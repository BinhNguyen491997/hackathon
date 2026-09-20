<#
.SYNOPSIS
    Client gọi Work Agent từ máy cá nhân.

.EXAMPLE
    # Hỏi một câu rồi thoát
    .\agent.ps1 -Message "Tạo task viết slide demo, hạn 12/09/2026, ưu tiên cao"

.EXAMPLE
    # Chế độ hội thoại (nhớ ngữ cảnh), gõ 'exit' để thoát
    .\agent.ps1

.EXAMPLE
    # Gọi agent đã deploy, có API key
    .\agent.ps1 -Url https://agent.example.com -ApiKey $env:AGENT_API_KEY
#>
[CmdletBinding()]
param(
    [string]$Url = $(if ($env:AGENT_URL) { $env:AGENT_URL } else { "http://localhost:8080" }),
    [string]$ApiKey = $env:AGENT_API_KEY,
    [string]$Message,
    [string]$ConversationId
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if (-not $ConversationId) {
    $ConversationId = [guid]::NewGuid().ToString()
}

function Invoke-Agent {
    param([string]$Text)

    $payload = @{ conversationId = $script:ConversationId; message = $Text } | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)

    $headers = @{}
    if ($ApiKey) { $headers["X-API-Key"] = $ApiKey }

    try {
        $response = Invoke-WebRequest -Uri "$Url/api/chat" -Method Post -Headers $headers `
            -ContentType "application/json; charset=utf-8" -Body $bytes -UseBasicParsing
    }
    catch {
        $status = $_.Exception.Response.StatusCode.value__
        Write-Host "Loi goi agent (HTTP $status): $($_.Exception.Message)" -ForegroundColor Red
        return
    }

    # Tự decode UTF-8 để không bị lỗi font tiếng Việt trên PowerShell 5.1
    $json = [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray()) | ConvertFrom-Json
    $script:ConversationId = $json.conversationId
    Write-Host ""
    Write-Host $json.reply -ForegroundColor Cyan
    Write-Host ""
}

if ($Message) {
    Invoke-Agent -Text $Message
    return
}

Write-Host "Work Agent @ $Url  (conversation: $ConversationId)" -ForegroundColor Green
Write-Host "Go 'exit' de thoat." -ForegroundColor DarkGray

while ($true) {
    $input = Read-Host "Ban"
    if ([string]::IsNullOrWhiteSpace($input)) { continue }
    if ($input.Trim().ToLower() -in @("exit", "quit", ":q")) { break }
    Invoke-Agent -Text $input
}
