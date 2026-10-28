<#
.SYNOPSIS
Starts the game for testing on a separate, invisible Windows desktop, so it never shows a window,
takes focus or appears in the taskbar.

.DESCRIPTION
Runs `gradlew runClient` from the given checkout on the desktop "RadianceHarness". The game inherits
that desktop. Drive it with the file-driven test harness (-Harness, see TestHarness.java): write
commands to <Harness>/commands.txt and read screenshots and log.txt from the same directory. Sound
is muted by the harness. Prints the process id of the launched gradle wrapper.

.EXAMPLE
./tools/run-hidden.ps1 -Checkout C:\dev\Radiance-test -RunDir run2 -World "New World" -Harness C:\dev\harness -Log C:\dev\run-hidden.log
#>
param(
    [string]$Checkout = (Get-Location).Path,
    [string]$RunDir = "run",
    [string]$World = "",
    [Parameter(Mandatory = $true)][string]$Harness,
    [string]$Log = "$env:TEMP\radiance-hidden.log",
    [string]$ExtraArgs = ""
)

Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class HiddenDesktop {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct STARTUPINFO {
        public int cb; public string lpReserved; public string lpDesktop; public string lpTitle;
        public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
        public short wShowWindow, cbReserved2; public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
    }
    [StructLayout(LayoutKind.Sequential)]
    public struct PROCESS_INFORMATION { public IntPtr hProcess, hThread; public int dwProcessId, dwThreadId; }
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern IntPtr CreateDesktop(string name, IntPtr device, IntPtr mode, int flags, uint access, IntPtr attributes);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool CreateProcess(string app, string commandLine, IntPtr pa, IntPtr ta, bool inherit,
        uint flags, IntPtr env, string cwd, ref STARTUPINFO si, out PROCESS_INFORMATION pi);
}
"@

$desktopName = "RadianceHarness"
# GENERIC_ALL; returns the existing desktop if it is already there.
$desktop = [HiddenDesktop]::CreateDesktop($desktopName, [IntPtr]::Zero, [IntPtr]::Zero, 0, 0x10000000, [IntPtr]::Zero)
if ($desktop -eq [IntPtr]::Zero) { throw "CreateDesktop failed: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }

$gradleArgs = "--no-daemon runClient -PradianceRunDir=`"$RunDir`" -PradianceHarness=`"$Harness`""
if ($World) { $gradleArgs += " `"-PquickPlay=$World`"" }
if ($ExtraArgs) { $gradleArgs += " $ExtraArgs" }
$gradlew = Join-Path $Checkout "gradlew.bat"
$commandLine = "cmd.exe /s /c `"`"$gradlew`" $gradleArgs > `"$Log`" 2>&1`""

$si = New-Object HiddenDesktop+STARTUPINFO
$si.cb = [Runtime.InteropServices.Marshal]::SizeOf($si)
$si.lpDesktop = $desktopName
$pi = New-Object HiddenDesktop+PROCESS_INFORMATION
# CREATE_NO_WINDOW keeps even the console off the hidden desktop's (invisible) screen.
if (-not [HiddenDesktop]::CreateProcess([NullString]::Value, $commandLine, [IntPtr]::Zero, [IntPtr]::Zero, $false, 0x08000000,
        [IntPtr]::Zero, $Checkout, [ref]$si, [ref]$pi)) {
    throw "CreateProcess failed: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
}
$pi.dwProcessId
