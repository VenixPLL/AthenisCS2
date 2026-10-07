package me.venixpll.cheat;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CS2Memory handles external interaction with the Counter-Strike 2 process.
 * Provides functions to attach to the game, find client.dll, and read various
 * memory types.
 *
 * <p>Additionally exposes a centralized {@link MemoryStatus} reporting channel:
 * UI components (launcher, overlay) can register a {@link StatusListener} to be
 * notified about attachment changes and read failures instead of each layer
 * having to guess from silent {@code 0} return values.</p>
 */
public class CS2Memory {

    /** High-level health of the CS2 process connection. */
    public enum MemoryStatus {
        /** Not attached to any process. */
        DETACHED,
        /** Attached and reading normally. */
        ATTACHED,
        /** Attached, but kernel reads are failing (stale handle / permissions). */
        READ_FAILURE,
        /** The CS2 process has exited. */
        PROCESS_EXITED
    }

    /** Callback invoked when the memory connection status changes. */
    public interface StatusListener {
        /**
         * @param status New status.
         * @param detail Human-readable detail message (may be empty).
         */
        void onStatusChanged(MemoryStatus status, String detail);
    }

    private static HANDLE processHandle = null;
    private static long clientBase = 0;
    private static long engine2Base = 0;
    private static int processId = 0;
    private static boolean loggedError = false;

    private static final List<StatusListener> statusListeners = new CopyOnWriteArrayList<>();
    private static volatile MemoryStatus currentStatus = MemoryStatus.DETACHED;

    // ── Verbose / diagnostic mode ────────────────────────────────────────────
    /**
     * When {@code true} every failed {@code ReadProcessMemory} call is printed
     * immediately (unthrottled) to help diagnose which pointer hop in the entity
     * pipeline is broken.  Toggle via {@link #setVerboseReadFailures(boolean)}.
     * <p>Off by default – enable from the launcher debug panel or programmatically.</p>
     */
    private static volatile boolean verboseReadFailures = false;

    /**
     * Enables or disables verbose (unthrottled) read-failure logging.
     *
     * @param verbose {@code true} to print every failed RPM address; {@code false}
     *                to revert to the throttled 5-second window.
     */
    public static void setVerboseReadFailures(boolean verbose) {
        verboseReadFailures = verbose;
        System.out.println("[CS2Memory] Verbose read-failure logging " + (verbose ? "ENABLED" : "DISABLED"));
    }

    /** Returns whether verbose read-failure logging is currently active. */
    public static boolean isVerboseReadFailures() {
        return verboseReadFailures;
    }

    /** Throttle for read-failure notifications (one report per window). */
    private static final AtomicLong lastReadFailureReportMs = new AtomicLong(0L);
    private static final long READ_FAILURE_REPORT_INTERVAL_MS = 5_000L;

    /**
     * Registers a listener that will be invoked on the calling thread of
     * whatever operation changes the status (never on a UI thread — callers
     * must marshal to their own thread if needed).
     *
     * @param listener Listener to register (no-op if null).
     */
    public static void addStatusListener(StatusListener listener) {
        if (listener != null) {
            statusListeners.add(listener);
            // Immediately inform the new listener of the current state.
            listener.onStatusChanged(currentStatus, "");
        }
    }

    /** Removes a previously registered status listener. */
    public static void removeStatusListener(StatusListener listener) {
        statusListeners.remove(listener);
    }

    /** @return The current connection status. */
    public static MemoryStatus getStatus() {
        return currentStatus;
    }

    /** Fires the status-change event to all listeners if the status changed. */
    private static void setStatus(MemoryStatus status, String detail) {
        MemoryStatus old = currentStatus;
        currentStatus = status;
        if (old != status) {
            for (StatusListener l : statusListeners) {
                try {
                    l.onStatusChanged(status, detail);
                } catch (Exception e) {
                    System.err.println("[CS2Memory] Status listener error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Reports a failed kernel read.
     * <p>
     * In <em>verbose</em> mode ({@link #verboseReadFailures} == {@code true}) every
     * failure is printed immediately with caller context so the exact broken pointer
     * hop can be identified.  In normal mode the notification is throttled to one
     * report per {@link #READ_FAILURE_REPORT_INTERVAL_MS} to avoid log/event spam.
     *
     * @param address Address that failed to read.
     */
    private static void reportReadFailure(long address) {
        int err = Kernel32.INSTANCE.GetLastError();
        String msg = "ReadProcessMemory failed at 0x" + Long.toHexString(address)
                + " (Win32 error " + err + "). If CS2 was restarted, click STOP then START to reattach.";

        if (verboseReadFailures) {
            // Unthrottled: log every failure with a stack trace snippet so callers
            // can see exactly which reader/method triggered the failure.
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            StringBuilder callers = new StringBuilder();
            // Skip [0]=getStackTrace, [1]=reportReadFailure, [2]=readXxx
            for (int i = 2; i < Math.min(stack.length, 7); i++) {
                callers.append("\n    at ").append(stack[i]);
            }
            System.err.println("[CS2Memory] " + msg + callers);
        } else {
            long now = System.currentTimeMillis();
            long last = lastReadFailureReportMs.get();
            if (now - last < READ_FAILURE_REPORT_INTERVAL_MS)
                return;
            if (!lastReadFailureReportMs.compareAndSet(last, now))
                return;
            setStatus(MemoryStatus.READ_FAILURE, msg);
        }
    }

    // ThreadLocal reused JNA Memory buffers to prevent garbage collection heap/native churn
    private static final ThreadLocal<Memory> MEM_1 = ThreadLocal.withInitial(() -> new Memory(1));
    private static final ThreadLocal<Memory> MEM_2 = ThreadLocal.withInitial(() -> new Memory(2));
    private static final ThreadLocal<Memory> MEM_4 = ThreadLocal.withInitial(() -> new Memory(4));
    private static final ThreadLocal<Memory> MEM_8 = ThreadLocal.withInitial(() -> new Memory(8));
    private static final ThreadLocal<Memory> MEM_12 = ThreadLocal.withInitial(() -> new Memory(12));
    private static final ThreadLocal<Memory> MEM_64 = ThreadLocal.withInitial(() -> new Memory(64));
    private static final ThreadLocal<Memory> STRING_BUF = new ThreadLocal<>();
    private static final ThreadLocal<IntByReference> EXIT_CODE_BUF = ThreadLocal.withInitial(IntByReference::new);

    private static Memory getStringBuffer(int size) {
        Memory mem = STRING_BUF.get();
        if (mem == null || mem.size() < size) {
            mem = new Memory(size);
            STRING_BUF.set(mem);
        }
        return mem;
    }

    /**
     * Attempts to find the process ID of cs2.exe and attach to it.
     * 
     * @return True if successfully attached, false otherwise.
     */
    public static boolean attach() {
        if (processHandle != null) {
            return true;
        }

        processId = getProcessId("cs2.exe");
        if (processId == 0) {
            if (!loggedError) {
                System.out.println("[CS2Memory] cs2.exe not found. Waiting for game to start...");
                loggedError = true;
            }
            return false;
        }

        loggedError = false;
        System.out.println("[CS2Memory] Found cs2.exe with PID: " + processId);

        // Open the process with Read, Write, Query and Create Thread access permissions
        processHandle = Kernel32.INSTANCE.OpenProcess(
                WinNT.PROCESS_VM_READ | WinNT.PROCESS_VM_WRITE | WinNT.PROCESS_VM_OPERATION
                        | WinNT.PROCESS_QUERY_INFORMATION | 0x0002 /* PROCESS_CREATE_THREAD */,
                false,
                processId);

        if (processHandle == null || processHandle == WinBase.INVALID_HANDLE_VALUE) {
            System.err.println("[CS2Memory] Failed to open process handle. Make sure to run as Administrator!");
            processHandle = null;
            return false;
        }

        System.out.println("[CS2Memory] Successfully opened process handle.");

        // Find client.dll base address
        clientBase = getModuleBaseAddress(processId, "client.dll");
        if (clientBase == 0) {
            System.err.println("[CS2Memory] Failed to find client.dll base address. Retrying...");
            close();
            return false;
        }

        System.out.println(String.format("[CS2Memory] Found client.dll base address: 0x%X", clientBase));
        setStatus(MemoryStatus.ATTACHED, "Attached to cs2.exe (PID " + processId + ")");
        return true;
    }

    /**
     * Cleans up process handles and resets memory variables.
     */
    public static void close() {
        if (processHandle != null) {
            Kernel32.INSTANCE.CloseHandle(processHandle);
            processHandle = null;
        }
        clientBase = 0;
        engine2Base = 0;
        processId = 0;
        setStatus(MemoryStatus.DETACHED, "Handle closed");
    }

    /**
     * Returns the cached base address of client.dll.
     * 
     * @return Base address value or 0 if not attached.
     */
    public static long getClientBase() {
        return clientBase;
    }

    /**
     * Returns the cached base address of engine2.dll, resolving it on first call.
     *
     * @return Base address or 0 if not attached / not found.
     */
    public static long getEngine2Base() {
        if (engine2Base == 0 && processId != 0) {
            engine2Base = getModuleBaseAddress(processId, "engine2.dll");
        }
        return engine2Base;
    }

    /**
     * Returns whether we are currently attached to the CS2 process.
     * 
     * @return True if attached, false otherwise.
     */
    /**
     * Returns the target process ID.
     *
     * @return CS2 process ID or 0 if not attached.
     */
    public static int getProcessId() {
        return processId;
    }

    public static boolean isAttached() {
        return processHandle != null;
    }

    /**
     * Checks if the CS2 process is still running.
     * 
     * @return True if running, false if exited or not attached.
     */
    public static boolean isProcessRunning() {
        if (processHandle == null) {
            return false;
        }
        IntByReference exitCode = EXIT_CODE_BUF.get();
        if (Kernel32.INSTANCE.GetExitCodeProcess(processHandle, exitCode)) {
            boolean running = exitCode.getValue() == WinBase.STILL_ACTIVE;
            if (!running) {
                setStatus(MemoryStatus.PROCESS_EXITED, "cs2.exe has exited");
            }
            return running;
        }
        return false;
    }

    /**
     * Reads a 4-byte integer from the specified memory address.
     * 
     * @param address Native memory address to read from.
     * @return Read integer value, or 0 if reading fails.
     */
    public static int readInt(long address) {
        if (processHandle == null || address == 0)
            return 0;
        Memory mem = MEM_4.get();
        if (Kernel32.INSTANCE.ReadProcessMemory(processHandle, new Pointer(address), mem, 4, null)) {
            return mem.getInt(0);
        }
        reportReadFailure(address);
        return 0;
    }

    /**
     * Reads an 8-byte long pointer from the specified memory address.
     * 
     * @param address Native memory address to read from.
     * @return Read long value, or 0 if reading fails.
     */
    public static long readLong(long address) {
        if (processHandle == null || address == 0)
            return 0;
        Memory mem = MEM_8.get();
        if (Kernel32.INSTANCE.ReadProcessMemory(processHandle, new Pointer(address), mem, 8, null)) {
            return mem.getLong(0);
        }
        reportReadFailure(address);
        return 0;
    }

    /**
     * Reads a single byte from the specified memory address.
     * 
     * @param address Native memory address to read from.
     * @return Read byte value, or 0 if reading fails.
     */
    public static byte readByte(long address) {
        if (processHandle == null || address == 0)
            return 0;
        Memory mem = MEM_1.get();
        if (Kernel32.INSTANCE.ReadProcessMemory(processHandle, new Pointer(address), mem, 1, null)) {
            return mem.getByte(0);
        }
        return 0;
    }

    /**
     * Reads a 4-byte float from the specified memory address.
     * 
     * @param address Native memory address to read from.
     * @return Read float value, or 0.0f if reading fails.
     */
    public static float readFloat(long address) {
        if (processHandle == null || address == 0)
            return 0f;
        Memory mem = MEM_4.get();
        if (Kernel32.INSTANCE.ReadProcessMemory(processHandle, new Pointer(address), mem, 4, null)) {
            return mem.getFloat(0);
        }
        reportReadFailure(address);
        return 0f;
    }

    /**
     * Reads a Vector3 (3 consecutive floats) from the specified memory address.
     * 
     * @param address Native memory address to read from.
     * @return Read Vector3 object, or a zero vector if reading fails.
     */
    public static Vector3 readVector(long address) {
        if (processHandle == null || address == 0)
            return new Vector3(0, 0, 0);
        Memory mem = MEM_12.get();
        if (Kernel32.INSTANCE.ReadProcessMemory(processHandle, new Pointer(address), mem, 12, null)) {
            return new Vector3(mem.getFloat(0), mem.getFloat(4), mem.getFloat(8));
        }
        reportReadFailure(address);
        return new Vector3(0, 0, 0);
    }

    /**
     * Reads a 4x4 matrix (16 consecutive floats) from the specified memory address.
     * 
     * @param address Native memory address to read from.
     * @return Float array representing the 4x4 matrix, or an empty matrix if
     *         reading fails.
     */
    public static float[] readMatrix(long address) {
        float[] matrix = new float[16];
        if (processHandle == null || address == 0)
            return matrix;
        Memory mem = MEM_64.get();
        if (Kernel32.INSTANCE.ReadProcessMemory(processHandle, new Pointer(address), mem, 64, null)) {
            mem.read(0, matrix, 0, 16);
        }
        return matrix;
    }

    /**
     * Writes a 4-byte float value to the specified memory address in the CS2
     * process.
     * Used exclusively for educational silent-aim angle patching and immediate
     * restoration.
     *
     * @param address Native memory address to write to.
     * @param value   Float value to write.
     * @return True if the write succeeded, false otherwise.
     */
    public static boolean writeFloat(long address, float value) {
        if (processHandle == null || address == 0)
            return false;
        Memory mem = MEM_4.get();
        mem.setFloat(0, value);
        return Kernel32.INSTANCE.WriteProcessMemory(processHandle, new Pointer(address), mem, 4, null);
    }

    /**
     * Writes a 4-byte integer value to the specified memory address in the CS2
     * process.
     * Used by modules that patch boolean or integer fields, such as
     * {@code m_bSpotted} for the radar hack.
     *
     * @param address Native memory address to write to.
     * @param value   Integer value to write.
     * @return {@code true} if the write succeeded, {@code false} otherwise.
     */
    public static boolean writeInt(long address, int value) {
        if (processHandle == null || address == 0)
            return false;
        Memory mem = MEM_4.get();
        mem.setInt(0, value);
        return Kernel32.INSTANCE.WriteProcessMemory(processHandle, new Pointer(address), mem, 4, null);
    }

    /**
     * Writes a single byte to the specified memory address in the CS2 process.
     * <p>
     * Use this instead of {@link #writeInt} when patching 1-byte boolean fields
     * such as {@code m_bSpotted} — writing 4 bytes to a 1-byte field corrupts
     * the three bytes that follow it in the struct, which can crash the game.
     *
     * @param address Native memory address to write to.
     * @param value   Byte value to write (e.g. {@code (byte) 1} for {@code true}).
     * @return {@code true} if the write succeeded, {@code false} otherwise.
     */
    public static boolean writeByte(long address, byte value) {
        if (processHandle == null || address == 0)
            return false;
        Memory mem = MEM_1.get();
        mem.setByte(0, value);
        return Kernel32.INSTANCE.WriteProcessMemory(processHandle, new Pointer(address), mem, 1, null);
    }

    /**
     * Reads an array of bytes from the specified memory address.
     *
     * @param address Native memory address to read from.
     * @param size    Number of bytes to read.
     * @return Byte array containing read memory, or an empty array on failure.
     */
    public static byte[] readBytes(long address, int size) {
        if (processHandle == null || address == 0 || size <= 0)
            return new byte[0];
        byte[] buffer = new byte[size];
        Memory mem = new Memory(size);
        if (Kernel32.INSTANCE.ReadProcessMemory(processHandle, new Pointer(address), mem, size, null)) {
            mem.read(0, buffer, 0, size);
            return buffer;
        }
        return new byte[0];
    }



    public static boolean writeAngles(long address, float pitch, float yaw) {
        if (processHandle == null || address == 0)
            return false;
        Memory mem = MEM_8.get();
        mem.setFloat(0, pitch);
        mem.setFloat(4, yaw);
        return Kernel32.INSTANCE.WriteProcessMemory(processHandle, new Pointer(address), mem, 8, null);
    }

    /**
     * Fills a caller-supplied pre-allocated JNA {@link Memory} buffer by reading
     * {@code size} bytes from {@code address} in the CS2 process.
     * <p>
     * Intended for reader classes ({@code ViewMatrixReader},
     * {@code EntityDataReader},
     * {@code PositionReader}) that maintain their own pooled buffers to avoid the
     * per-call native heap allocation that the individual {@link #readInt} /
     * {@link #readLong} / {@link #readVector} helpers would otherwise incur.
     * <p>
     * The caller is responsible for ensuring {@code buffer} capacity ≥
     * {@code size}.
     *
     * @param address Address in the CS2 process to read from.
     * @param buffer  Pre-allocated {@link Memory} buffer owned by the caller.
     * @param size    Number of bytes to read into {@code buffer}.
     * @return {@code true} if the read succeeded; {@code false} if the handle is
     *         null,
     *         the address is zero, or the kernel call fails.
     */
    public static boolean readInto(long address, Memory buffer, int size) {
        if (processHandle == null || address == 0)
            return false;
        boolean ok = Kernel32.INSTANCE.ReadProcessMemory(processHandle, new Pointer(address), buffer, size, null);
        if (!ok) {
            reportReadFailure(address);
        }
        return ok;
    }

    /**
     * Reads a null-terminated UTF-8 string up to the specified maximum length.
     * 
     * @param address   Native memory address to read from.
     * @param maxLength Maximum number of bytes to read.
     * @return Read string value, or an empty string if reading fails.
     */
    public static String readString(long address, int maxLength) {
        if (processHandle == null || address == 0)
            return "";
        Memory mem = getStringBuffer(maxLength);
        if (Kernel32.INSTANCE.ReadProcessMemory(processHandle, new Pointer(address), mem, maxLength, null)) {
            byte[] bytes = mem.getByteArray(0, maxLength);
            int len = 0;
            while (len < bytes.length && bytes[len] != 0) {
                len++;
            }
            return new String(bytes, 0, len, StandardCharsets.UTF_8);
        }
        return "";
    }

    /**
     * Helper method to find a process ID by process executable name.
     * 
     * @param processName Target process name (e.g. "cs2.exe")
     * @return Found process ID or 0 if not found.
     */
    private static int getProcessId(String processName) {
        HANDLE snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, new WinDef.DWORD(0));
        if (snapshot == WinBase.INVALID_HANDLE_VALUE) {
            return 0;
        }
        try {
            Tlhelp32.PROCESSENTRY32.ByReference entry = new Tlhelp32.PROCESSENTRY32.ByReference();
            if (Kernel32.INSTANCE.Process32First(snapshot, entry)) {
                do {
                    String name = Native.toString(entry.szExeFile);
                    if (name.equalsIgnoreCase(processName)) {
                        return entry.th32ProcessID.intValue();
                    }
                } while (Kernel32.INSTANCE.Process32Next(snapshot, entry));
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(snapshot);
        }
        return 0;
    }

    /**
     * Helper method to find the base address of a specific module within a process.
     * 
     * @param pid        Process ID
     * @param moduleName Name of the dll/module (e.g. "client.dll")
     * @return Module base address or 0 if not found.
     */
    private static long getModuleBaseAddress(int pid, String moduleName) {
        HANDLE snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(
                new WinDef.DWORD(Tlhelp32.TH32CS_SNAPMODULE.intValue() | Tlhelp32.TH32CS_SNAPMODULE32.intValue()),
                new WinDef.DWORD(pid));
        if (snapshot == WinBase.INVALID_HANDLE_VALUE) {
            return 0;
        }
        try {
            Tlhelp32.MODULEENTRY32W.ByReference entry = new Tlhelp32.MODULEENTRY32W.ByReference();
            if (Kernel32.INSTANCE.Module32FirstW(snapshot, entry)) {
                do {
                    String name = Native.toString(entry.szModule);
                    if (name.equalsIgnoreCase(moduleName)) {
                        return Pointer.nativeValue(entry.modBaseAddr);
                    }
                } while (Kernel32.INSTANCE.Module32NextW(snapshot, entry));
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(snapshot);
        }
        return 0;
    }

    public static long getModuleSize(String moduleName) {
        if (processId == 0) return 0;
        HANDLE snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(
                new WinDef.DWORD(Tlhelp32.TH32CS_SNAPMODULE.intValue() | Tlhelp32.TH32CS_SNAPMODULE32.intValue()),
                new WinDef.DWORD(processId));
        if (snapshot == WinBase.INVALID_HANDLE_VALUE) {
            return 0;
        }
        try {
            Tlhelp32.MODULEENTRY32W.ByReference entry = new Tlhelp32.MODULEENTRY32W.ByReference();
            if (Kernel32.INSTANCE.Module32FirstW(snapshot, entry)) {
                do {
                    String name = Native.toString(entry.szModule);
                    if (name.equalsIgnoreCase(moduleName)) {
                        return entry.modBaseSize.longValue();
                    }
                } while (Kernel32.INSTANCE.Module32NextW(snapshot, entry));
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(snapshot);
        }
        return 0;
    }

    public static long sigScan(String moduleName, String pattern) {
        long base = getModuleBaseAddress(processId, moduleName);
        long size = getModuleSize(moduleName);
        if (base == 0 || size == 0) return 0;

        byte[] moduleBytes = new byte[(int) size];
        Memory memBuffer = new Memory(size);
        if (!readInto(base, memBuffer, (int) size)) {
            return 0;
        }
        memBuffer.read(0, moduleBytes, 0, (int) size);

        // Parse pattern
        String[] parts = pattern.split(" ");
        byte[] patternBytes = new byte[parts.length];
        boolean[] wildcards = new boolean[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("?")) {
                wildcards[i] = true;
            } else {
                patternBytes[i] = (byte) Integer.parseInt(parts[i], 16);
            }
        }

        // Scan
        for (int i = 0; i <= moduleBytes.length - patternBytes.length; i++) {
            boolean found = true;
            for (int j = 0; j < patternBytes.length; j++) {
                if (wildcards[j]) continue;
                if (moduleBytes[i + j] != patternBytes[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return base + i;
            }
        }
        return 0;
    }

    public static short readShort(long address) {
        if (processHandle == null || address == 0)
            return 0;
        Memory mem = MEM_2.get();
        if (Kernel32.INSTANCE.ReadProcessMemory(processHandle, new Pointer(address), mem, 2, null)) {
            return mem.getShort(0);
        }
        return 0;
    }

    public static boolean writeShort(long address, short value) {
        if (processHandle == null || address == 0)
            return false;
        Memory mem = MEM_2.get();
        mem.setShort(0, value);
        return Kernel32.INSTANCE.WriteProcessMemory(processHandle, new Pointer(address), mem, 2, null);
    }

    public static boolean writeLong(long address, long value) {
        if (processHandle == null || address == 0)
            return false;
        Memory mem = MEM_8.get();
        mem.setLong(0, value);
        return Kernel32.INSTANCE.WriteProcessMemory(processHandle, new Pointer(address), mem, 8, null);
    }

    public static boolean writeBytes(long address, byte[] data) {
        if (processHandle == null || address == 0)
            return false;
        Memory mem = new Memory(data.length);
        mem.write(0, data, 0, data.length);
        return Kernel32.INSTANCE.WriteProcessMemory(processHandle, new Pointer(address), mem, data.length, null);
    }

    public static long virtualAllocEx(long size) {
        if (processHandle == null) return 0;
        Pointer addr = ExtraKernel32.INSTANCE.VirtualAllocEx(
            processHandle,
            null,
            size,
            0x1000 | 0x2000, // MEM_COMMIT | MEM_RESERVE
            0x40 // PAGE_EXECUTE_READWRITE
        );
        return addr == null ? 0 : Pointer.nativeValue(addr);
    }

    public static boolean virtualFreeEx(long address) {
        if (processHandle == null || address == 0) return false;
        return ExtraKernel32.INSTANCE.VirtualFreeEx(
            processHandle,
            new Pointer(address),
            0L,
            0x8000 // MEM_RELEASE
        );
    }

    public static void callThread(long funcAddress) {
        if (processHandle == null || funcAddress == 0) return;

        HANDLE hThread = ExtraKernel32.INSTANCE.CreateRemoteThread(
            processHandle,
            null,
            0L,
            new Pointer(funcAddress),
            null,
            0,
            null
        );

        if (hThread != null && !hThread.equals(WinBase.INVALID_HANDLE_VALUE)) {
            Kernel32.INSTANCE.WaitForSingleObject(hThread, WinBase.INFINITE);
            Kernel32.INSTANCE.CloseHandle(hThread);
        } else {
            System.err.println("[CS2Memory] CreateRemoteThread failed! Last error: " + Kernel32.INSTANCE.GetLastError());
        }
    }

    public interface ExtraKernel32 extends com.sun.jna.win32.StdCallLibrary {
        ExtraKernel32 INSTANCE = Native.load("kernel32", ExtraKernel32.class, com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);

        Pointer VirtualAllocEx(HANDLE hProcess, Pointer lpAddress, long dwSize, int flAllocationType, int flProtect);
        boolean VirtualFreeEx(HANDLE hProcess, Pointer lpAddress, long dwSize, int dwFreeType);
        HANDLE CreateRemoteThread(HANDLE hProcess, Pointer lpThreadAttributes, long dwStackSize, Pointer lpStartAddress, Pointer lpParameter, int dwCreationFlags, Pointer lpThreadId);
    }
}
