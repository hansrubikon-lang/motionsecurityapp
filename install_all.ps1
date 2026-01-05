$adb = "C:\Users\Kalif\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$apk = "D:\motioncam\TestStreamApp\app\build\outputs\apk\debug\app-debug.apk"

$devices = & $adb devices | Select-String "`tdevice$" | ForEach-Object {
    ($_ -split "`t")[0]
}

foreach ($id in $devices) {
    Write-Host "Installing on $id"
    & $adb -s $id uninstall com.example.teststreamapp | Out-Null
    & $adb -s $id install -r $apk
}
