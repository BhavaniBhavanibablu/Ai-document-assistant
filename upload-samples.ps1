
$folder = Join-Path $PSScriptRoot "src\main\resources\pdfs"
$api = "http://localhost:8080/api/documents/upload"

if (-not (Test-Path $folder)) {
    Write-Host "PDF folder not found: $folder" -ForegroundColor Red
    exit 1
}

$files = Get-ChildItem $folder -Filter "*.pdf" -File

if ($files.Count -eq 0) {
    Write-Host "No PDFs found in $folder" -ForegroundColor Red
    exit 1
}

foreach ($file in $files) {
    Write-Host "Uploading $($file.Name)..."
    & curl.exe -sS -w "`nHTTP status: %{http_code}`n" `
        -F "file=@$($file.FullName);type=application/pdf" $api
}
