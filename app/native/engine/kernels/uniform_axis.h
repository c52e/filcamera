/*
 * Spektrafilm for Android — native engine: O(1) uniform-axis bracket lookup.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm.
 *
 * EXPORT_FASTPATH item 1: replaces the per-pixel binary search in the density
 * lookups with an O(1) index estimate — WITHOUT changing a single output byte.
 * The estimate is only a starting point; a fix-up walk restores the EXACT
 * bracket the right-biased binary search (numpy searchsorted side='right')
 * would pick, so the interpolation runs on identical operands in identical
 * order and the result is bit-identical wherever the fast path engages.
 *
 * The fast path engages only for an axis that detect_uniform_axis approves:
 * strictly ascending, all-finite, and within step/4 of a uniform grid. Every
 * bundled profile's log_exposure axis is exactly uniform (n=256), and dividing
 * it by a per-channel gamma (how the density-lookup axes are built) perturbs
 * uniformity by float rounding only — far inside the step/4 budget. An axis
 * that fails detection (e.g. the non-uniform DIR-coupler le0 axis, a
 * descending axis from a negative gamma, repeated knots) keeps the caller's
 * existing binary search, byte-identical to before.
 */
#ifndef SPK_KERNELS_UNIFORM_AXIS_H
#define SPK_KERNELS_UNIFORM_AXIS_H

#include <cmath>

namespace spk {

// Precomputed O(1) lookup hint for one axis. ok == false means the axis did
// not qualify and the caller must keep its exact binary-search path.
template <typename T>
struct UniformAxis {
    bool ok = false;
    T x0 = T(0);
    T inv_step = T(0);
};

// Analyze axis xa[0], xa[stride], ... xa[(n-1)*stride] once per (re)build —
// O(n), run far from the per-pixel loops. Approves the axis only when the
// O(1) estimate in uniform_axis_bracket is guaranteed to land within one cell
// of the true bracket: strictly ascending, every element finite, and every
// element within step/4 of the uniform fit x0 + k*step.
template <typename T>
UniformAxis<T> detect_uniform_axis(const T* xa, int n, int stride = 1) {
    UniformAxis<T> ua;
    if (!xa || n < 2 || stride < 1) return ua;
    const T first = xa[0];
    const T last = xa[(n - 1) * stride];
    if (!std::isfinite(first) || !std::isfinite(last) || !(last > first))
        return ua;
    const T step = (last - first) / static_cast<T>(n - 1);
    if (!(step > T(0)) || !std::isfinite(step)) return ua;
    const T tol = step * T(0.25);
    T prev = first;
    for (int k = 1; k < n; ++k) {
        const T v = xa[k * stride];
        if (!std::isfinite(v) || !(v > prev)) return ua;  // strictly ascending
        const T fit = first + static_cast<T>(k) * step;
        const T dev = v > fit ? v - fit : fit - v;
        if (dev > tol) return ua;  // too non-uniform for a within-1 estimate
        prev = v;
    }
    const T inv = T(1) / step;
    if (!std::isfinite(inv)) return ua;
    ua.ok = true;
    ua.x0 = first;
    ua.inv_step = inv;
    return ua;
}

// O(1) bracket lookup. PRECONDITIONS (the caller's clamp/NaN guards provide
// them): ua.ok, x is not NaN, and xa[0] < x < xa[(n-1)*stride].
// POSTCONDITION: returns the unique low in [0, n-2] with
//   xa[low*stride] <= x < xa[(low+1)*stride],
// which for a strictly ascending axis is exactly searchsorted(side='right')-1
// — the same bracket the binary searches this replaces would return, so the
// downstream interpolation is bit-identical.
//
// The fix-up loops make correctness independent of the estimate: they walk to
// the bracket invariant no matter where the estimate lands. detect's step/4
// tolerance only guarantees SPEED (each loop then iterates at most once).
template <typename T>
inline int uniform_axis_bracket(T x, const T* xa, int n, int stride,
                                const UniformAxis<T>& ua) {
    const T e = (x - ua.x0) * ua.inv_step;
    int low = e > T(0) ? static_cast<int>(e) : 0;
    if (low > n - 2) low = n - 2;
    while (low > 0 && x < xa[low * stride]) --low;
    while (low < n - 2 && x >= xa[(low + 1) * stride]) ++low;
    return low;
}

}  // namespace spk

#endif  // SPK_KERNELS_UNIFORM_AXIS_H
