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

public static class MinosAppContainerNativeV3 {
    private const uint EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private const uint CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private const uint CREATE_SUSPENDED = 0x00000004;
    private const uint CREATE_NO_WINDOW = 0x08000000;
    private const uint STARTF_USESTDHANDLES = 0x00000100;
    private const long PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES = 0x00020009;
    private const uint TOKEN_QUERY = 0x0008;
    private const uint SE_GROUP_ENABLED = 0x00000004;
    private const int TokenIsAppContainer = 29;
    private const uint INFINITE = 0xffffffff;

    private const uint JOB_OBJECT_LIMIT_JOB_TIME = 0x00000004;
    private const uint JOB_OBJECT_LIMIT_ACTIVE_PROCESS = 0x00000008;
    private const uint JOB_OBJECT_LIMIT_JOB_MEMORY = 0x00000200;
    private const uint JOB_OBJECT_LIMIT_DIE_ON_UNHANDLED_EXCEPTION = 0x00000400;
    private const uint JOB_OBJECT_LIMIT_BREAKAWAY_OK = 0x00000800;
    private const uint JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK = 0x00001000;
    private const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private const uint JOB_OBJECT_CPU_RATE_CONTROL_ENABLE = 0x1;
    private const uint JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP = 0x4;
    private const int JobObjectBasicAccountingInformation = 1;
    private const int JobObjectExtendedLimitInformation = 9;
    private const int JobObjectCpuRateControlInformation = 15;

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
    private struct STARTUPINFOEX {
        public STARTUPINFO StartupInfo;
        public IntPtr lpAttributeList;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PROCESS_INFORMATION {
        public IntPtr hProcess;
        public IntPtr hThread;
        public uint dwProcessId;
        public uint dwThreadId;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SECURITY_CAPABILITIES {
        public IntPtr AppContainerSid;
        public IntPtr Capabilities;
        public uint CapabilityCount;
        public uint Reserved;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SID_AND_ATTRIBUTES {
        public IntPtr Sid;
        public uint Attributes;
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

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_BASIC_ACCOUNTING_INFORMATION {
        public long TotalUserTime;
        public long TotalKernelTime;
        public long ThisPeriodTotalUserTime;
        public long ThisPeriodTotalKernelTime;
        public uint TotalPageFaultCount;
        public uint TotalProcesses;
        public uint ActiveProcesses;
        public uint TotalTerminatedProcesses;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_CPU_RATE_CONTROL_INFORMATION {
        public uint ControlFlags;
        public uint CpuRate;
    }

    [DllImport("userenv.dll", CharSet = CharSet.Unicode)]
    private static extern int CreateAppContainerProfile(
        string pszAppContainerName,
        string pszDisplayName,
        string pszDescription,
        IntPtr pCapabilities,
        uint dwCapabilityCount,
        out IntPtr ppSidAppContainerSid);

    [DllImport("userenv.dll", CharSet = CharSet.Unicode)]
    private static extern int DeleteAppContainerProfile(string pszAppContainerName);

    [DllImport("userenv.dll", CharSet = CharSet.Unicode)]
    private static extern int DeriveAppContainerSidFromAppContainerName(
        string pszAppContainerName,
        out IntPtr ppsidAppContainerSid);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool ConvertSidToStringSid(IntPtr Sid, out IntPtr StringSid);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool ConvertStringSidToSid(string StringSid, out IntPtr Sid);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool OpenProcessToken(IntPtr ProcessHandle, uint DesiredAccess, out IntPtr TokenHandle);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool GetTokenInformation(
        IntPtr TokenHandle,
        int TokenInformationClass,
        out int TokenInformation,
        int TokenInformationLength,
        out int ReturnLength);

    [DllImport("advapi32.dll")]
    private static extern IntPtr FreeSid(IntPtr pSid);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LocalFree(IntPtr hMem);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool InitializeProcThreadAttributeList(
        IntPtr lpAttributeList,
        int dwAttributeCount,
        int dwFlags,
        ref IntPtr lpSize);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool UpdateProcThreadAttribute(
        IntPtr lpAttributeList,
        uint dwFlags,
        IntPtr Attribute,
        IntPtr lpValue,
        IntPtr cbSize,
        IntPtr lpPreviousValue,
        IntPtr lpReturnSize);

    [DllImport("kernel32.dll")]
    private static extern void DeleteProcThreadAttributeList(IntPtr lpAttributeList);

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
        ref STARTUPINFOEX lpStartupInfo,
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
    private static extern bool IsProcessInJob(IntPtr ProcessHandle, IntPtr JobHandle, out bool Result);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool TerminateJobObject(IntPtr hJob, uint uExitCode);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint ResumeThread(IntPtr hThread);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WaitForSingleObject(IntPtr hHandle, uint dwMilliseconds);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetExitCodeProcess(IntPtr hProcess, out uint lpExitCode);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr hObject);

    public static string CreateProfile(string name) {
        IntPtr sid;
        int hr = CreateAppContainerProfile(
            name,
            name,
            "MINOS isolated provider worker",
            IntPtr.Zero,
            0,
            out sid);
        if (hr < 0) Marshal.ThrowExceptionForHR(hr);
        try {
            IntPtr stringSid;
            if (!ConvertSidToStringSid(sid, out stringSid)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            try {
                return Marshal.PtrToStringUni(stringSid);
            } finally {
                LocalFree(stringSid);
            }
        } finally {
            FreeSid(sid);
        }
    }

    public static void DeleteProfile(string name) {
        int hr = DeleteAppContainerProfile(name);
        if (hr < 0) Marshal.ThrowExceptionForHR(hr);
    }

    public static int RunSandbox(
        string profileName,
        string[] command,
        string[] environment,
        string workingDirectory,
        ulong jobMemoryBytes,
        uint activeProcesses,
        uint cpuRate,
        ulong jobCpuSeconds,
        bool allowNetwork) {
        if (command == null || command.Length == 0) throw new ArgumentException("command is empty");
        if (environment == null) throw new ArgumentNullException("environment");
        IntPtr sid = IntPtr.Zero;
        IntPtr attributeList = IntPtr.Zero;
        IntPtr securityCapabilitiesPtr = IntPtr.Zero;
        IntPtr capabilityArray = IntPtr.Zero;
        IntPtr internetClientSid = IntPtr.Zero;
        IntPtr environmentBlock = IntPtr.Zero;
        IntPtr job = IntPtr.Zero;
        PROCESS_INFORMATION pi = new PROCESS_INFORMATION();
        try {
            int hr = DeriveAppContainerSidFromAppContainerName(profileName, out sid);
            if (hr < 0) Marshal.ThrowExceptionForHR(hr);

            if (allowNetwork) {
                if (!ConvertStringSidToSid("S-1-15-3-1", out internetClientSid)) {
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                }
                SID_AND_ATTRIBUTES internetClient = new SID_AND_ATTRIBUTES {
                    Sid = internetClientSid,
                    Attributes = SE_GROUP_ENABLED
                };
                capabilityArray = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(SID_AND_ATTRIBUTES)));
                Marshal.StructureToPtr(internetClient, capabilityArray, false);
            }

            SECURITY_CAPABILITIES capabilities = new SECURITY_CAPABILITIES {
                AppContainerSid = sid,
                Capabilities = capabilityArray,
                CapabilityCount = allowNetwork ? 1u : 0u,
                Reserved = 0
            };
            securityCapabilitiesPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(SECURITY_CAPABILITIES)));
            Marshal.StructureToPtr(capabilities, securityCapabilitiesPtr, false);

            IntPtr attributeBytes = IntPtr.Zero;
            InitializeProcThreadAttributeList(IntPtr.Zero, 1, 0, ref attributeBytes);
            if (attributeBytes == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            attributeList = Marshal.AllocHGlobal(attributeBytes);
            if (!InitializeProcThreadAttributeList(attributeList, 1, 0, ref attributeBytes)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            if (!UpdateProcThreadAttribute(
                    attributeList,
                    0,
                    new IntPtr(PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES),
                    securityCapabilitiesPtr,
                    new IntPtr(Marshal.SizeOf(typeof(SECURITY_CAPABILITIES))),
                    IntPtr.Zero,
                    IntPtr.Zero)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }

            // The job boundary exists and is fully configured before the contained process is even
            // created, so there is no window in which an untrusted child could be spawned outside it.
            job = CreateJobObject(IntPtr.Zero, null);
            if (job == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            ConfigureJob(job, jobMemoryBytes, activeProcesses, cpuRate, jobCpuSeconds);
            VerifyJob(job, jobMemoryBytes, activeProcesses);

            STARTUPINFOEX startup = new STARTUPINFOEX();
            startup.StartupInfo.cb = (uint)Marshal.SizeOf(typeof(STARTUPINFOEX));
            startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
            startup.StartupInfo.hStdInput = GetStdHandle(-10);
            startup.StartupInfo.hStdOutput = GetStdHandle(-11);
            startup.StartupInfo.hStdError = GetStdHandle(-12);
            startup.lpAttributeList = attributeList;

            StringBuilder commandLine = new StringBuilder(BuildCommandLine(command));
            environmentBlock = BuildEnvironmentBlock(environment);
            uint flags = EXTENDED_STARTUPINFO_PRESENT
                    | CREATE_UNICODE_ENVIRONMENT
                    | CREATE_SUSPENDED
                    | CREATE_NO_WINDOW;
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

            // The process is still suspended: it cannot have created any descendant yet.
            IntPtr token;
            if (!OpenProcessToken(pi.hProcess, TOKEN_QUERY, out token)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            try {
                int isAppContainer;
                int returned;
                if (!GetTokenInformation(
                        token,
                        TokenIsAppContainer,
                        out isAppContainer,
                        sizeof(int),
                        out returned)) {
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                }
                if (isAppContainer != 1) {
                    throw new InvalidOperationException("child token is not an AppContainer token");
                }
            } finally {
                CloseHandle(token);
            }

            if (!AssignProcessToJobObject(job, pi.hProcess)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            bool inJob;
            if (!IsProcessInJob(pi.hProcess, job, out inJob)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            if (!inJob) {
                throw new InvalidOperationException("sandbox process is not a member of the MINOS job object");
            }
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
                // No descendant may outlive the job, whatever the outcome was.
                TerminateJobObject(job, 1);
            }
            if (pi.hThread != IntPtr.Zero) CloseHandle(pi.hThread);
            if (pi.hProcess != IntPtr.Zero) CloseHandle(pi.hProcess);
            if (job != IntPtr.Zero) CloseHandle(job);
            if (environmentBlock != IntPtr.Zero) Marshal.FreeHGlobal(environmentBlock);
            if (attributeList != IntPtr.Zero) {
                DeleteProcThreadAttributeList(attributeList);
                Marshal.FreeHGlobal(attributeList);
            }
            if (securityCapabilitiesPtr != IntPtr.Zero) Marshal.FreeHGlobal(securityCapabilitiesPtr);
            if (capabilityArray != IntPtr.Zero) Marshal.FreeHGlobal(capabilityArray);
            if (internetClientSid != IntPtr.Zero) LocalFree(internetClientSid);
            if (sid != IntPtr.Zero) FreeSid(sid);
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

    private static void ConfigureJob(
        IntPtr job,
        ulong memoryBytes,
        uint activeProcesses,
        uint cpuRate,
        ulong jobCpuSeconds) {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_ACTIVE_PROCESS
                | JOB_OBJECT_LIMIT_JOB_MEMORY
                | JOB_OBJECT_LIMIT_JOB_TIME
                | JOB_OBJECT_LIMIT_DIE_ON_UNHANDLED_EXCEPTION
                | JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        limits.BasicLimitInformation.ActiveProcessLimit = activeProcesses;
        limits.BasicLimitInformation.PerJobUserTimeLimit = checked((long)(jobCpuSeconds * 10000000UL));
        limits.JobMemoryLimit = new UIntPtr(memoryBytes);
        IntPtr limitsPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION)));
        try {
            Marshal.StructureToPtr(limits, limitsPtr, false);
            if (!SetInformationJobObject(
                    job,
                    JobObjectExtendedLimitInformation,
                    limitsPtr,
                    (uint)Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION)))) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
        } finally {
            Marshal.FreeHGlobal(limitsPtr);
        }

        JOBOBJECT_CPU_RATE_CONTROL_INFORMATION cpu = new JOBOBJECT_CPU_RATE_CONTROL_INFORMATION {
            ControlFlags = JOB_OBJECT_CPU_RATE_CONTROL_ENABLE | JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP,
            CpuRate = cpuRate
        };
        IntPtr cpuPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(JOBOBJECT_CPU_RATE_CONTROL_INFORMATION)));
        try {
            Marshal.StructureToPtr(cpu, cpuPtr, false);
            if (!SetInformationJobObject(
                    job,
                    JobObjectCpuRateControlInformation,
                    cpuPtr,
                    (uint)Marshal.SizeOf(typeof(JOBOBJECT_CPU_RATE_CONTROL_INFORMATION)))) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
        } finally {
            Marshal.FreeHGlobal(cpuPtr);
        }
    }

    /** Reads the limits back from the kernel: a silently ignored limit must never be claimed. */
    private static void VerifyJob(IntPtr job, ulong memoryBytes, uint activeProcesses) {
        int size = Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
        IntPtr buffer = Marshal.AllocHGlobal(size);
        try {
            if (!QueryInformationJobObject(
                    job,
                    JobObjectExtendedLimitInformation,
                    buffer,
                    (uint)size,
                    IntPtr.Zero)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            JOBOBJECT_EXTENDED_LIMIT_INFORMATION applied =
                (JOBOBJECT_EXTENDED_LIMIT_INFORMATION)Marshal.PtrToStructure(
                    buffer, typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
            uint flags = applied.BasicLimitInformation.LimitFlags;
            if ((flags & JOB_OBJECT_LIMIT_ACTIVE_PROCESS) == 0
                || (flags & JOB_OBJECT_LIMIT_JOB_MEMORY) == 0
                || (flags & JOB_OBJECT_LIMIT_JOB_TIME) == 0
                || (flags & JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE) == 0) {
                throw new InvalidOperationException("MINOS job object limits were not applied: flags=" + flags);
            }
            if ((flags & JOB_OBJECT_LIMIT_BREAKAWAY_OK) != 0
                || (flags & JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK) != 0) {
                throw new InvalidOperationException("MINOS job object must never allow breakaway");
            }
            if (applied.BasicLimitInformation.ActiveProcessLimit != activeProcesses) {
                throw new InvalidOperationException("MINOS job active process limit was not applied");
            }
            if (applied.JobMemoryLimit.ToUInt64() != memoryBytes) {
                throw new InvalidOperationException("MINOS job memory limit was not applied");
            }
        } finally {
            Marshal.FreeHGlobal(buffer);
        }
    }

    /** Number of processes still alive inside the job; used to prove that nothing survived. */
    public static uint ActiveProcesses(IntPtr job) {
        int size = Marshal.SizeOf(typeof(JOBOBJECT_BASIC_ACCOUNTING_INFORMATION));
        IntPtr buffer = Marshal.AllocHGlobal(size);
        try {
            if (!QueryInformationJobObject(
                    job,
                    JobObjectBasicAccountingInformation,
                    buffer,
                    (uint)size,
                    IntPtr.Zero)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            JOBOBJECT_BASIC_ACCOUNTING_INFORMATION accounting =
                (JOBOBJECT_BASIC_ACCOUNTING_INFORMATION)Marshal.PtrToStructure(
                    buffer, typeof(JOBOBJECT_BASIC_ACCOUNTING_INFORMATION));
            return accounting.ActiveProcesses;
        } finally {
            Marshal.FreeHGlobal(buffer);
        }
    }

    private static string BuildCommandLine(string[] args) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < args.Length; i++) {
            if (i > 0) result.Append(' ');
            // cmd.exe's own /S /C (or /S /K) contract requires the portion after /C to be one
            // pre-quoted, self-contained unit (see CommandLocator.windowsBatchInvocation, which
            // builds exactly that shape for a Windows batch-file invocation). Re-escaping that
            // unit here would double-quote an argument that is already correctly quoted for
            // cmd's contract, corrupting it -- observed in practice as scip-typescript.cmd (and
            // any other managed .cmd provider) never being recognized as a command. Every other
            // argument, including cmd.exe itself and its flags, still goes through the normal
            // escaping below.
            bool isPreQuotedForCmdShell = i > 0
                    && (string.Equals(args[i - 1], "/c", StringComparison.OrdinalIgnoreCase)
                            || string.Equals(args[i - 1], "/k", StringComparison.OrdinalIgnoreCase));
            result.Append(isPreQuotedForCmdShell ? args[i] : QuoteArgument(args[i]));
        }
        return result.ToString();
    }

    private static string QuoteArgument(string value) {
        if (value == null) throw new ArgumentNullException("value");
        if (value.Length == 0) return "\"\"";
        bool quote = false;
        foreach (char c in value) {
            if (char.IsWhiteSpace(c) || c == '"') {
                quote = true;
                break;
            }
        }
        if (!quote) return value;
        StringBuilder result = new StringBuilder();
        result.Append('"');
        int slashes = 0;
        foreach (char c in value) {
            if (c == '\\') {
                slashes++;
                continue;
            }
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
        if ($separator -lt 1) { throw "Invalid sandbox plan line: $line" }
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
            throw 'Invalid provider environment entry in sandbox plan'
        }
        $result.Add($key + '=' + $value)
    }
    return [string[]]$result.ToArray()
}

function Grant-AppContainerDirectory([string] $Path, [string] $Sid, [string] $Rights) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "Sandbox ACL root is not a directory: $Path"
    }
    $grant = "*${Sid}:$Rights"
    & icacls.exe $Path /grant $grant /q | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to grant AppContainer ACL '$Rights' on $Path" }
}

function Grant-AppContainerFile([string] $Path, [string] $Sid) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Sandbox ACL file is not a file: $Path"
    }
    & icacls.exe $Path /grant "*${Sid}:RX" /q | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to grant AppContainer file ACL on $Path" }
}

function Remove-AppContainerPath([string] $Path, [string] $Sid) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    & icacls.exe $Path /remove:g "*$Sid" /q | Out-Null
}

function Write-Recovery([string] $Journal, [string] $containerProfile, [string] $Sid, [System.Collections.Generic.List[string]] $Granted) {
    $state = [ordered]@{ profile = $containerProfile; sid = $Sid; paths = [string[]]$Granted.ToArray() }
    $temporary = $Journal + '.tmp'
    $state | ConvertTo-Json -Depth 3 -Compress | Set-Content -LiteralPath $temporary -Encoding UTF8
    Move-Item -LiteralPath $temporary -Destination $Journal -Force
}

function Recover-Stale([string] $RecoveryDirectory) {
    if (-not (Test-Path -LiteralPath $RecoveryDirectory -PathType Container)) { return }
    foreach ($journal in Get-ChildItem -LiteralPath $RecoveryDirectory -Filter '*.json' -File -ErrorAction SilentlyContinue) {
        try {
            $state = Get-Content -LiteralPath $journal.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
            $sid = [string]$state.sid
            foreach ($path in @($state.paths)) {
                try { Remove-AppContainerPath ([string]$path) $sid } catch { Write-Warning $_ }
            }
            try { [MinosAppContainerNativeV3]::DeleteProfile([string]$state.profile) } catch { Write-Warning $_ }
            Remove-Item -LiteralPath $journal.FullName -Force -ErrorAction SilentlyContinue
        } catch {
            Write-Warning "Unable to reconcile stale MINOS AppContainer journal $($journal.FullName): $_"
        }
    }
}

$values = Read-Plan $Plan
$command = Read-List $values 'command'
$environment = Read-Environment $values
$readPaths = Read-List $values 'read'
$readFiles = Read-List $values 'readFile'
$writePaths = Read-List $values 'write'
$workingDirectory = Decode ([string]$values['working'])
$recoveryDirectory = Decode ([string]$values['recovery'])
$memoryBytes = [UInt64]$values['jobMemoryBytes']
$activeProcesses = [UInt32]$values['activeProcesses']
$cpuRate = [UInt32]$values['cpuRate']
$jobCpuSeconds = [UInt64]$values['jobCpuSeconds']
$networkPolicy = [string]$values['networkPolicy']
if ($networkPolicy -ne 'ALLOW' -and $networkPolicy -ne 'DENY') {
    throw "Invalid sandbox network policy: $networkPolicy"
}

New-Item -ItemType Directory -Path $recoveryDirectory -Force | Out-Null
Recover-Stale $recoveryDirectory

$containerProfile = 'Minos.Worker.' + ([Guid]::NewGuid().ToString('N'))
$sid = $null
$granted = New-Object System.Collections.Generic.List[string]
$journal = Join-Path $recoveryDirectory ($containerProfile + '.json')

try {
    $sid = [MinosAppContainerNativeV3]::CreateProfile($containerProfile)
    Write-Recovery $journal $containerProfile $sid $granted
    foreach ($path in $readPaths) {
        Grant-AppContainerDirectory $path $sid '(OI)(CI)RX'
        $granted.Add($path)
        Write-Recovery $journal $containerProfile $sid $granted
    }
    foreach ($path in $readFiles) {
        Grant-AppContainerFile $path $sid
        $granted.Add($path)
        Write-Recovery $journal $containerProfile $sid $granted
    }
    foreach ($path in $writePaths) {
        Grant-AppContainerDirectory $path $sid '(OI)(CI)M'
        $granted.Add($path)
        Write-Recovery $journal $containerProfile $sid $granted
    }
    $exitCode = [MinosAppContainerNativeV3]::RunSandbox(
        $containerProfile,
        $command,
        $environment,
        $workingDirectory,
        $memoryBytes,
        $activeProcesses,
        $cpuRate,
        $jobCpuSeconds,
        ($networkPolicy -eq 'ALLOW'))
    exit $exitCode
}
finally {
    if ($sid) {
        foreach ($path in @($granted | Select-Object -Unique)) {
            try { Remove-AppContainerPath $path $sid } catch { Write-Warning $_ }
        }
    }
    try { [MinosAppContainerNativeV3]::DeleteProfile($containerProfile) } catch { Write-Warning $_ }
    Remove-Item -LiteralPath $journal -Force -ErrorAction SilentlyContinue
}
