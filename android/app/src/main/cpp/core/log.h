#pragma once

#include <cstddef>
#include <string>

namespace betterendfield {

void LogInfo(const char* component, const char* message);
void LogError(const char* component, const char* message);

// Lines logged since the previous call, oldest first, for the on-device
// journal (input_relay.cpp appends them to the native log file the panel
// tails). A stale cursor — one from before the ring existed — restarts at 0.
std::string CopyNativeLogSince(std::size_t& cursor);

}  // namespace betterendfield
