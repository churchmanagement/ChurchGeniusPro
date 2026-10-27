<#
.SYNOPSIS
  Commit ChurchGeniusPro changes to feature1, merge into master, and push —
  the push to master is what deploys to Azure production (GitHub Actions).

.USAGE
  cd C:\workspace\ChurchGeniusPro
  .\deploy.ps1 -Message "Short description of the change"

  Optional:
    -NoDeploy    commit + push feature1 only (do not touch master)
    -Preview     show what would be committed and stop

  What deploys: .github\workflows\master_churchgeniuspro.yml runs on every push to
  master — builds in ChurchGeniusPro\, runs the full test suite (~3 min), and only
  then deploys to the Azure Web App "churchgeniuspro". A failing test = no deploy.
#>
param(
    [Parameter(Mandatory = $true)] [string] $Message,
    [switch] $NoDeploy,
    [switch] $Preview
)

$ErrorActionPreference = 'Stop'
$repo = 'C:\workspace\ChurchGeniusPro'
Set-Location $repo

function Run($cmd) {
    Write-Host ">> git $cmd" -ForegroundColor Cyan
    # git writes normal progress ("Switched to...", "From ...", push output) to stderr; in
    # Windows PowerShell 5.1 that becomes a terminating error under ErrorActionPreference=Stop.
    # Judge success by git's exit code only.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try   { $out = Invoke-Expression "git $cmd 2>&1" | ForEach-Object { "$_" } }
    finally { $ErrorActionPreference = $prev }
    if ($LASTEXITCODE -ne 0) { Write-Host ($out -join "`n") -ForegroundColor Red; throw "git $cmd failed" }
    $out
}

# ── 1. Safety checks ─────────────────────────────────────────────────────────
Run 'fetch origin --prune' | Out-Null
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($branch -ne 'feature1') {
    Write-Host "You are on '$branch'. Switching to feature1 (your changes come with you)." -ForegroundColor Yellow
    Run 'checkout feature1' | Out-Null
}
# feature1 must not be behind origin (e.g. a workflow commit made by Azure)
$behind = (git rev-list --count 'feature1..origin/feature1').Trim()
if ($behind -ne '0') {
    Write-Host "feature1 is $behind commit(s) behind origin/feature1 — pulling (fast-forward only)." -ForegroundColor Yellow
    Run 'pull --ff-only origin feature1' | Out-Null
}

# ── 2. Show what will be committed ───────────────────────────────────────────
Write-Host "`nChanges to commit:" -ForegroundColor Green
git status --short
if (-not (git status --porcelain)) { Write-Host "Nothing to commit."; }
if ($Preview) { Write-Host "`n-Preview: stopping here."; exit 0 }

# ── 3. Commit to feature1 and push ───────────────────────────────────────────
if (git status --porcelain) {
    Run 'add -A' | Out-Null
    git commit -m $Message | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'commit failed' }
}
Run 'push origin feature1' | Out-Host
Write-Host "feature1 pushed: $(git rev-parse --short feature1)" -ForegroundColor Green
if ($NoDeploy) { Write-Host "-NoDeploy: master untouched, nothing deployed."; exit 0 }

# ── 4. Merge feature1 into master and push (this triggers the deployment) ────
Run 'checkout master' | Out-Null
Run 'pull --ff-only origin master' | Out-Null
Write-Host "`nCommits going to production:" -ForegroundColor Green
git log --oneline 'master..feature1'
$merge = git merge --no-edit feature1 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host $merge -ForegroundColor Red
    Write-Host "MERGE CONFLICT — resolve in Eclipse, then: git commit; git push origin master" -ForegroundColor Red
    exit 1
}
Run 'push origin master' | Out-Host
$sha = (git rev-parse --short master).Trim()

# ── 5. Back to feature1, keep it equal to master ─────────────────────────────
Run 'checkout feature1' | Out-Null
Run 'merge --ff-only master' | Out-Null
Run 'push origin feature1' | Out-Null

Write-Host "`nDeployment of $sha started. Watch it here:" -ForegroundColor Green
Write-Host "  https://github.com/churchmanagement/ChurchGeniusPro/actions"
Write-Host "  Azure: Deployment Center > Logs (should show $sha Succeeded, ~4 min)"
Write-Host "  Health: https://churchgeniuspro.net/actuator/health  (expect status UP)"
