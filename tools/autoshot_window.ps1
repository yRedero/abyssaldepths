# Keeps the autoshot game out of the way (started by tools/autoshot.sh, in the background):
# waits for the game's window (the java process started with the autoshot run's arguments), moves it to a second
# monitor (the largest one that is not the main one, or the one named in $env:AUTOSHOT_SCREEN, e.g. DISPLAY2)
# filling its working area, and gives the focus back to whatever window had it, every time the game takes it in
# its first minute. Other Minecraft windows (your own game) are never touched.
param([int]$Seconds = 1600)

Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class AutoshotWin {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    public delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc f, IntPtr l);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }

    public static IntPtr WindowOf(uint pid) {
        IntPtr found = IntPtr.Zero;
        EnumWindows((h, l) => {
            uint p; GetWindowThreadProcessId(h, out p);
            if (p == pid && IsWindowVisible(h)) { found = h; return false; }
            return true;
        }, IntPtr.Zero);
        return found;
    }

    /** Gives the focus back (Windows lets a background program do that only right after a key press). */
    public static void Focus(IntPtr h) {
        keybd_event(0x12, 0, 0, UIntPtr.Zero);
        keybd_event(0x12, 0, 2, UIntPtr.Zero);
        SetForegroundWindow(h);
    }
}
"@

$screens = [System.Windows.Forms.Screen]::AllScreens
$target = if ($env:AUTOSHOT_SCREEN) { $screens | Where-Object { $_.DeviceName -like "*$($env:AUTOSHOT_SCREEN)" } | Select-Object -First 1 } else { $null }
if (-not $target) {
    $target = $screens | Where-Object { -not $_.Primary } | Sort-Object { $_.Bounds.Width * $_.Bounds.Height } -Descending | Select-Object -First 1
}
$area = if ($target) { $target.WorkingArea } else { $null }
$userWindow = [AutoshotWin]::GetForegroundWindow()
$start = Get-Date
$gamePid = $null
$lastPlaced = $null

while (((Get-Date) - $start).TotalSeconds -lt $Seconds) {
    Start-Sleep -Milliseconds 250
    if (-not $gamePid) {
        $proc = Get-CimInstance Win32_Process -Filter "Name like 'java%'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -like '*autoshotRunVmArgs*' } | Select-Object -First 1
        if ($proc) { $gamePid = [uint32]$proc.ProcessId }
        continue
    }
    if (-not (Get-Process -Id $gamePid -ErrorAction SilentlyContinue)) { break } # the game is done
    $h = [AutoshotWin]::WindowOf($gamePid)
    if ($h -eq [IntPtr]::Zero) { continue }
    if ($area) { # on the other monitor (moved again if the game put it back, e.g. its loading window making way for the game's)
        $r = New-Object AutoshotWin+RECT
        [void][AutoshotWin]::GetWindowRect($h, [ref]$r)
        if ($r.Left -ne $area.X -or $r.Top -ne $area.Y -or ($r.Right - $r.Left) -ne $area.Width) {
            [void][AutoshotWin]::ShowWindow($h, 1) # (restored, not maximized, to be moved)
            [void][AutoshotWin]::SetWindowPos($h, [IntPtr]::Zero, $area.X, $area.Y, $area.Width, $area.Height, 0x0014) # no z-order change, no activation
            $lastPlaced = Get-Date
        }
    }
    if (-not $lastPlaced) { $lastPlaced = Get-Date }
    # it took the focus while starting up: back to the user's window (later, a click into it is the user's own)
    if ([AutoshotWin]::GetForegroundWindow() -eq $h -and ((Get-Date) - $lastPlaced).TotalSeconds -lt 60 -and $userWindow -ne [IntPtr]::Zero) {
        [AutoshotWin]::Focus($userWindow)
    }
}
