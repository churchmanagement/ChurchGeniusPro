$base = 'C:\workspace\ChurchGeniusPro\ChurchGeniusPro\src\main\resources\static'
$enc  = [System.Text.Encoding]::UTF8

# Pages whose .user-name and .user-role divs have no id yet
$files = @(
    'income.html',
    'fund.html',
    'members.html',
    'meeting.html',
    'autoReminders.html',
    'meetingType.html',
    'group.html',
    'family.html',
    'purpose.html',
    'notifyEmail.html',
    'expense.html',
    'eventReminders.html',
    'oneReminders.html',
    'memberType.html',
    'source.html',
    'viewFamily.html',
    'transactionType.html',
    'eventCalendar.html'
)

foreach ($f in $files) {
    $path = Join-Path $base $f
    if (-not (Test-Path $path)) { Write-Host "MISSING: $f"; continue }

    $content = [System.IO.File]::ReadAllText($path, $enc)

    # Add id to .user-name div (only when it does NOT already carry an id)
    # Pattern: <div class="user-name">   (no id= present right after class=)
    $content = $content -replace '<div class="user-name">',
                                  '<div class="user-name" id="sidebarUserName">'

    # Add id to .user-role div — the static text may be "Admin" or anything
    # Pattern: <div class="user-role">   (no id= present)
    $content = $content -replace '<div class="user-role">',
                                  '<div class="user-role" id="sidebarUserRole">'

    [System.IO.File]::WriteAllText($path, $content, $enc)
    Write-Host "Updated: $f"
}

Write-Host "Done."
