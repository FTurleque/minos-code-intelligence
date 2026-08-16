param(
    [Parameter(Mandatory = $true)]
    [string] $Plan
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

public static class MinosJobObjectOwnerV1 {
    private const uint CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private const uint CREATE_SUSPENDED = 0x00000004;
    private const uint CREATE_NO_WINDOW = 0x08000000;
    private const uint STARTF_USESTDHANDLES = 0x00000100;
    private const uint INFINITE = 0xffffffff;

    private const uint JOB_OBJECT_LIMIT_BREAKAWAY_OK = 0x00000800;
    private const uint JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK = 0x00001000;
    private const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private const int JobObjectExtendedLimitInformation = 9;

    [StructLayout(LayoutKind.Sequential)]
    private struct STARTUPINFO {
        public uint cb;
        public IntPtr lpReserved;
        public IntPtr lpDesktop;
        public IntPtr lpTitle;
        public uint dwX;
        public uint dwY;
        public uint dwXSize;
        public uint dwYSize;
        public uint dwXCountChars;
        public uint dwYCountChars;
        public uint dwFillAttribute;
        public uint dwFlags;
        public ushort wShowWindow;
        public ushort cbReserved2;
        public IntPtr lpReserved2;
        public IntPtr hStdInput;
        public IntPtr hStdOutput;
        public IntPtr hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PROCESS_INFORMATION {
        public IntPtr hProcess;
        public IntPtr hThread;
        public uint dwProcessId;
        public uint dwThreadId;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_BASIC_LIMIT_INFORMATION {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IO_COUNTERS {
        public ulong ReadOperationCount;
        public ulong WriteOperationCount;
        public ulong OtherOperationCount;
        public ulong ReadTransferCount;
        public ulong WriteTransferCount;
        public ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
        public IO_COUNTERS IoInfo;
        public UIntPtr ProcessMemoryLimit;
        public UIntPtr JobMemoryLimit;
        public UIntPtr PeakProcessMemoryUsed;
        public UIntPtr PeakJobMemoryUsed;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CreateProcessW(
        string lpApplicationName,
        StringBuilder lpCommandLine,
        IntPtr lpProcessAttributes,
        IntPtr lpThreadAttributes,
        bool bInheritHandles,
        uint dwCreationFlags,
        IntPtr lpEnvironment,
        string lpCurrentDirectory,
        ref STARTUPINFO lpStartupInfo,
        out PROCESS_INFORMATION lpProcessInformation);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr GetStdHandle(int nStdHandle);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr CreateJobObject(IntPtr lpJobAttributes, string lpName);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetInformationJobObject(
        IntPtr hJob,
        int JobObjectInfoClass,
        IntPtr lpJobObjectInfo,
        uint cbJobObjectInfoLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool QueryInformationJobObject(
        IntPtr hJob,
        int JobObjectInfoClass,
        IntPtr lpJobObjectInfo,
        uint cbJobObjectInfoLength,
        IntPtr lpReturnLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AssignProcessToJobObject(IntPtr hJob, IntPtr hProcess);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool IsProcessInJob(IntPtr processHandle, IntPtr jobHandle, out bool result);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool TerminateJobObject(IntPtr hJob, uint exitCode);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint ResumeThread(IntPtr hThread);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WaitForSingleObject(IntPtr hHandle, uint milliseconds);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetExitCodeProcess(IntPtr hProcess, out uint exitCode);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr hObject);

    public static int Run(string[] command, string[] environment, string workingDirectory) {
        if (command == null || command.Length == 0) throw new ArgumentException("command is empty");
        if (environment == null) throw new ArgumentNullException("environment");

        IntPtr environmentBlock = IntPtr.Zero;
        IntPtr job = IntPtr.Zero;
        PROCESS_INFORMATION pi = new PROCESS_INFORMATION();
        try {
            // The ownership authority exists before the provider process exists.
            job = CreateJobObject(IntPtr.Zero, null);
            if (job == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            ConfigureAndVerifyJob(job);

            STARTUPINFO startup = new STARTUPINFO();
            startup.cb = (uint)Marshal.SizeOf(typeof(STARTUPINFO));
            startup.dwFlags = STARTF_USESTDHANDLES;
            startup.hStdInput = GetStdHandle(-10);
            startup.hStdOutput = GetStdHandle(-11);
            startup.hStdError = GetStdHandle(-12);

            StringBuilder commandLine = new StringBuilder(BuildCommandLine(command));
            environmentBlock = BuildEnvironmentBlock(environment);
            uint flags = CREATE_UNICODE_ENVIRONMENT | CREATE_SUSPENDED | CREATE_NO_WINDOW;
            if (!CreateProcessW(
                    command[0],
                    commandLine,
                    IntPtr.Zero,
                    IntPtr.Zero,
                    true,
                    flags,
                    environmentBlock,
                    workingDirectory,
                    ref startup,
                    out pi)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }

            // The child is still suspended here and therefore cannot have spawned anything.
            if (!AssignProcessToJobObject(job, pi.hProcess)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            bool inJob;
            if (!IsProcessInJob(pi.hProcess, job, out inJob)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            if (!inJob) throw new InvalidOperationException("provider is not a member of the MINOS Job Object");

            if (ResumeThread(pi.hThread) == 0xffffffff) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            uint wait = WaitForSingleObject(pi.hProcess, INFINITE);
            if (wait != 0) throw new Win32Exception(Marshal.GetLastWin32Error());
            uint exitCode;
            if (!GetExitCodeProcess(pi.hProcess, out exitCode)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            return unchecked((int)exitCode);
        } finally {
            if (job != IntPtr.Zero) {
                // Normal exit, failure, or wrapper teardown: descendants cannot outlive this handle.
                TerminateJobObject(job, 1);
            }
            if (pi.hThread != IntPtr.Zero) CloseHandle(pi.hThread);
            if (pi.hProcess != IntPtr.Zero) CloseHandle(pi.hProcess);
            if (job != IntPtr.Zero) CloseHandle(job);
            if (environmentBlock != IntPtr.Zero) Marshal.FreeHGlobal(environmentBlock);
        }
    }

    private static void ConfigureAndVerifyJob(IntPtr job) {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        int size = Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
        IntPtr buffer = Marshal.AllocHGlobal(size);
        try {
            Marshal.StructureToPtr(limits, buffer, false);
            if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, buffer, (uint)size)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            Marshal.StructureToPtr(new JOBOBJECT_EXTENDED_LIMIT_INFORMATION(), buffer, true);
            if (!QueryInformationJobObject(job, JobObjectExtendedLimitInformation, buffer, (uint)size, IntPtr.Zero)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            JOBOBJECT_EXTENDED_LIMIT_INFORMATION applied =
                (JOBOBJECT_EXTENDED_LIMIT_INFORMATION)Marshal.PtrToStructure(
                    buffer, typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
            uint flags = applied.BasicLimitInformation.LimitFlags;
            if ((flags & JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE) == 0) {
                throw new InvalidOperationException("KILL_ON_JOB_CLOSE was not applied");
            }
            if ((flags & JOB_OBJECT_LIMIT_BREAKAWAY_OK) != 0
                    || (flags & JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK) != 0) {
                throw new InvalidOperationException("MINOS ownership Job Object must not allow breakaway");
            }
        } finally {
            Marshal.FreeHGlobal(buffer);
        }
    }

    private static IntPtr BuildEnvironmentBlock(string[] environment) {
        StringBuilder block = new StringBuilder();
        foreach (string entry in environment) {
            if (String.IsNullOrEmpty(entry) || entry.IndexOf('\0') >= 0) {
                throw new ArgumentException("invalid environment entry");
            }
            block.Append(entry);
            block.Append('\0');
        }
        block.Append('\0');
        return Marshal.StringToHGlobalUni(block.ToString());
    }

    private static string BuildCommandLine(string[] args) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < args.Length; i++) {
            if (i > 0) result.Append(' ');
            result.Append(QuoteArgument(args[i]));
        }
        return result.ToString();
    }

    private static string QuoteArgument(string value) {
        if (value == null) throw new ArgumentNullException("value");
        if (value.Length == 0) return "\"\"";
        bool quote = false;
        foreach (char c in value) {
            if (char.IsWhiteSpace(c) || c == '"') { quote = true; break; }
        }
        if (!quote) return value;
        StringBuilder result = new StringBuilder();
        result.Append('"');
        int slashes = 0;
        foreach (char c in value) {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') {
                result.Append('\\', slashes * 2 + 1);
                result.Append('"');
                slashes = 0;
                continue;
            }
            result.Append('\\', slashes);
            slashes = 0;
            result.Append(c);
        }
        result.Append('\\', slashes * 2);
        result.Append('"');
        return result.ToString();
    }
}
'@

function Read-Plan([string] $Path) {
    $values = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($Path, [System.Text.Encoding]::UTF8)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -lt 1) { throw "Invalid ownership plan line: $line" }
        $values[$line.Substring(0, $separator)] = $line.Substring($separator + 1)
    }
    return $values
}

function Decode([string] $Value) {
    return [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Value))
}

function Read-List($Values, [string] $Prefix) {
    $count = [int]$Values["$Prefix.count"]
    $result = New-Object System.Collections.Generic.List[string]
    for ($index = 0; $index -lt $count; $index++) {
        $result.Add((Decode ([string]$Values["$Prefix.$index"])))
    }
    return [string[]]$result.ToArray()
}

function Read-Environment($Values) {
    $count = [int]$Values['environment.count']
    $result = New-Object System.Collections.Generic.List[string]
    for ($index = 0; $index -lt $count; $index++) {
        $key = Decode ([string]$Values["environment.$index.key"])
        $value = Decode ([string]$Values["environment.$index.value"])
        if ([string]::IsNullOrEmpty($key) -or $key.IndexOf([char]0) -ge 0 -or $value.IndexOf([char]0) -ge 0) {
            throw 'Invalid provider environment entry in ownership plan'
        }
        $result.Add($key + '=' + $value)
    }
    return [string[]]$result.ToArray()
}

$values = Read-Plan $Plan
$command = Read-List $values 'command'
$environment = Read-Environment $values
$workingDirectory = Decode ([string]$values['working'])
$exitCode = [MinosJobObjectOwnerV1]::Run($command, $environment, $workingDirectory)
exit $exitCode
