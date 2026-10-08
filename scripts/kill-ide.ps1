$targets = @()
$targets += Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*platform.prefix=Rider*' }
$targets += Get-CimInstance Win32_Process -Filter "Name='dotnet.exe'" | Where-Object { $_.CommandLine -like '*resharper-host*' }
$targets += Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'Rider.Backend*' }
$targets | ForEach-Object {
    Write-Output ("tree-kill " + $_.Name + " " + $_.ProcessId)
    & taskkill.exe /PID $_.ProcessId /T /F 2>&1 | Out-Null
}
Start-Sleep -Seconds 2
$left = @()
$left += Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*platform.prefix=Rider*' }
$left += Get-CimInstance Win32_Process -Filter "Name='dotnet.exe'" | Where-Object { $_.CommandLine -like '*resharper-host*' }
Write-Output ("pozostalo: " + @($left).Count)
