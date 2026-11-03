# esp_capture.ps1 — 读取 ESP32 串口(COM6@115200)并追加时间戳到日志
# 用法: powershell -WindowStyle Hidden -File C:\Users\<USER>\esp_capture.ps1
$portName = "COM6"
$baud = 115200
$logPath = "C:\Users\<USER>\esp_capture.log"

$port = New-Object System.IO.Ports.SerialPort $portName,$baud,None,8,One
$port.ReadTimeout = 1000
try {
  $port.Open()
} catch {
  Add-Content $logPath "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') FAILED to open $portName"
  exit 1
}
Add-Content $logPath "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') serial capture started on $portName @$baud"
while ($true) {
  try {
    $line = $port.ReadLine()
  } catch { continue }
  Add-Content $logPath "$(Get-Date -Format 'HH:mm:ss.fff') $line"
}
