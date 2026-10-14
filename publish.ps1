# =====================================================================
#  NotificationSinking - one-click publish to GitHub
#  (ASCII-only on purpose: runs correctly in Windows PowerShell 5.1 and 7.)
#
#  QUICK START (creates the repository automatically, then pushes):
#     powershell -ExecutionPolicy Bypass -File publish.ps1 -Token <YOUR_PAT>
#
#  More control:
#     ... -RepoName MyRepo -Visibility private
#     ... -RepoUrl https://github.com/<user>/<repo>.git      # push into an existing repo
#     ... -UserName "Nick" -UserEmail "nick@example.com"     # commit identity
#
#  Token needs the "repo" scope (classic PAT) or "Contents: read/write"
#  (fine-grained PAT). The token is used in memory only - it is never written
#  to .git/config or any file.
# =====================================================================
[CmdletBinding()]
param(
    [string]$RepoName = "NotificationSinking",
    [string]$RepoUrl  = "",
    [string]$Token    = "",
    [string]$UserName = "",
    [string]$UserEmail = "",
    [ValidateSet("public", "private")][string]$Visibility = "public",
    [string]$Description = "ColorOS 16 lockscreen / AOD notification sinking (LSPosed module). AI-generated.",
    [string]$Version = "1.13.0",
    [switch]$SkipCommit,
    [switch]$SkipRelease
)

# Native git commands may write to stderr (warnings) - never let that abort the
# script, otherwise the release/upload step would be skipped.
$ErrorActionPreference = "Continue"
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}
function Info($m) { Write-Host "[publish] $m" -ForegroundColor Cyan }
function Warn($m) { Write-Host "[publish] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[publish] $m" -ForegroundColor Red; exit 1 }

# GitHub API call with retries (network to api.github.com can be flaky).
function Invoke-Api {
    param(
        [string]$Uri,
        [string]$Method = "Get",
        $Body = $null,
        [string]$InFile = "",
        [string]$ContentType = "application/json; charset=utf-8",
        [int]$Retries = 3
    )
    for ($i = 1; $i -le $Retries; $i++) {
        try {
            $p = @{ Uri = $Uri; Method = $Method; Headers = $script:headers; TimeoutSec = 180 }
            if ($InFile) { $p.InFile = $InFile; $p.ContentType = $ContentType }
            elseif ($null -ne $Body) { $p.Body = $Body; $p.ContentType = $ContentType }
            return Invoke-RestMethod @p
        } catch {
            $msg = $_.Exception.Message
            # do not retry authentication failures - they will never succeed
            $isAuth = ($msg -match "401") -or ($msg -match "(?i)unauthor") -or ($msg -match "未经授权")
            if ($i -eq $Retries -or $isAuth) { throw }
            Warn ("network error (" + $msg + ") - retry " + $i + "/" + ($Retries - 1))
            Start-Sleep -Seconds (2 * $i)
        }
    }
}

$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $repo
Info ("repo dir: " + $repo)

# ---------- 1. locate git ----------
function Find-Git {
    # -CommandType Application: only look for a real executable, never a function/alias
    $cmd = Get-Command git -CommandType Application -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        (Join-Path $repo "..\tools\git\cmd\git.exe"),
        (Join-Path $repo "tools\git\cmd\git.exe"),
        "E:\deepseek harness1\tools\git\cmd\git.exe",
        "$env:ProgramFiles\Git\cmd\git.exe",
        "${env:ProgramFiles(x86)}\Git\cmd\git.exe",
        "$env:LOCALAPPDATA\Programs\Git\cmd\git.exe"
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return (Resolve-Path $c).Path } }
    return $null
}

$git = Find-Git
if (-not $git) {
    Fail @"
git not found.
Install it with:  winget install --id Git.Git -e
(or download portable MinGit and put it in ..\tools\git\)
"@
}
Info ("git: " + $git)

# ---------- 2. init repo ----------
if (-not (Test-Path (Join-Path $repo ".git"))) {
    Info "git init (branch: main)"
    & $git init -b main | Out-Null
} else {
    Info "existing git repo detected"
}

if ($UserName)  { & $git config user.name  $UserName }
if ($UserEmail) { & $git config user.email $UserEmail }
if (-not (& $git config user.name))  { & $git config user.name  "NotificationSinking (AI-generated)" }
if (-not (& $git config user.email)) { & $git config user.email "notificationsinking@users.noreply.github.com" }
Info ("commit identity: " + (& $git config user.name) + " <" + (& $git config user.email) + ">")

# ---------- 3. commit ----------
if (-not $SkipCommit) {
    & $git add -A
    if (& $git status --porcelain) {
        $msg = "feat: NotificationSinking v1.13.0 - AI-generated LSPosed module for ColorOS 16"
        Info ("commit: " + $msg)
        & $git commit -q -m $msg
    } else {
        Info "nothing to commit (working tree clean)"
    }
}

# ---------- 4. resolve token (ask if missing) ----------
if (-not $Token) {
    Write-Host ""
    Warn "A GitHub Personal Access Token is needed (scope: repo) to create/push the repository."
    Write-Host "Create one at: https://github.com/settings/tokens" -ForegroundColor Yellow
    $secure = Read-Host "Token (empty = abort)" -AsSecureString
    if ($secure -and $secure.Length -gt 0) {
        $Token = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
            [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
    }
}
if (-not $Token) { Fail "no token provided - repository is committed locally and ready to push." }

$headers = @{
    Authorization = "token $Token"
    "User-Agent"  = "NotificationSinking-publish"
    Accept        = "application/vnd.github+json"
}

# ---------- 5. verify token & figure out the login ----------
$login = ""
try {
    $me = Invoke-Api -Uri "https://api.github.com/user"
    $login = $me.login
    Info ("authenticated as: " + $login)
} catch {
    Fail ("token check failed: " + $_.Exception.Message + "  (check scope: repo)")
}

# ---------- 6. create the repository if needed ----------
if (-not $RepoUrl) {
    $RepoUrl = "https://github.com/$login/$RepoName.git"
    $exists = $false
    try {
        Invoke-Api -Uri "https://api.github.com/repos/$login/$RepoName" | Out-Null
        $exists = $true
    } catch { $exists = $false }

    if ($exists) {
        Info ("repository already exists: " + $RepoUrl)
    } else {
        Info ("creating repository: $login/$RepoName ($Visibility)")
        $body = @{
            name        = $RepoName
            description = $Description
            private     = ($Visibility -eq "private")
            has_issues  = $true
            has_wiki    = $false
        } | ConvertTo-Json
        try {
            Invoke-RestMethod -Uri "https://api.github.com/user/repos" -Method Post -Headers $headers `
                -Body $body -ContentType "application/json" -TimeoutSec 60 | Out-Null
            Info "repository created"
        } catch {
            Fail ("repository creation failed: " + $_.Exception.Message)
        }
    }
}

# ---------- 7. push ----------
$cleanUrl = $RepoUrl -replace "https://[^@/]+@", "https://"
if ((& $git remote) -contains "origin") {
    & $git remote set-url origin $cleanUrl
} else {
    & $git remote add origin $cleanUrl
}
Info ("remote origin -> " + $cleanUrl)

$parts = $cleanUrl -replace "^https://", ""
$authUrl = "https://" + $login + ":" + $Token + "@" + $parts
Info "pushing (token used in memory only)"
& $git push $authUrl "HEAD:refs/heads/main" --force
$ok = ($LASTEXITCODE -eq 0)
& $git remote set-url origin $cleanUrl
if (-not $ok) { Fail "push failed - check the token scope and repository URL." }
Info "pushed"

# create the remote-tracking ref locally, then wire up the upstream branch.
# (The push above used an explicit URL, so origin/main does not exist yet -
#  without this fetch the --set-upstream-to call fails; it must never be fatal.)
& $git fetch origin main 2>&1 | Out-Null
$a = & $git branch --set-upstream-to=origin/main main 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) { Info "upstream set: main -> origin/main" }
else { Warn "could not set upstream (harmless; push already succeeded)" }

# ---------- 8. create a GitHub Release and upload the APK ----------
# The APK is already tracked in dist/ and therefore pushed with the repo.
# A Release additionally gives users a stable download link + version tag.
if (-not $SkipRelease) {
    $slug = ($cleanUrl -replace "^https://github\.com/", "") -replace "\.git$", ""
    $apks = @(Get-ChildItem (Join-Path $repo "dist") -Filter *.apk -ErrorAction SilentlyContinue)
    if ($apks.Count -eq 0) {
        Warn "no APK found in dist/ - skipping release"
    } else {
        $tag = "v" + $Version
        foreach ($a in $apks) { Info ("release asset: " + $a.Name + " (" + [math]::Round($a.Length/1KB) + " KB)") }

        $notesPath = Join-Path $repo "docs\RELEASE_NOTES.md"
        if (Test-Path $notesPath) {
            $notes = [System.IO.File]::ReadAllText($notesPath, [System.Text.Encoding]::UTF8)
            Info "release notes: docs/RELEASE_NOTES.md"
        } else {
            $notes = "AI-generated LSPosed module for ColorOS 16 lockscreen / AOD notification sinking."
        }

        # remove an existing release with the same tag (script is re-runnable)
        try {
            $old = Invoke-RestMethod -Uri "https://api.github.com/repos/$slug/releases/tags/$tag" `
                -Headers $headers -TimeoutSec 30
            if ($old -and $old.id) {
                Invoke-RestMethod -Uri "https://api.github.com/repos/$slug/releases/$($old.id)" `
                    -Method Delete -Headers $headers -TimeoutSec 30 | Out-Null
                Info ("removed previous release " + $tag)
            }
        } catch { }

        $relBody = @{
            tag_name   = $tag
            name       = "NotificationSinking $tag"
            body       = $notes
            draft      = $false
            prerelease = $false
        } | ConvertTo-Json -Depth 3
        # PS 5.1 would encode a plain string body as Latin-1 and mangle the
        # Chinese release notes - always send explicit UTF-8 bytes.
        $relBytes = [System.Text.Encoding]::UTF8.GetBytes($relBody)

        try {
            $rel = Invoke-Api -Uri "https://api.github.com/repos/$slug/releases" -Method Post -Body $relBytes
            Info ("release created: " + $rel.tag_name)
        } catch {
            $rel = $null
            Warn ("release creation failed: " + $_.Exception.Message)
        }

        if ($rel -and $rel.upload_url) {
            $uploadBase = $rel.upload_url -replace "\{\?name,label\}", ""
            foreach ($a in $apks) {
                $assetUrl = $uploadBase + "?name=" + [uri]::EscapeDataString($a.Name)
                try {
                    Invoke-Api -Uri $assetUrl -Method Post -InFile $a.FullName `
                        -ContentType "application/vnd.android.package-archive" | Out-Null
                    Info ("uploaded asset: " + $a.Name)
                } catch {
                    Warn ("asset upload failed (" + $a.Name + "): " + $_.Exception.Message)
                }
            }
        }
    }
}

Info "done!"
Write-Host ""
Write-Host ("  repository : https://github.com/" + $slug) -ForegroundColor Green
Write-Host ("  releases   : https://github.com/" + $slug + "/releases") -ForegroundColor Green
Write-Host ("  apk (repo) : https://github.com/" + $slug + "/blob/main/dist/NotificationSinking-v" + $Version + ".apk") -ForegroundColor Green
Write-Host ("  files      : " + ((& $git ls-files).Count) + " tracked") -ForegroundColor Green
Write-Host ""
Write-Host "  Reminder: README carries the AI-generated disclosure." -ForegroundColor Yellow
