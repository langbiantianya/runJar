#pragma once

#include <android/log.h>

int runjar_log(int prio, const char *fmt, ...);

#define LOGI(...) runjar_log(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) runjar_log(ANDROID_LOG_WARN, __VA_ARGS__)
#define LOGE(...) runjar_log(ANDROID_LOG_ERROR, __VA_ARGS__)
