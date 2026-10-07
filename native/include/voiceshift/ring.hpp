#pragma once
#include <array>
#include <atomic>
#include <algorithm>
#include <cstddef>
#include <cstdint>
namespace voiceshift {
// One producer, one consumer. Never reset until both have stopped.
template<class T, size_t N> class Ring {
 static_assert(N > 1 && (N & (N-1)) == 0);
 std::array<T,N> data_{};
 alignas(64) std::atomic<uint64_t> write_{0};
 alignas(64) std::atomic<uint64_t> read_{0};
public:
 size_t push(const T* src, size_t n) noexcept {
  const auto w=write_.load(std::memory_order_relaxed);
  const auto r=read_.load(std::memory_order_acquire);
  n=std::min(n,N-static_cast<size_t>(w-r));
  for(size_t i=0;i<n;i++) data_[(w+i)&(N-1)]=src[i];
  write_.store(w+n,std::memory_order_release); return n;
 }
 size_t pop(T* dst, size_t n) noexcept {
  const auto r=read_.load(std::memory_order_relaxed);
  const auto w=write_.load(std::memory_order_acquire);
  n=std::min(n,static_cast<size_t>(w-r));
  for(size_t i=0;i<n;i++) dst[i]=data_[(r+i)&(N-1)];
  read_.store(r+n,std::memory_order_release); return n;
 }
 size_t size() const noexcept { return write_.load(std::memory_order_acquire)-read_.load(std::memory_order_acquire); }
 void resetStopped() noexcept { read_.store(0); write_.store(0); }
};
}
