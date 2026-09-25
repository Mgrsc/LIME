#ifndef RIME_NO_LOGGING_H_
#define RIME_NO_LOGGING_H_

#include <android/log.h>
#include <sstream>

namespace rime {

constexpr int kLogINFO = ANDROID_LOG_INFO;
constexpr int kLogWARNING = ANDROID_LOG_WARN;
constexpr int kLogERROR = ANDROID_LOG_ERROR;
constexpr int kLogFATAL = ANDROID_LOG_FATAL;

class AndroidLogger {
 public:
  AndroidLogger(int priority = ANDROID_LOG_INFO) : priority_(priority) {}
  ~AndroidLogger() {
    __android_log_print(priority_, "RimeCore", "%s", oss_.str().c_str());
  }
  AndroidLogger& stream() { return *this; }
  template <class T>
  AndroidLogger& operator<<(const T& x) {
    oss_ << x;
    return *this;
  }
 private:
  int priority_;
  std::ostringstream oss_;
};

// Keep disabled stream operands unevaluated and safe inside if/else statements.
struct LogVoidify {
  void operator&(AndroidLogger&) const {}
};

}  // namespace rime

#define RIME_NO_LOG \
  true ? (void)0 : rime::LogVoidify() & rime::AndroidLogger().stream()
#define LOG(severity) rime::AndroidLogger(rime::kLog##severity).stream()
#define VLOG(verboselevel) RIME_NO_LOG
#define LOG_IF(severity, condition) \
  !(condition) ? (void)0 : rime::LogVoidify() & LOG(severity)
#define LOG_EVERY_N(severity, n) RIME_NO_LOG
#define LOG_IF_EVERY_N(severity, condition, n) RIME_NO_LOG
#define LOG_ASSERT(condition) RIME_NO_LOG

#define RIME_NO_CHECK (void)0

#define CHECK(condition) RIME_NO_CHECK
#define CHECK_EQ(val1, val2) RIME_NO_CHECK
#define CHECK_NE(val1, val2) RIME_NO_CHECK
#define CHECK_LE(val1, val2) RIME_NO_CHECK
#define CHECK_LT(val1, val2) RIME_NO_CHECK
#define CHECK_GE(val1, val2) RIME_NO_CHECK
#define CHECK_GT(val1, val2) RIME_NO_CHECK
#define CHECK_NOTNULL(val) RIME_NO_CHECK
#define CHECK_STREQ(str1, str2) RIME_NO_CHECK
#define CHECK_STRCASEEQ(str1, str2) RIME_NO_CHECK
#define CHECK_STRNE(str1, str2) RIME_NO_CHECK
#define CHECK_STRCASENE(str1, str2) RIME_NO_CHECK

// Native search tracing is too expensive even in debug builds. Use the
// metadata-only LimeInputTrace for input latency diagnostics instead.
#define DLOG(severity) RIME_NO_LOG
#define DVLOG(verboselevel) RIME_NO_LOG
#define DLOG_IF(severity, condition) RIME_NO_LOG
#define DLOG_EVERY_N(severity, n) LOG_EVERY_N(severity, n)
#define DLOG_IF_EVERY_N(severity, condition, n) \
  LOG_IF_EVERY_N(severity, condition, n)
#define DLOG_ASSERT(condition) LOG_ASSERT(condition)

#define DCHECK(condition) CHECK(condition)
#define DCHECK_EQ(val1, val2) CHECK_EQ(val1, val2)
#define DCHECK_NE(val1, val2) CHECK_NE(val1, val2)
#define DCHECK_LE(val1, val2) CHECK_LE(val1, val2)
#define DCHECK_LT(val1, val2) CHECK_LT(val1, val2)
#define DCHECK_GE(val1, val2) CHECK_GE(val1, val2)
#define DCHECK_GT(val1, val2) CHECK_GT(val1, val2)
#define DCHECK_NOTNULL(val) CHECK_NOTNULL(val)
#define DCHECK_STREQ(str1, str2) CHECK_STREQ(str1, str2)
#define DCHECK_STRCASEEQ(str1, str2) CHECK_STRCASEEQ(str1, str2)
#define DCHECK_STRNE(str1, str2) CHECK_STRNE(str1, str2)
#define DCHECK_STRCASENE(str1, str2) CHECK_STRCASENE(str1, str2)

#endif  // RIME_NO_LOGGING_H_
