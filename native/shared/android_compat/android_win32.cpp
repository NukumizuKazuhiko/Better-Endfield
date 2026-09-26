#include "android_win32.h"

#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>

namespace betterendfield {
namespace {

constexpr int kVirtualKeyCount = 256;
constexpr std::uint64_t kHeldForever = ~std::uint64_t{0};

// Zero means released, kHeldForever means held until an explicit release, and
// anything else is the monotonic millisecond at which a pulse expires.
std::atomic<std::uint64_t> g_key_deadline[kVirtualKeyCount];

std::uint64_t NowMilliseconds() {
    return static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
            .count());
}

}  // namespace

bool SetVirtualKey(int virtual_key, VirtualKeyAction action) {
    if (virtual_key <= 0 || virtual_key >= kVirtualKeyCount) return false;
    std::uint64_t deadline = 0;
    switch (action) {
        case VirtualKeyAction::Release: deadline = 0; break;
        case VirtualKeyAction::Press: deadline = kHeldForever; break;
        case VirtualKeyAction::Pulse:
            deadline = NowMilliseconds() + kVirtualKeyPulseMs;
            break;
        default: return false;
    }
    g_key_deadline[virtual_key].store(deadline, std::memory_order_release);
    return true;
}

void ReleaseAllVirtualKeys() {
    for (auto& key : g_key_deadline) key.store(0, std::memory_order_release);
}

bool VirtualKeyDown(int virtual_key) {
    if (virtual_key <= 0 || virtual_key >= kVirtualKeyCount) return false;
    const std::uint64_t deadline =
        g_key_deadline[virtual_key].load(std::memory_order_acquire);
    if (deadline == 0) return false;
    if (deadline == kHeldForever) return true;
    if (NowMilliseconds() < deadline) return true;
    // Clear the expired pulse so the next tap is a fresh rising edge. The
    // compare_exchange leaves a deadline a racing writer has just re-armed.
    std::uint64_t expected = deadline;
    g_key_deadline[virtual_key].compare_exchange_strong(
        expected, 0, std::memory_order_acq_rel, std::memory_order_acquire);
    return false;
}

namespace win32 {

std::uint64_t MonotonicMilliseconds() { return NowMilliseconds(); }

std::int64_t MonotonicNanoseconds() {
    return static_cast<std::int64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
            .count());
}

void SleepMilliseconds(std::uint32_t milliseconds) {
    std::this_thread::sleep_for(std::chrono::milliseconds(milliseconds));
}

std::uint32_t ThreadId() { return static_cast<std::uint32_t>(gettid()); }

std::uint32_t ProcessId() { return static_cast<std::uint32_t>(getpid()); }

void* Il2CppImage() {
    // RTLD_NOLOAD: the client maps libil2cpp.so long before any module starts,
    // and loading a second copy would resolve exports against the wrong image.
    static void* image = dlopen("libil2cpp.so", RTLD_NOLOAD | RTLD_NOW);
    return image;
}

void* Symbol(void* image, const char* name) {
    if (name == nullptr) return nullptr;
    void* target = image != nullptr ? image : Il2CppImage();
    return target != nullptr ? dlsym(target, name) : nullptr;
}

}  // namespace win32
}  // namespace betterendfield

namespace {

int HandleToDescriptor(HANDLE handle) {
    if (handle == nullptr || handle == INVALID_HANDLE_VALUE) return -1;
    return static_cast<int>(reinterpret_cast<std::intptr_t>(handle) - 1);
}

HANDLE DescriptorToHandle(int descriptor) {
    return reinterpret_cast<HANDLE>(static_cast<std::intptr_t>(descriptor) + 1);
}

std::string WideToUtf8(const wchar_t* wide) {
    std::string result;
    if (wide == nullptr) return result;
    for (const wchar_t* it = wide; *it != L'\0'; ++it) {
        const auto codepoint = static_cast<std::uint32_t>(*it);
        if (codepoint > 0x10ffffu) return std::string();
        if (codepoint <= 0x7fu) {
            result.push_back(static_cast<char>(codepoint));
        } else if (codepoint <= 0x7ffu) {
            result.push_back(static_cast<char>(0xc0u | (codepoint >> 6)));
            result.push_back(static_cast<char>(0x80u | (codepoint & 0x3fu)));
        } else if (codepoint <= 0xffffu) {
            result.push_back(static_cast<char>(0xe0u | (codepoint >> 12)));
            result.push_back(static_cast<char>(0x80u | ((codepoint >> 6) & 0x3fu)));
            result.push_back(static_cast<char>(0x80u | (codepoint & 0x3fu)));
        } else {
            result.push_back(static_cast<char>(0xf0u | (codepoint >> 18)));
            result.push_back(static_cast<char>(0x80u | ((codepoint >> 12) & 0x3fu)));
            result.push_back(static_cast<char>(0x80u | ((codepoint >> 6) & 0x3fu)));
            result.push_back(static_cast<char>(0x80u | (codepoint & 0x3fu)));
        }
    }
    return result;
}

// Decodes UTF-8 into output and returns the code point count, terminator included
// when input_length is negative. A null output asks for the count alone. Returns
// -1 for invalid UTF-8 and for a buffer too small to hold the result, which the
// caller reports the way Windows reports both: by returning 0.
int DecodeUtf8(const char* input, int input_length, wchar_t* output, int output_capacity) {
    const bool terminated = input_length < 0;
    const std::size_t limit = terminated ? std::strlen(input)
                                         : static_cast<std::size_t>(input_length);
    static const std::uint32_t kMinimumByExtraBytes[] = {0u, 0x80u, 0x800u, 0x10000u};
    int produced = 0;
    for (std::size_t i = 0; i < limit; ) {
        const auto lead = static_cast<unsigned char>(input[i]);
        int extra = 0;
        std::uint32_t codepoint = 0;
        if (lead <= 0x7fu) {
            codepoint = lead;
        } else if ((lead & 0xe0u) == 0xc0u) {
            codepoint = lead & 0x1fu;
            extra = 1;
        } else if ((lead & 0xf0u) == 0xe0u) {
            codepoint = lead & 0x0fu;
            extra = 2;
        } else if ((lead & 0xf8u) == 0xf0u) {
            codepoint = lead & 0x07u;
            extra = 3;
        } else {
            return -1;
        }
        if (i + static_cast<std::size_t>(extra) >= limit) return -1;
        for (int continuation = 1; continuation <= extra; ++continuation) {
            const auto byte = static_cast<unsigned char>(input[i + continuation]);
            if ((byte & 0xc0u) != 0x80u) return -1;
            codepoint = (codepoint << 6) | (byte & 0x3fu);
        }
        i += static_cast<std::size_t>(extra) + 1;
        // Over-long forms, surrogates and past-U+10FFFF values are not code
        // points, and encoding one here would invent a path nobody wrote.
        if (codepoint < kMinimumByExtraBytes[extra] || codepoint > 0x10ffffu ||
            (codepoint >= 0xd800u && codepoint <= 0xdfffu)) {
            return -1;
        }
        if (output != nullptr) {
            if (produced >= output_capacity) return -1;
            output[produced] = static_cast<wchar_t>(codepoint);
        }
        ++produced;
    }
    if (terminated) {
        if (output != nullptr) {
            if (produced >= output_capacity) return -1;
            output[produced] = L'\0';
        }
        ++produced;
    }
    return produced;
}

}  // namespace

int MultiByteToWideChar(unsigned code_page, unsigned flags, const char* input,
    int input_length, wchar_t* output, int output_capacity) {
    (void)flags;
    if (code_page != CP_UTF8 || input == nullptr || input_length == 0 ||
        output_capacity < 0) {
        return 0;
    }
    wchar_t* destination = output_capacity > 0 ? output : nullptr;
    const int produced = DecodeUtf8(input, input_length, destination, output_capacity);
    return produced < 0 ? 0 : produced;
}

HANDLE CreateFileW(LPCWSTR file_name, DWORD desired_access, DWORD share_mode,
    void* security_attributes, DWORD creation_disposition,
    DWORD flags_and_attributes, void* template_file) {
    (void)share_mode;  // POSIX shares unconditionally, so there is nothing to grant.
    (void)security_attributes;
    (void)flags_and_attributes;
    (void)template_file;
    if (desired_access != GENERIC_READ || creation_disposition != OPEN_EXISTING) {
        return INVALID_HANDLE_VALUE;
    }
    const std::string path = WideToUtf8(file_name);
    if (path.empty()) return INVALID_HANDLE_VALUE;
    const int descriptor = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (descriptor < 0) return INVALID_HANDLE_VALUE;
    return DescriptorToHandle(descriptor);
}

BOOL ReadFile(HANDLE file, void* buffer, DWORD bytes_to_read, DWORD* bytes_read,
    void* overlapped) {
    (void)overlapped;
    const int descriptor = HandleToDescriptor(file);
    if (descriptor < 0 || buffer == nullptr) return 0;
    std::size_t total = 0;
    while (total < bytes_to_read) {
        const ssize_t chunk = ::read(descriptor, static_cast<char*>(buffer) + total,
            static_cast<std::size_t>(bytes_to_read) - total);
        if (chunk < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (chunk == 0) break;
        total += static_cast<std::size_t>(chunk);
    }
    // A short count is a successful read that reached the end of the file, which
    // is what Windows reports; the caller compares it against the size it asked
    // for and rejects the mismatch itself.
    if (bytes_read != nullptr) *bytes_read = static_cast<DWORD>(total);
    return 1;
}

BOOL GetFileSizeEx(HANDLE file, LARGE_INTEGER* size) {
    const int descriptor = HandleToDescriptor(file);
    if (descriptor < 0 || size == nullptr) return 0;
    struct stat status {};
    if (::fstat(descriptor, &status) != 0) return 0;
    size->QuadPart = static_cast<std::int64_t>(status.st_size);
    return 1;
}

BOOL CloseHandle(HANDLE object) {
    const int descriptor = HandleToDescriptor(object);
    if (descriptor < 0) return 0;
    return ::close(descriptor) == 0 ? 1 : 0;
}
