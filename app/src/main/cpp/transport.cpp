#include <jni.h>
#include <android/sharedmem.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/mman.h>
#include <unistd.h>
#include <poll.h>
#include <new>
#include <memory>
#include <algorithm>
#include <thread>
#include <mutex>
#include <atomic>
#include <array>
#include <chrono>
#include <cstring>
#include <string>
#include <cerrno>
#include "AudioBufferHeader.h"
namespace {
constexpr const char* path="/data/vendor/virtualmic/virtual_mic.sock";
constexpr size_t ringSize=32768;
constexpr size_t mapSize=sizeof(virtualmic::AudioBufferHeader)+ringSize;
static_assert(sizeof(virtualmic::AudioBufferHeader)==64,"Requires upstream arm64 ABI");
static_assert(std::atomic<uint32_t>::is_always_lock_free);
static_assert(std::atomic<uint64_t>::is_always_lock_free);
struct Fd{int n=-1;~Fd(){if(n>=0)close(n);}Fd()=default;Fd(const Fd&)=delete;Fd& operator=(const Fd&)=delete;};
std::mutex mutex;
std::thread producer;
std::atomic<bool> stopping{true};
std::atomic<uint64_t> frames{0},overruns{0},consumed{0};
// Keep mapping alive until writer has joined; fd is also owned by HAL after SCM_RIGHTS.
struct Mapping {
 Fd fd;void* p=MAP_FAILED;
 ~Mapping(){if(p!=MAP_FAILED)munmap(p,mapSize);}
};
std::unique_ptr<Mapping> mapping;
int connectSocket(){
 int fd=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);if(fd<0)return -1;
 timeval tv{1,0};setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&tv,sizeof(tv));setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&tv,sizeof(tv));
 sockaddr_un addr{};addr.sun_family=AF_UNIX;std::strncpy(addr.sun_path,path,sizeof(addr.sun_path)-1);
 if(connect(fd,reinterpret_cast<sockaddr*>(&addr),sizeof(addr))<0){close(fd);return -1;}return fd;
}
bool sendFd(int socket,int fd){
 char token='V';iovec iov{&token,1};alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))]{};
 msghdr msg{};msg.msg_iov=&iov;msg.msg_iovlen=1;msg.msg_control=control;msg.msg_controllen=sizeof(control);
 cmsghdr* c=CMSG_FIRSTHDR(&msg);c->cmsg_level=SOL_SOCKET;c->cmsg_type=SCM_RIGHTS;c->cmsg_len=CMSG_LEN(sizeof(int));std::memcpy(CMSG_DATA(c),&fd,sizeof(fd));
 if(sendmsg(socket,&msg,MSG_NOSIGNAL)!=1)return false;
 uint64_t size=mapSize;size_t done=0;
 while(done<sizeof(size)){ssize_t n=send(socket,reinterpret_cast<char*>(&size)+done,sizeof(size)-done,MSG_NOSIGNAL);if(n<0&&errno==EINTR)continue;if(n<=0)return false;done+=static_cast<size_t>(n);}return true;
}
void stopLocked(){stopping=true;if(producer.joinable())producer.join();if(mapping){auto* h=static_cast<virtualmic::AudioBufferHeader*>(mapping->p);h->flags.store(0,std::memory_order_release);}mapping.reset();}
void writeProbe(){
 auto* h=static_cast<virtualmic::AudioBufferHeader*>(mapping->p);
 auto* data=reinterpret_cast<uint8_t*>(mapping->p)+sizeof(*h);
 std::array<int16_t,960> block{};uint32_t phase=0;
 auto due=std::chrono::steady_clock::now();
 while(!stopping){
  // 1023-chip deterministic pseudorandom signal, each chip 48 samples (1 ms).
  // Nothing is ever sent to AudioTrack / speaker.
  for(size_t f=0;f<480;f++){
   uint32_t v=(phase/48)%1023+1;
   v^=v>>16;v*=0x7feb352dU;v^=v>>15;v*=0x846ca68bU;v^=v>>16;
   const int16_t sample=(v&1)?8192:-8192;
   block[2*f]=sample;block[2*f+1]=sample;phase=(phase+1)%(1023*48);
  }
  const uint32_t w=h->writePos.load(std::memory_order_relaxed),r=h->readPos.load(std::memory_order_acquire);
  if(r>=ringSize||w>=ringSize){stopping=true;break;}
  const uint32_t avail=(r+ringSize-w-1)%ringSize;
  if(avail>=sizeof(block)){
   size_t first=std::min(sizeof(block),ringSize-w);std::memcpy(data+w,block.data(),first);std::memcpy(data,reinterpret_cast<uint8_t*>(block.data())+first,sizeof(block)-first);
   h->writePos.store((w+sizeof(block))%ringSize,std::memory_order_release);
   h->totalSamplesWritten.fetch_add(960,std::memory_order_relaxed);frames+=480;
  }else ++overruns;
  consumed=h->totalSamplesRead.load(std::memory_order_relaxed)/2;
  due+=std::chrono::milliseconds(10);std::this_thread::sleep_until(due);
 }
}
jstring string(JNIEnv* env,const std::string& s){return env->NewStringUTF(s.c_str());}
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_voiceshift_Native_probe(JNIEnv* env,jobject){
 Fd socket;socket.n=connectSocket();if(socket.n<0)return string(env,"Unavailable: HAL socket connect failed ("+std::string(strerror(errno))+"). Root alone does not install a HAL.");
 return string(env,"HAL socket reachable. PCM transport and capture NOT verified.");
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_voiceshift_Native_startProbe(JNIEnv* env,jobject){
 std::lock_guard<std::mutex> lock(mutex);stopLocked();
 try{
  auto m=std::make_unique<Mapping>();m->fd.n=ASharedMemory_create("voiceshift-test",mapSize);
  if(m->fd.n<0)return string(env,"Shared memory unavailable");
  m->p=mmap(nullptr,mapSize,PROT_READ|PROT_WRITE,MAP_SHARED,m->fd.n,0);
  if(m->p==MAP_FAILED)return string(env,"Shared memory mapping failed");
  auto* h=new(m->p) virtualmic::AudioBufferHeader{};
  h->magic=virtualmic::AUDIO_BUFFER_MAGIC;h->version=1;h->sampleRate=48000;h->channelCount=2;h->format=virtualmic::AudioFormat::PCM_16_BIT;h->bytesPerSample=2;h->ringBufferOffset=sizeof(*h);h->ringBufferSize=ringSize;h->flags=3;
  Fd socket;socket.n=connectSocket();if(socket.n<0||!sendFd(socket.n,m->fd.n))return string(env,"HAL handoff failed; no virtual microphone verified");
  mapping=std::move(m);frames=0;overruns=0;consumed=0;stopping=false;producer=std::thread(writeProbe);
  return string(env,"SENDING");
 }catch(...){stopLocked();return string(env,"Transport initialization failed");}
}
extern "C" JNIEXPORT void JNICALL Java_dev_voiceshift_Native_stopProbe(JNIEnv*,jobject){std::lock_guard<std::mutex> lock(mutex);stopLocked();}
extern "C" JNIEXPORT jlongArray JNICALL Java_dev_voiceshift_Native_stats(JNIEnv* env,jobject){jlong a[]={static_cast<jlong>(frames.load()),static_cast<jlong>(consumed.load()),static_cast<jlong>(overruns.load())};jlongArray out=env->NewLongArray(3);if(out)env->SetLongArrayRegion(out,0,3,a);return out;}
