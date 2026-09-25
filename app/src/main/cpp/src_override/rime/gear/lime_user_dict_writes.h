#pragma once

#include <atomic>

namespace lime {

inline std::atomic<bool> g_user_dict_writes_enabled{true};

inline void set_user_dict_writes_enabled(bool enabled) {
    g_user_dict_writes_enabled.store(enabled, std::memory_order_release);
}

inline bool user_dict_writes_enabled() {
    return g_user_dict_writes_enabled.load(std::memory_order_acquire);
}

}  // namespace lime
