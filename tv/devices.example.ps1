# Copy to devices.local.ps1 (not committed) and fill in your TVs.
# Serial: adb shell getprop ro.serialno    Address: the TV's IP with port 5555 (network debugging on).
# The first TV is the default; install.ps1 -Xiaomi and xiaomi-debloat.ps1 use the one named 'xiaomi'.
$Devices = [ordered]@{
  tv     = @{ Serial = 'YOUR_TV_SERIAL'; Address = '192.168.1.50:5555' }
  xiaomi = @{ Serial = 'SECOND_TV_SERIAL'; Address = '192.168.1.51:5555' }
}
# Where the undo logs of debloat.ps1 go (default: tv\undo).
# $UndoFolder = 'D:\tv-undo'
