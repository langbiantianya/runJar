#include <android/log.h>
#include <jni.h>
#include <stdarg.h>
#include <stdio.h>

int runjar_log(int prio, const char *fmt, ...) {
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n < 0) return n;
    if ((size_t) n >= sizeof(buf)) n = (int) sizeof(buf) - 1;
    __android_log_print(prio, "runjar", "%s", buf);
    return n;
}
