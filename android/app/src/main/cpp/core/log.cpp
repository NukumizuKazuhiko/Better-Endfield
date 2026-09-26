#include "log.h"

#include <android/log.h>

#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <string>

namespace betterendfield {
namespace {

constexpr char kLogTag[] = "BetterEndfield";
// The on-device journal tails these entries; keep it bounded so a chatty
// module cannot grow memory without limit. 512 because module init bursts
// (the UI module logs one line per resolved contract) must not evict a
// sibling module's failure evidence.
constexpr std::size_t kRingCapacity = 512;

std::mutex g_log_mutex;
std::string g_ring[kRingCapacity];
std::size_t g_ring_next = 0;
std::size_t g_ring_total = 0;

void Remember(const char* component, const char* message) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_ring[g_ring_next] = std::string("[") + component + "] " + message;
    g_ring_next = (g_ring_next + 1) % kRingCapacity;
    ++g_ring_total;
}

void Write(int priority, const char* component, const char* message) {
    __android_log_print(priority, kLogTag, "[%s] %s", component, message);
    Remember(component, message);
    const char* diagnostics = std::getenv("BETTER_ENDFIELD_DIAGNOSTICS_PATH");
    if (diagnostics != nullptr && *diagnostics != '\0') {
        if (FILE* file = std::fopen(diagnostics, "a")) {
            std::fprintf(file, "[%s] %s\n", component, message);
            std::fclose(file);
        }
    }
}

}  // namespace

void LogInfo(const char* component, const char* message) {
    // MIUI suppresses injected native INFO messages for this game process.
    // Keep alpha diagnostics visible without using fatal/error severity.
    Write(ANDROID_LOG_WARN, component, message);
}

void LogError(const char* component, const char* message) {
    Write(ANDROID_LOG_ERROR, component, message);
}

std::string CopyNativeLogSince(std::size_t& cursor) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (cursor > g_ring_total) cursor = 0;  // stale reader from an old session
    if (g_ring_total - cursor > kRingCapacity) cursor = g_ring_total - kRingCapacity;
    std::string out;
    for (std::size_t i = cursor; i < g_ring_total; ++i) {
        // A monotonic serial lets the panel reject lines it has already
        // recorded (a file truncate resets the journal's byte offset, which
        // would otherwise replay the whole log every 250 ms).
        out += "#" + std::to_string(i + 1) + " " + g_ring[i % kRingCapacity] + "\n";
    }
    cursor = g_ring_total;
    return out;
}

}  // namespace betterendfield
