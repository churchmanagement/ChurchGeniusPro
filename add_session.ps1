$base = 'C:\workspace\ChurchGeniusPro\ChurchGeniusPro\src\main\resources\static'

$files = @(
    'tax-report.html',
    'expense-report.html',
    'income-report.html',
    'financial-report.html',
    'transactions-report.html',
    'home.html',
    'viewUsers.html',
    'group.html',
    'viewFamily.html',
    'transactionType.html',
    'source.html',
    'purpose.html',
    'oneReminders.html',
    'notifyEmail.html',
    'memberType.html',
    'members.html',
    'meetingType.html',
    'meeting.html',
    'income.html',
    'fund.html',
    'family.html',
    'expense.html',
    'eventReminders.html',
    'eventCalendar.html',
    'autoReminders.html'
)

$scriptTag = '<script src="/session.js"></script>'
$enc = [System.Text.Encoding]::UTF8

foreach ($f in $files) {
    $fullPath = Join-Path $base $f
    if (-not (Test-Path $fullPath)) {
        Write-Host "MISSING: $fullPath"
        continue
    }
    $content = [System.IO.File]::ReadAllText($fullPath, $enc)
    if ($content -notmatch '/session\.js') {
        $content = $content -replace '</body>', ($scriptTag + "`n</body>")
        [System.IO.File]::WriteAllText($fullPath, $content, $enc)
        Write-Host "Updated: $f"
    } else {
        Write-Host "Already has session.js: $f"
    }
}

Write-Host "Done."
