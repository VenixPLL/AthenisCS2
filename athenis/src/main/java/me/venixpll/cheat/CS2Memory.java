package me.venixpll.cheat;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;

import java.nio.charset.StandardCharsets;
import com.sun.jna.ptr.IntByReference;

/**
 * CS2Memory handles external interaction with the Counter-Strike 2 process.
 * Provides functions to attach to the game, find client.dll, and read various
 * memory types.
 */
public class CS2Memory {
    private static HANDLE processHandle = null;
    private static long clientBase = 0;
    private static int processId = 0;
    private static boolean loggedError = false;

    // ThreadLocal reused JNA Memory buffers to prevent garbage collection heap/native churn
    private static final ThreadLocal<Memory> MEM_1 = ThreadLocal.withInitial(() -> new Memory(1));
    private static final ThreadLocal<Memory> MEM_4 = ThreadLocal.withInitial(() -> new Memory(4));
    private static final ThreadLocal<Memory> MEM_8 = ThreadLocal.withInitial(() -> new Memory(8));
    private static final ThreadLocal<Memory> MEM_12 = ThreadLocal.withInitial(() -> new Memory(12));
    private static final ThreadLocal<Memory> MEM_64 = ThreadLocal.withInitial(() -> new Memory(64));
    private static final ThreadLocal<Memory> STRING_BUF = new ThreadLocal<>();

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

        // Open the process with Read, Write and Query access permissions
        processHandle = Kernel32.INSTANCE.OpenProcess(
                WinNT.PROCESS_VM_READ | WinNT.PROCESS_VM_WRITE | WinNT.PROCESS_VM_OPERATION
                        | WinNT.PROCESS_QUERY_INFORMATION,
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
        processId = 0;
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
     * Returns whether we are currently attached to the CS2 process.
     * 
     * @return True if attached, false otherwise.
     */
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
        IntByReference exitCode = new IntByReference();
        if (Kernel32.INSTANCE.GetExitCodeProcess(processHandle, exitCode)) {
            return exitCode.getValue() == WinBase.STILL_ACTIVE;
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
        return Kernel32.INSTANCE.ReadProcessMemory(processHandle, new Pointer(address), buffer, size, null);
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
}
