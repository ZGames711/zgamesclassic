// Minimal software-rendering libretro frontend for PS1 cores (SwanStation / PCSX ReARMed).
//
// The core (a libretro .so shipped in jniLibs) is dlopen()ed, its callbacks are served here:
//   video  -> copied into the Android Surface (ANativeWindow)
//   audio  -> returned to Kotlin after every frame, which writes it to an AudioTrack
//   input  -> a joypad bitmask set from the on-screen controls
// Hardware rendering is intentionally refused, so the core uses its software renderer.
// All retro_* calls happen on ONE thread (the emulation thread started by Kotlin).

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <atomic>
#include <algorithm>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#define TAG "PsxFrontend"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---- libretro ABI (subset, written out so no external header is needed) ----
struct retro_game_info { const char* path; const void* data; size_t size; const char* meta; };
struct retro_system_info { const char* library_name; const char* library_version; const char* valid_extensions; bool need_fullpath; bool block_extract; };
struct retro_game_geometry { unsigned base_width; unsigned base_height; unsigned max_width; unsigned max_height; float aspect_ratio; };
struct retro_system_timing { double fps; double sample_rate; };
struct retro_system_av_info { retro_game_geometry geometry; retro_system_timing timing; };
struct retro_variable { const char* key; const char* value; };
struct retro_log_callback { void (*log)(int level, const char* fmt, ...); };

typedef bool (*retro_environment_t)(unsigned cmd, void* data);
typedef void (*retro_video_refresh_t)(const void* data, unsigned width, unsigned height, size_t pitch);
typedef void (*retro_audio_sample_t)(int16_t left, int16_t right);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t* data, size_t frames);
typedef void (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned port, unsigned device, unsigned index, unsigned id);

static const unsigned RETRO_DEVICE_JOYPAD = 1;
static const unsigned RETRO_DEVICE_ANALOG = 5;
static const unsigned RETRO_DEVICE_ID_JOYPAD_MASK = 256;
static std::atomic<int> g_analogLX{0}, g_analogLY{0}, g_analogRX{0}, g_analogRY{0};

// ---- state ----
struct Core {
    void* handle = nullptr;
    void (*set_environment)(retro_environment_t) = nullptr;
    void (*set_video_refresh)(retro_video_refresh_t) = nullptr;
    void (*set_audio_sample)(retro_audio_sample_t) = nullptr;
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
    void (*set_input_poll)(retro_input_poll_t) = nullptr;
    void (*set_input_state)(retro_input_state_t) = nullptr;
    void (*init)(void) = nullptr;
    void (*deinit)(void) = nullptr;
    unsigned (*api_version)(void) = nullptr;
    void (*get_system_info)(retro_system_info*) = nullptr;
    void (*get_system_av_info)(retro_system_av_info*) = nullptr;
    void (*set_controller_port_device)(unsigned, unsigned) = nullptr;
    void (*run)(void) = nullptr;
    bool (*load_game)(const retro_game_info*) = nullptr;
    void (*unload_game)(void) = nullptr;
    size_t (*serialize_size)(void) = nullptr;
    bool (*serialize)(void*, size_t) = nullptr;
    bool (*unserialize)(const void*, size_t) = nullptr;
};

static Core g;
static std::string g_systemDir, g_saveDir, g_corePath;
// Core options set from the app's emulator settings (EmuSettings) before the core loads.
// Keys are the SwanStation libretro option keys, e.g. "duckstation_GPU.Renderer".
// std::map nodes are stable, so the const char* handed to the core stays valid.
static std::map<std::string, std::string> g_variables;
static std::atomic<bool> g_loaded{false};
static std::atomic<bool> g_gameLoaded{false};
static std::atomic<bool> g_analogEnabled{true};  // false = digital pad only, sticks off
static std::atomic<bool> g_shutdown{false};
static std::atomic<int> g_inputMask{0};
static int g_pixelFormat = 1;  // 1 = XRGB8888, 2 = RGB565
static double g_fps = 60.0, g_sampleRate = 44100.0;
static std::vector<int16_t> g_audio;
static std::vector<uint8_t> g_romData;

static std::mutex g_winMutex;
static ANativeWindow* g_win = nullptr;
static int g_lastW = 0, g_lastH = 0, g_lastFmt = 0;

// ---- callbacks served to the core ----
static void log_cb(int level, const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    int prio = level >= 3 ? ANDROID_LOG_ERROR : (level == 2 ? ANDROID_LOG_WARN : ANDROID_LOG_INFO);
    __android_log_print(prio, "PsxCore", "%s", buf);
}

static bool env_cb(unsigned cmdRaw, void* data) {
    unsigned cmd = cmdRaw & 0xFFFFu;  // strip EXPERIMENTAL / PRIVATE flags
    switch (cmd) {
        case 1:  return true;                                           // SET_ROTATION
        case 2:  if (data) *(bool*)data = false; return true;          // GET_OVERSCAN
        case 3:  if (data) *(bool*)data = true; return true;           // GET_CAN_DUPE
        case 6:  return true;                                           // SET_MESSAGE
        case 7:  g_shutdown = true; return true;                        // SHUTDOWN
        case 8:  return true;                                           // SET_PERFORMANCE_LEVEL
        case 9:  if (data) *(const char**)data = g_systemDir.c_str(); return true;   // GET_SYSTEM_DIRECTORY
        case 10: {                                                      // SET_PIXEL_FORMAT
            if (!data) return false;
            int fmt = *(int*)data;
            if (fmt == 1 || fmt == 2) { g_pixelFormat = fmt; return true; }
            return false;
        }
        case 11: return true;                                           // SET_INPUT_DESCRIPTORS
        case 12: return true;                                           // SET_KEYBOARD_CALLBACK
        case 13: return true;                                           // SET_DISK_CONTROL_INTERFACE
        case 14: return false;                                          // SET_HW_RENDER -> software renderer
        case 15: {                                                      // GET_VARIABLE
            auto* v = (retro_variable*)data;
            if (!v || !v->key) return false;
            auto it = g_variables.find(v->key);
            if (it != g_variables.end()) { v->value = it->second.c_str(); return true; }
            // This frontend is software-only (SET_HW_RENDER is refused below),
            // so the renderer is always Software unless the app says otherwise.
            if (std::string(v->key) == "duckstation_GPU.Renderer") { v->value = "Software"; return true; }
            return false;  // not set -> the core uses its default
        }
        case 16: return true;                                           // SET_VARIABLES
        case 17: if (data) *(bool*)data = false; return true;          // GET_VARIABLE_UPDATE
        case 18: return true;                                           // SET_SUPPORT_NO_GAME
        case 19: if (data) *(const char**)data = g_corePath.c_str(); return true;     // GET_LIBRETRO_PATH
        case 24: if (data) *(uint64_t*)data = (1ull << RETRO_DEVICE_JOYPAD) | (1ull << RETRO_DEVICE_ANALOG); return true; // GET_INPUT_DEVICE_CAPABILITIES
        case 27: if (data) ((retro_log_callback*)data)->log = log_cb; return true;    // GET_LOG_INTERFACE
        case 30: if (data) *(const char**)data = g_systemDir.c_str(); return true;    // GET_CORE_ASSETS_DIRECTORY
        case 31: if (data) *(const char**)data = g_saveDir.c_str(); return true;      // GET_SAVE_DIRECTORY
        case 32: return true;                                           // SET_SYSTEM_AV_INFO
        case 34: return true;                                           // SET_SUBSYSTEM_INFO
        case 35: return true;                                           // SET_CONTROLLER_INFO
        case 37: return true;                                           // SET_GEOMETRY
        case 39: if (data) *(unsigned*)data = 0; return true;          // GET_LANGUAGE (English)
        case 42: return true;                                           // SET_SUPPORT_ACHIEVEMENTS
        case 44: return true;                                           // SET_SERIALIZATION_QUIRKS
        case 47: if (data) *(int*)data = 3; return true;               // GET_AUDIO_VIDEO_ENABLE
        case 49: if (data) *(bool*)data = false; return true;          // GET_FASTFORWARDING
        case 51: return true;                                           // GET_INPUT_BITMASKS
        case 52: if (data) *(unsigned*)data = 0; return true;          // GET_CORE_OPTIONS_VERSION -> plain SET_VARIABLES
        case 55: return true;                                           // SET_CORE_OPTIONS_DISPLAY
        case 59: if (data) *(unsigned*)data = 0; return true;          // GET_MESSAGE_INTERFACE_VERSION
        case 60: return true;                                           // SET_MESSAGE_EXT
        case 61: if (data) *(unsigned*)data = 1; return true;          // GET_INPUT_MAX_USERS
        case 63: return true;                                           // SET_MINIMUM_AUDIO_LATENCY
        default: return false;
    }
}

static inline void push_audio(const int16_t* d, size_t frames) {
    if (g_audio.size() > 65536) return;  // safety cap
    g_audio.insert(g_audio.end(), d, d + frames * 2);
}
static void audio_sample_cb(int16_t l, int16_t r) { int16_t s[2] = {l, r}; push_audio(s, 1); }
static size_t audio_batch_cb(const int16_t* d, size_t frames) { if (d) push_audio(d, frames); return frames; }
static void input_poll_cb(void) {}
static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0) return 0;
    if (device == RETRO_DEVICE_ANALOG) {
        if (index == 0) return (int16_t)(id == 0 ? g_analogLX.load(std::memory_order_relaxed) : g_analogLY.load(std::memory_order_relaxed));
        if (index == 1) return (int16_t)(id == 0 ? g_analogRX.load(std::memory_order_relaxed) : g_analogRY.load(std::memory_order_relaxed));
        return 0;
    }
    if (device != RETRO_DEVICE_JOYPAD) return 0;
    int mask = g_inputMask.load(std::memory_order_relaxed);
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)mask;
    if (id < 16) return (mask >> id) & 1;
    return 0;
}

static void video_cb(const void* data, unsigned w, unsigned h, size_t pitch) {
    if (!data || w == 0 || h == 0) return;
    std::lock_guard<std::mutex> lk(g_winMutex);
    if (!g_win) return;
    const int fmt = (g_pixelFormat == 2) ? WINDOW_FORMAT_RGB_565 : WINDOW_FORMAT_RGBA_8888;
    if (g_lastW != (int)w || g_lastH != (int)h || g_lastFmt != fmt) {
        ANativeWindow_setBuffersGeometry(g_win, (int32_t)w, (int32_t)h, fmt);
        g_lastW = (int)w; g_lastH = (int)h; g_lastFmt = fmt;
    }
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(g_win, &buf, nullptr) != 0) return;
    const unsigned rows = std::min<unsigned>(h, (unsigned)buf.height);
    const unsigned cols = std::min<unsigned>(w, (unsigned)buf.width);
    if (g_pixelFormat == 2) {
        for (unsigned y = 0; y < rows; ++y) {
            const uint8_t* s = (const uint8_t*)data + y * pitch;
            uint8_t* d = (uint8_t*)buf.bits + (size_t)y * buf.stride * 2;
            memcpy(d, s, cols * 2);
        }
    } else {
        for (unsigned y = 0; y < rows; ++y) {
            const uint32_t* s = (const uint32_t*)((const uint8_t*)data + y * pitch);
            uint32_t* d = (uint32_t*)buf.bits + (size_t)y * buf.stride;
            for (unsigned x = 0; x < cols; ++x) {
                uint32_t p = s[x];  // 0x00RRGGBB -> 0xFFBBGGRR (RGBA8888 in memory order)
                d[x] = 0xFF000000u | ((p & 0xFFu) << 16) | (p & 0x0000FF00u) | ((p >> 16) & 0xFFu);
            }
        }
    }
    ANativeWindow_unlockAndPost(g_win);
}

template <typename T>
static bool sym(T& out, const char* name) {
    out = reinterpret_cast<T>(dlsym(g.handle, name));
    if (!out) LOGE("missing symbol %s", name);
    return out != nullptr;
}

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

// ---- JNI ----
extern "C" {

// Sets one SwanStation core option (key like "duckstation_GPU.Renderer").
// Called from Kotlin (EmuSettings.applyAll) before nativeLoadCore; the core
// reads these through GET_VARIABLE when it boots.
JNIEXPORT void JNICALL
Java_com_evolutionofgames_zgamespsx_emulator_NativeCore_nativeSetVariable(JNIEnv* env, jobject, jstring key, jstring value) {
    std::string k = jstr(env, key);
    std::string v = jstr(env, value);
    if (!k.empty()) g_variables[k] = v;
}

JNIEXPORT jboolean JNICALL
Java_com_evolutionofgames_zgamespsx_emulator_NativeCore_nativeLoadCore(JNIEnv* env, jobject, jstring corePath, jstring systemDir, jstring saveDir) {
    if (g_loaded) return JNI_TRUE;
    g_corePath = jstr(env, corePath);
    g_systemDir = jstr(env, systemDir);
    g_saveDir = jstr(env, saveDir);
    g.handle = dlopen(g_corePath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!g.handle) { LOGE("dlopen failed: %s", dlerror()); return JNI_FALSE; }
    bool ok = sym(g.set_environment, "retro_set_environment") &&
              sym(g.set_video_refresh, "retro_set_video_refresh") &&
              sym(g.set_audio_sample, "retro_set_audio_sample") &&
              sym(g.set_audio_sample_batch, "retro_set_audio_sample_batch") &&
              sym(g.set_input_poll, "retro_set_input_poll") &&
              sym(g.set_input_state, "retro_set_input_state") &&
              sym(g.init, "retro_init") && sym(g.deinit, "retro_deinit") &&
              sym(g.api_version, "retro_api_version") &&
              sym(g.get_system_info, "retro_get_system_info") &&
              sym(g.get_system_av_info, "retro_get_system_av_info") &&
              sym(g.set_controller_port_device, "retro_set_controller_port_device") &&
              sym(g.run, "retro_run") && sym(g.load_game, "retro_load_game") &&
              sym(g.unload_game, "retro_unload_game");
    // Save states are optional: a core without them still boots, it just
    // can't save/load states. (The real symbol is retro_serialize_size,
    // not retro_get_serialize_size — the wrong name blocked every load.)
    sym(g.serialize_size, "retro_serialize_size");
    sym(g.serialize, "retro_serialize");
    sym(g.unserialize, "retro_unserialize");
    if (!ok) { dlclose(g.handle); g.handle = nullptr; return JNI_FALSE; }
    if (g.api_version() != 1) { LOGE("unsupported libretro API %u", g.api_version()); dlclose(g.handle); g.handle = nullptr; return JNI_FALSE; }
    g_shutdown = false;
    g_pixelFormat = 1;
    g.set_environment(env_cb);
    g.init();
    g.set_video_refresh(video_cb);
    g.set_audio_sample(audio_sample_cb);
    g.set_audio_sample_batch(audio_batch_cb);
    g.set_input_poll(input_poll_cb);
    g.set_input_state(input_state_cb);
    g_loaded = true;
    retro_system_info si{};
    g.get_system_info(&si);
    LOGI("core: %s %s", si.library_name ? si.library_name : "?", si.library_version ? si.library_version : "?");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_evolutionofgames_zgamespsx_emulator_NativeCore_nativeLoadGame(JNIEnv* env, jobject, jstring path) {
    if (!g_loaded) return JNI_FALSE;
    std::string p = jstr(env, path);
    retro_system_info si{};
    g.get_system_info(&si);
    retro_game_info gi{};
    gi.path = p.c_str();
    gi.data = nullptr; gi.size = 0; gi.meta = nullptr;
    if (!si.need_fullpath) {
        FILE* f = fopen(p.c_str(), "rb");
        if (!f) { LOGE("cannot open %s", p.c_str()); return JNI_FALSE; }
        fseek(f, 0, SEEK_END); long len = ftell(f); fseek(f, 0, SEEK_SET);
        if (len <= 0) { fclose(f); return JNI_FALSE; }
        g_romData.resize((size_t)len);
        size_t rd = fread(g_romData.data(), 1, (size_t)len, f);
        fclose(f);
        if (rd != (size_t)len) return JNI_FALSE;
        gi.data = g_romData.data(); gi.size = g_romData.size();
    }
    if (!g.load_game(&gi)) { LOGE("retro_load_game failed"); return JNI_FALSE; }
    g.set_controller_port_device(0, g_analogEnabled ? RETRO_DEVICE_ANALOG : RETRO_DEVICE_JOYPAD);  // DualShock (analog) or digital pad
    retro_system_av_info av{};
    g.get_system_av_info(&av);
    if (av.timing.fps > 1.0) g_fps = av.timing.fps;
    if (av.timing.sample_rate > 8000.0) g_sampleRate = av.timing.sample_rate;
    g_gameLoaded = true;
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_evolutionofgames_zgamespsx_emulator_NativeCore_nativeSampleRate(JNIEnv*, jobject) { return (jint)g_sampleRate; }

JNIEXPORT jdouble JNICALL
Java_com_evolutionofgames_zgamespsx_emulator_NativeCore_nativeFps(JNIEnv*, jobject) { return (jdouble)g_fps; }

// Save states run on the emulation thread (same thread as retro_run).
JNIEXPORT jboolean JNICALL
Java_com_evolutionofgames_zgamespsx_emulator_NativeCore_nativeSaveState(JNIEnv* env, jobject, jstring path) {
    if (!g_loaded || !g_gameLoaded || !g.serialize || !g.serialize_size) return JNI_FALSE;
    size_t size = g.serialize_size();
    if (size == 0) return JNI_FALSE;
    std::vector<uint8_t> buf(size);
    if (!g.serialize(buf.data(), size)) return JNI_FALSE;
    std::string p = jstr(env, path);
    FILE* f = fopen(p.c_str(), "wb");
    if (!f) return JNI_FALSE;
    size_t w = fwrite(buf.data(), 1, size, f);
    fclose(f);
    return w == size ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_evolutionofgames_zgamespsx_emulator_NativeCore_nativeLoadState(JNIEnv* env, jobject, jstring path) {
    if (!g_loaded || !g_gameLoaded || !g.unserialize) return JNI_FALSE;
    std::string p = jstr(env, path);
    FILE* f = fopen(p.c_str(), "rb");
    if (!f) return JNI_FALSE;
    fseek(f, 0, SEEK_END); long len = ftell(f); fseek(f, 0, SEEK_SET);
    if (len <= 0) { fclose(f); return JNI_FALSE; }
    std::vector<uint8_t> buf((size_t)len);
    size_t rd = fread(buf.data(), 1, (size_t)len, f);
    fclose(f);
    if (rd != (size_t)len) return JNI_FALSE;
    return g.unserialize(buf.data(), buf.size()) ? JNI_TRUE : JNI_FALSE;
}

// Analog stick: stick 0 = left, 1 = right; x/y in -32767..32767 (up = negative y).
JNIEXPORT void JNICALL
Java_com_evolutionofgames_zgamespsx_emulator_NativeCore_nativeSetAnalog(JNIEnv*, jobject, jint stick, jint x, jint y) {
    if (stick == 0) { g_analogLX = x; g_analogLY = y; }
    else { g_analogRX = x; g_analogRY = y; }
}

JNIEXPORT void JNICALL
Java_com_evolutionofgames_zgamespsx_emulator_NativeCore_nativeSetSurface(JNIEnv* env, jobject, jobject surface) {
    std::lock_guard<std::mutex> lk(g_winMutex);
    if (g_win) { ANativeWindow_release(g_win); g_win = nullptr; }
    g_lastW = g_lastH = g_lastFmt = 0;
    if (surface) g_win = ANativeWindow_fromSurface(env, surface);
}

// Runs one emulated frame. Returns the number of audio samples (L+R interleaved) copied into
// [audio], -1 when nothing is loaded, -2 when the core asked to shut down.
JNIEXPORT jint JNICALL
Java_com_evolutionofgames_zgamespsx_emulator_NativeCore_nativeRunFrame(JNIEnv* env, jobject, jshortArray audio) {
    if (!g_loaded || !g_gameLoaded) return -1;
    g_audio.clear();
    g.run();
    if (g_shutdown) return -2;
    jsize cap = env->GetArrayLength(audio);
    jsize n = (jsize)std::min<size_t>(g_audio.size(), (size_t)cap);
    if (n > 0) env->SetShortArrayRegion(audio, 0, n, g_audio.data());
    return n;
}

JNIEXPORT void JNICALL
Java_com_evolutionofgames_zgamespsx_emulator_NativeCore_nativeSetInput(JNIEnv*, jobject, jint mask) { g_inputMask = mask; }

/** Analog sticks on/off. Applies at once when a game is running, otherwise at the next load. */
JNIEXPORT void JNICALL
Java_com_evolutionofgames_zgamespsx_emulator_NativeCore_nativeSetAnalogEnabled(JNIEnv*, jobject, jboolean enabled) {
    g_analogEnabled = enabled == JNI_TRUE;
    if (g_loaded && g_gameLoaded && g.set_controller_port_device) {
        g.set_controller_port_device(0, g_analogEnabled ? RETRO_DEVICE_ANALOG : RETRO_DEVICE_JOYPAD);
    }
}

JNIEXPORT void JNICALL
Java_com_evolutionofgames_zgamespsx_emulator_NativeCore_nativeUnload(JNIEnv*, jobject) {
    if (g_gameLoaded) { g.unload_game(); g_gameLoaded = false; }
    if (g_loaded) { g.deinit(); g_loaded = false; }
    std::lock_guard<std::mutex> lk(g_winMutex);
    if (g_win) { ANativeWindow_release(g_win); g_win = nullptr; }
}

}  // extern "C"
