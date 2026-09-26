// File-based input relay between the panel's Java code and the native key
// latch. JNI cannot serve this role: Runtime.nativeLoad registers the library
// under the game's classloader, while the panel's bridge classes belong to the
// LSPosed module classloader, and Android forbids opening the same .so path
// twice under different classloaders. Plain files in the game's own files
// directory have no such boundary — both sides run in the same process.
//
// Protocol on the input file (one event per line, UTF-8):
//   "<vk> <action>\n"   latch a virtual key (VirtualKeyAction: 0/1/2)
//   "c <payload>\n"     submit a runtime command (single-slot pump)
//   "r\n"               release every latched key
// The status file is rewritten whenever the runtime command status changes.

#include "android_virtual_keys.h"
#include "core/command_pump.h"
#include "core/log.h"

#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <thread>

#include <fcntl.h>
#include <unistd.h>

namespace betterendfield {
namespace {

constexpr auto kPollInterval = std::chrono::milliseconds(10);
constexpr auto kStatusInterval = std::chrono::milliseconds(500);

// Reads every new byte of <path> since the last call into <buffer>. Reopens
// (and restarts from zero) when the file shrank underneath us, which is how a
// fresh session truncates the stream.
bool DrainInput(int& fd, long long& offset, const std::string& path,
        std::string& buffer) {
    if (fd < 0) {
        fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) return false;
        offset = 0;
    }
    // A truncate resets the stream; detect it via the current size.
    off_t size = lseek(fd, 0, SEEK_END);
    if (size < offset) {
        close(fd);
        fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) return false;
        offset = 0;
        size = lseek(fd, 0, SEEK_END);
    }
    if (size == offset) return true;
    if (lseek(fd, offset, SEEK_SET) < 0) return false;
    char chunk[512];
    for (;;) {
        ssize_t got = read(fd, chunk, sizeof(chunk));
        if (got <= 0) break;
        buffer.append(chunk, static_cast<size_t>(got));
        offset += got;
        if (static_cast<long long>(got) < static_cast<long long>(sizeof(chunk))) break;
    }
    return true;
}

void HandleLine(const std::string& line) {
    if (line.empty()) return;
    if (line[0] == 'r') {
        ReleaseAllVirtualKeys();
        return;
    }
    if (line[0] == 'c' && line.size() >= 3 && line[1] == ' ') {
        SubmitRuntimeCommand(line.c_str() + 2, line.size() - 2);
        return;
    }
    char* end = nullptr;
    const long vk = std::strtol(line.c_str(), &end, 10);
    if (end == nullptr || end == line.c_str() || *end != ' ') return;
    const long action = std::strtol(end + 1, &end, 10);
    if (vk <= 0 || vk > 255) return;
    if (action < 0 || action > 2) return;  // VirtualKeyAction range
    SetVirtualKey(static_cast<int>(vk), static_cast<VirtualKeyAction>(action));
}

void WriteStatusFile(const std::string& path, const std::string& status) {
    const int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return;
    ssize_t ignored = write(fd, status.data(), status.size());
    (void)ignored;
    close(fd);
}

// Appends the native log ring's new entries to the journal file the panel
// tails. Truncates first when the file has grown past the cap; already
// delivered entries are never replayed (the ring cursor is independent).
void AppendNativeLog(const std::string& path, std::size_t& cursor) {
    const int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    if (fd < 0) return;
    if (lseek(fd, 0, SEEK_END) > 256 * 1024) ftruncate(fd, 0);
    const std::string fresh = CopyNativeLogSince(cursor);
    if (!fresh.empty()) {
        ssize_t ignored = write(fd, fresh.data(), fresh.size());
        (void)ignored;
    }
    close(fd);
}

void RelayLoop(const std::string& input, const std::string& status,
        const std::string& nativeLog) {
    LogInfo("relay", "input file relay active");
    WriteStatusFile(status, "relay alive; native runtime starting\n");
    // A fresh session must not replay the previous one's log.
    close(open(nativeLog.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644));
    int fd = -1;
    long long offset = 0;
    std::size_t logCursor = 0;
    std::string pending;
    std::string lastStatus;
    auto nextStatus = std::chrono::steady_clock::now();
    for (;;) {
        std::string buffer;
        DrainInput(fd, offset, input, buffer);
        pending += buffer;
        size_t newline;
        while ((newline = pending.find('\n')) != std::string::npos) {
            HandleLine(pending.substr(0, newline));
            pending.erase(0, newline + 1);
        }
        const auto now = std::chrono::steady_clock::now();
        if (now >= nextStatus) {
            nextStatus = now + kStatusInterval;
            const std::string status_now = CopyRuntimeCommandStatus();
            if (status_now != lastStatus) {
                lastStatus = status_now;
                WriteStatusFile(status, "relay alive; " + status_now + "\n");
            }
            AppendNativeLog(nativeLog, logCursor);
        }
        std::this_thread::sleep_for(kPollInterval);
    }
}

}  // namespace

void StartInputRelay() {
    const char* input = std::getenv("BETTER_ENDFIELD_INPUT_FILE");
    const char* status = std::getenv("BETTER_ENDFIELD_STATUS_FILE");
    const char* nativeLog = std::getenv("BETTER_ENDFIELD_NATIVE_LOG");
    if (input == nullptr || input[0] == '\0' || status == nullptr || status[0] == '\0') {
        LogInfo("relay", "input file relay unavailable: paths not configured");
        return;
    }
    std::thread(RelayLoop, std::string(input), std::string(status),
            nativeLog != nullptr ? std::string(nativeLog) : std::string()).detach();
}

}  // namespace betterendfield
