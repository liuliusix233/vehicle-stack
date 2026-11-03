# DJI Power Monitor - Analytics Reader (PowerShell)
# Runs every 30 seconds, parses analytics JSON from DJI Home, pushes to HA.
# Place this in C:\Users\<USER>\power_monitor\ and run as a background job.
# Replace the old fetch.ps1 + sync.sh approach.

$ADB = "C:\Users\<USER>\adb-tools\adb.exe"
$DEVICE = "127.0.0.1:5555"
$ANALYTICS_FILE = "/sdcard/Android/data/com.dji.home/files/analytics"
$HA_URL = "http://<HA_HOST>:8123"
$HA_TOKEN = ""

# Read HA token from config
$ConfigPath = "C:\Users\<USER>\ha_config.json"
if (Test-Path $ConfigPath) {
    $HA_TOKEN = (Get-Content $ConfigPath | ConvertFrom-Json).token
}

# Connect to emulator
& $ADB connect $DEVICE | Out-Null

Write-Host "DJI Power Analytics Reader started at $(Get-Date -Format 'HH:mm:ss')"
Write-Host "Reading every 30 seconds..."

while ($true) {
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    
    # Read the last analytics line with power data
    $raw = & $ADB -s $DEVICE shell "grep dy_soc $ANALYTICS_FILE 2>/dev/null | tail -1"
    
    if ($raw -match '"eventvalue":"({[^}]+})"') {
        try {
            $json = $matches[1] | ConvertFrom-Json
            
            $soc = [int]$json.dy_soc / 10
            $voltage = [int]$json.dy_sdc_voltage / 100
            $soh = if ($json.dy_soh) { [int]$json.dy_soh / 10 } else { "?" }
            $discharge = if ($json.dy_discharge_power_total) { $json.dy_discharge_power_total } else { 0 }
            $charge = if ($json.dy_charge_power_total) { $json.dy_charge_power_total } else { 0 }
            $firmware = if ($json.dy_device_version) { $json.dy_device_version } else { "?" }
            
            Write-Host "[$timestamp] 🔋 ${soc}% | 🔌 ${voltage}V | ⚡ ${discharge}W (放电) / ${charge}W (充电)"
            
            # Push to HA if token is configured
            if ($HA_TOKEN) {
                $headers = @{
                    "Authorization" = "Bearer $HA_TOKEN"
                    "Content-Type" = "application/json"
                }
                
                # Battery %
                $body = @{state="$soc"; attributes=@{unit_of_measurement="%"; friendly_name="户外电源电量"; icon="mdi:battery"; device_class="battery"}} | ConvertTo-Json
                Invoke-RestMethod -Uri "$HA_URL/api/states/sensor.cadillac_battery" -Method Post -Headers $headers -Body $body -ErrorAction SilentlyContinue | Out-Null
                
                # Voltage
                $body = @{state="$voltage"; attributes=@{unit_of_measurement="V"; friendly_name="汽车电瓶电压"; icon="mdi:car-battery"; device_class="voltage"}} | ConvertTo-Json
                Invoke-RestMethod -Uri "$HA_URL/api/states/sensor.cadillac_car_voltage" -Method Post -Headers $headers -Body $body -ErrorAction SilentlyContinue | Out-Null
                
                # Discharge power
                $body = @{state="$discharge"; attributes=@{unit_of_measurement="W"; friendly_name="放电功率"; icon="mdi:flash-outline"}} | ConvertTo-Json
                Invoke-RestMethod -Uri "$HA_URL/api/states/sensor.cadillac_discharge_power" -Method Post -Headers $headers -Body $body -ErrorAction SilentlyContinue | Out-Null
                
                # Last update timestamp
                $body = @{state="$timestamp"; attributes=@{friendly_name="最后更新"; icon="mdi:update"}} | ConvertTo-Json
                Invoke-RestMethod -Uri "$HA_URL/api/states/sensor.cadillac_last_update" -Method Post -Headers $headers -Body $body -ErrorAction SilentlyContinue | Out-Null
            }
        } catch {
            Write-Warning "Parse error: $_"
        }
    } else {
        Write-Host "[$timestamp] ⚠️ No data from DJI Home. Check if app is open."
    }
    
    Start-Sleep -Seconds 30
}
