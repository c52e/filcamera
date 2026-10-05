/*
 * Spektrafilm for Android — native engine: memo for built 3D LUTs.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3 — see lut3d_cache.h.
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm.
 */
#include "kernels/lut3d_cache.h"

#include <utility>

namespace spk {

void Lut3DCache::touch_locked(std::map<std::string, Entry>::iterator it) {
    lru_.splice(lru_.begin(), lru_, it->second.lru);
}

void Lut3DCache::evict_to_fit_locked(size_t incoming_bytes) {
    // Drop least-recently-used entries while the cache holds anything and the
    // incoming entry would still overflow the budget. When incoming_bytes alone
    // exceeds the budget, the loop simply empties the cache and stops (the
    // `!entries_.empty()` bound), and the caller inserts the oversize entry
    // unconditionally afterwards — so it is cached as the sole resident rather
    // than refused (a caller using an unusually large lut_resolution still gets
    // single-slot memoization). The next insert then evicts it here.
    while (!entries_.empty() && bytes_ + incoming_bytes > budget_) {
        const std::string& oldest = lru_.back();
        auto it = entries_.find(oldest);
        if (it == entries_.end()) {  // defensive: keep the two structures in step
            lru_.pop_back();
            continue;
        }
        bytes_ -= it->second.bytes;
        entries_.erase(it);
        lru_.pop_back();
    }
}

std::shared_ptr<const PreparedLut3D> Lut3DCache::get_or_build(
    const std::string& key, const double xmin[3], const double xmax[3],
    int steps, void (*fn)(const double in[3], double out[3], void* ctx),
    void* ctx) {
    {
        std::lock_guard<std::mutex> g(mu_);
        auto it = entries_.find(key);
        if (it != entries_.end()) {
            touch_locked(it);
            ++hits_;
            return it->second.lut;
        }
        ++misses_;
    }

    // Build OUTSIDE the lock: the spectral integrals are the expensive part and
    // must not serialize concurrent renders. A racing thread may build the same
    // key concurrently; the build is a pure function of the key's inputs, so
    // both results are identical and either may win the insert.
    Lut3D built = build_lut_3d(xmin, xmax, steps, {}, fn, ctx);
    std::shared_ptr<const PreparedLut3D> prepared =
        prepare_lut_3d_pchip(std::move(built));
    const size_t sz = prepared_lut_3d_bytes(*prepared);

    std::lock_guard<std::mutex> g(mu_);
    auto it = entries_.find(key);
    if (it != entries_.end()) {  // lost the race — keep the resident copy
        touch_locked(it);
        return it->second.lut;
    }
    evict_to_fit_locked(sz);
    lru_.push_front(key);
    Entry e;
    e.lut = prepared;
    e.bytes = sz;
    e.lru = lru_.begin();
    entries_.emplace(key, std::move(e));
    bytes_ += sz;
    return prepared;
}

uint64_t Lut3DCache::hits() const {
    std::lock_guard<std::mutex> g(mu_);
    return hits_;
}

uint64_t Lut3DCache::misses() const {
    std::lock_guard<std::mutex> g(mu_);
    return misses_;
}

size_t Lut3DCache::bytes() const {
    std::lock_guard<std::mutex> g(mu_);
    return bytes_;
}

}  // namespace spk
