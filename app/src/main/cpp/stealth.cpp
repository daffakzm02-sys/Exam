#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <stdarg.h>
#include <unistd.h>
#include <stdlib.h>
#include <cstdio>
#include <cstring>
#include <string>

// WAJIB: Bungkus header C dengan extern "C"
extern "C" {
    #include <xhook.h>
}

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Stealth", __VA_ARGS__)

static int   (*orig_open)(const char*, int, ...)        = nullptr;
static int   (*orig_openat)(int, const char*, int, ...) = nullptr;
static FILE* (*orig_fopen)(const char*, const char*)    = nullptr;

static bool is_maps_path(const char* p) {
    if (!p) return false;
    std::string s(p);
    return s.find("/proc/self/maps") != std::string::npos
        || s.find("/proc/self/task/") != std::string::npos;
}

static int sanitized_maps_fd(int flags) {
    char tmpl[] = "/data/local/tmp/.uih_XXXXXX";
    int tmpfd = mkstemp(tmpl);
    if (tmpfd < 0) return -1;

    FILE* in = orig_fopen("/proc/self/maps", "r");
    if (!in) { close(tmpfd); unlink(tmpl); return -1; }

    FILE* out = fdopen(tmpfd, "w");
    if (!out) { fclose(in); close(tmpfd); unlink(tmpl); return -1; }

    char line[8192];
    while (fgets(line, sizeof(line), in)) {
        if (strstr(line, "libstealth.so")) continue;
        if (strstr(line, "libmahiru")) continue;
        fputs(line, out);
    }
    fclose(in);
    fflush(out);
    fclose(out);

    int fd = orig_open(tmpl, flags);
    unlink(tmpl);
    return fd;
}

static int my_open(const char* path, int flags, ...) {
    if (is_maps_path(path)) return sanitized_maps_fd(flags);
    va_list ap; va_start(ap, flags);
    int mode = (flags & O_CREAT) ? va_arg(ap, int) : 0;
    va_end(ap);
    return orig_open(path, flags, mode);
}

static int my_openat(int dirfd, const char* path, int flags, ...) {
    if (is_maps_path(path)) return sanitized_maps_fd(flags);
    va_list ap; va_start(ap, flags);
    int mode = (flags & O_CREAT) ? va_arg(ap, int) : 0;
    va_end(ap);
    return orig_openat(dirfd, path, flags, mode);
}

static FILE* my_fopen(const char* path, const char* mode) {
    if (is_maps_path(path)) {
        int fd = sanitized_maps_fd(O_RDONLY);
        if (fd < 0) return nullptr;
        return fdopen(fd, mode);
    }
    return orig_fopen(path, mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_system_android_uih_StealthHelper_00024NativeHook_install(JNIEnv*, jobject) {
    xhook_register(".*\\.so$", "open",   (void*)my_open,   (void**)&orig_open);
    xhook_register(".*\\.so$", "openat", (void*)my_openat, (void**)&orig_openat);
    xhook_register(".*\\.so$", "fopen",  (void*)my_fopen,  (void**)&orig_fopen);
    xhook_refresh(0);
    LOGI("native stealth installed");
}

extern "C" JNIEXPORT void JNICALL
Java_com_system_android_uih_StealthHelper_00024NativeHook_uninstall(JNIEnv*, jobject) {
    xhook_clear();
    xhook_refresh(0);
}