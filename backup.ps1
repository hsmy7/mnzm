param(
    [switch]$Push,
    [string]$Remote = "origin",
    [string]$Branch = "master"
)

$ErrorActionPreference = "Stop"

$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
$commitMessage = "backup: $timestamp"

Write-Host "=== Git Backup Script ===" -ForegroundColor Cyan
Write-Host "Time: $timestamp" -ForegroundColor Gray

try {
    $status = git status --porcelain 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Not a git repository or git command failed"
    }
    
    if ([string]::IsNullOrWhiteSpace($status)) {
        Write-Host "No changes to commit." -ForegroundColor Green
        exit 0
    }
    
    Write-Host "Changes detected:" -ForegroundColor Yellow
    Write-Host $status
    
    Write-Host "`nAdding all changes..." -ForegroundColor Cyan
    git add -A
    
    Write-Host "Committing changes..." -ForegroundColor Cyan
    git commit -m $commitMessage
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Commit successful: $commitMessage" -ForegroundColor Green
    } else {
        Write-Host "Commit may have issues (exit code: $LASTEXITCODE)" -ForegroundColor Yellow
    }
    
    if ($Push) {
        Write-Host "`nPushing to remote '$Remote' branch '$Branch'..." -ForegroundColor Cyan
        
        $remoteExists = git remote get-url $Remote 2>$null
        if ($LASTEXITCODE -ne 0) {
            Write-Host "Remote '$Remote' not found. Skipping push." -ForegroundColor Yellow
            Write-Host "To add a remote, run: git remote add $Remote <url>" -ForegroundColor Gray
        } else {
            git push $Remote $Branch
            if ($LASTEXITCODE -eq 0) {
                Write-Host "Push successful!" -ForegroundColor Green
            } else {
                Write-Host "Push failed (exit code: $LASTEXITCODE)" -ForegroundColor Red
            }
        }
    }
    
    Write-Host "`n=== Backup Complete ===" -ForegroundColor Green
    
} catch {
    Write-Host "Error: $_" -ForegroundColor Red
    exit 1
}
