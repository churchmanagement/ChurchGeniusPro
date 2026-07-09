$projectRoot = 'C:\workspace\ChurchGeniusPro\ChurchGeniusPro'
$items = Get-ChildItem $projectRoot -Filter '*.html' -File
foreach ($item in $items) {
    if ($item.Length -eq 0) {
        Remove-Item $item.FullName
        Write-Host ('Removed empty: ' + $item.Name)
    }
}
Write-Host 'Cleanup done.'
