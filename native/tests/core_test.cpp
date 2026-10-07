#include "voiceshift/session.hpp"
#include <cassert>
#include <iostream>
#include <vector>
using namespace voiceshift;
struct Model:Converter {bool fail=false;bool process(const float* p,size_t n,float* o,size_t cap,size_t& k)noexcept override {if(fail)return false;k=std::min(n,cap);std::copy(p,p+k,o);return true;}void reset()noexcept override{}};
struct Sink:Route {std::atomic<int> writes{0};std::atomic<bool> enabled{false};std::atomic<bool> fail{false};bool enable()noexcept override{enabled=true;return !fail;}bool write(const float*,size_t)noexcept override{++writes;return !fail;}void disable()noexcept override{enabled=false;}};
struct Mic:Capture {bool fail=false;std::atomic<bool> off{true};std::thread t;bool start(Ring<float,16384>& r)noexcept override{if(fail)return false;off=false;t=std::thread([this,&r]{std::array<float,640>a{};while(!off){r.push(a.data(),a.size());std::this_thread::sleep_for(std::chrono::milliseconds(1));}});return true;}void stop()noexcept override{off=true;if(t.joinable())t.join();}~Mic(){stop();}};
bool waitState(Session& s,State state){for(int i=0;i<1000;i++){if(s.state()==state)return true;std::this_thread::sleep_for(std::chrono::milliseconds(1));}return false;}
int main(){
 Ring<uint32_t,1024> r;
 std::thread producer([&]{for(uint32_t i=0;i<100000;i++){while(r.push(&i,1)==0)std::this_thread::yield();}});
 for(uint32_t i=0;i<100000;i++){uint32_t v;while(r.pop(&v,1)==0)std::this_thread::yield();assert(v==i);}producer.join();
 Model m;Sink route;Mic mic;Session s(m,route,mic);Evidence e;
 assert(!s.start(e));assert(!route.enabled);
 e={true,true,true,true,true,true,true};
 for(int i=0;i<100;i++){assert(s.start(e));assert(s.start(e));s.stop();assert(s.state()==State::IDLE);assert(!route.enabled);}
 m.fail=true;assert(s.start(e));assert(waitState(s,State::BYPASS));s.stop();m.fail=false;
 route.fail=true;assert(!s.start(e));assert(!route.enabled);route.fail=false;
 mic.fail=true;assert(!s.start(e));assert(!route.enabled);mic.fail=false;
 assert(s.start(e));route.fail=true;assert(waitState(s,State::ERROR));assert(!route.enabled);s.stop();route.fail=false;
 std::thread a([&]{for(int i=0;i<100;i++){s.start(e);s.stop();}});
 std::thread b([&]{for(int i=0;i<100;i++){s.stop();s.start(e);}});a.join();b.join();s.stop();
 std::cout<<"PASS: 100000 ordered samples; 100 lifecycle cycles; concurrent control; inference bypass; capture/route rollback\n";
}
