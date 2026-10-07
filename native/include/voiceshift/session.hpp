#pragma once
#include "ring.hpp"
#include <atomic>
#include <array>
#include <thread>
#include <mutex>
#include <chrono>
#include <cstdint>
namespace voiceshift {
enum class State { IDLE, INITIALIZING, READY, STARTING, RUNNING, BYPASS, STOPPING, ERROR };
struct Evidence {
 bool modelParity=false, referenceCached=false, transport=false;
 bool defaultMicCapture=false, voiceCommunicationCapture=false;
 bool isolatedPhysicalInput=false, rollback=false;
 bool ready() const { return modelParity && referenceCached && transport && defaultMicCapture && voiceCommunicationCapture && isolatedPhysicalInput && rollback; }
};
struct Converter {
 virtual ~Converter()=default;
 // Called only on worker. Output count may be zero while filling lookahead.
 virtual bool process(const float*,size_t,float*,size_t,size_t&) noexcept=0;
 virtual void reset() noexcept=0;
};
struct Route {
 virtual ~Route()=default;
 virtual bool enable() noexcept=0;
 virtual bool write(const float*,size_t) noexcept=0;
 virtual void disable() noexcept=0;
};
struct Capture {
 virtual ~Capture()=default;
 // Must start the bypass-exempt PHYSICAL endpoint, never default virtual input.
 virtual bool start(Ring<float,16384>&) noexcept=0;
 virtual void stop() noexcept=0; // Joins callback before returning.
};
class Session {
 Converter& model_; Route& route_; Capture& capture_;
 Ring<float,16384> input_;
 std::mutex control_;
 std::thread worker_;
 std::atomic<bool> stop_{false}, bypass_{false};
 std::atomic<State> state_{State::IDLE};
 void run() noexcept {
  std::array<float,640> in{}; std::array<float,2560> out{};
  while(!stop_.load()) {
   if(input_.size()<in.size()) { std::this_thread::sleep_for(std::chrono::milliseconds(1)); continue; }
   input_.pop(in.data(),in.size()); size_t count=0;
   if(bypass_.load()) { std::copy(in.begin(),in.end(),out.begin());count=in.size(); }
   else if(!model_.process(in.data(),in.size(),out.data(),out.size(),count) || count>out.size()) {
    bypass_.store(true);state_.store(State::BYPASS);
    std::copy(in.begin(),in.end(),out.begin());count=in.size();
   }
   if(count && !route_.write(out.data(),count)) {
    route_.disable(); stop_.store(true); state_.store(State::ERROR); break;
   }
  }
 }
public:
 Session(Converter& m,Route& r,Capture& c):model_(m),route_(r),capture_(c){}
 ~Session(){stop();}
 State state() const {return state_.load();}
 bool start(const Evidence& e) {
  std::lock_guard<std::mutex> lock(control_);
  if(state_==State::RUNNING || state_==State::BYPASS) return true;
  if(worker_.joinable()) {stop_.store(true);worker_.join();capture_.stop();route_.disable();}
  state_=State::STARTING;
  if(!e.ready()) {state_=State::ERROR;return false;}
  input_.resetStopped();model_.reset();bypass_=false;stop_=false;
  if(!route_.enable()) {route_.disable();state_=State::ERROR;return false;}
  if(!capture_.start(input_)) {capture_.stop();route_.disable();state_=State::ERROR;return false;}
  state_=State::RUNNING;
  try {worker_=std::thread(&Session::run,this);} catch(...) {stop_=true;capture_.stop();route_.disable();state_=State::ERROR;return false;}
  return true;
 }
 void setBypass(bool value) {std::lock_guard<std::mutex> lock(control_);if(state_==State::RUNNING || state_==State::BYPASS){bypass_=value;state_=value?State::BYPASS:State::RUNNING;}}
 void stop() noexcept {
  std::lock_guard<std::mutex> lock(control_);
  if(state_==State::IDLE)return;
  state_=State::STOPPING;stop_=true;
  if(worker_.joinable())worker_.join(); // No writes can race routing rollback.
  route_.disable();capture_.stop();input_.resetStopped();model_.reset();state_=State::IDLE;
 }
};
}
