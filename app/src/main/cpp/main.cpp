#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <sys/uio.h>
#include <unistd.h>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <limits>
#include <string>
#include <thread>
#include "imgui.h"
#include "backends/imgui_impl_opengl3.h"

namespace Config {
constexpr uintptr_t PLAYER_LIST = 0x0;
constexpr uintptr_t CAMERA = 0x0;
constexpr uintptr_t TRANSFORM_POSITION = 0x0;
constexpr uintptr_t VIEW_MATRIX = 0x0;
// Optional validated absolute address for a four-byte diagnostic read.
constexpr uintptr_t READ_TEST_ADDRESS = 0x0;
}

namespace {
std::atomic_bool stopping{false};
struct ReadResult {
    size_t transferred = 0;
    int error = 0;
    bool complete = false;
};

ReadResult ReadMemory(pid_t pid, uintptr_t address, void* output, size_t size) {
    ReadResult result;
    if (pid <= 0 || !output || !size || !address ||
        size > std::numeric_limits<uintptr_t>::max() - address) {
        result.error = EINVAL;
        return result;
    }
    auto* destination = static_cast<unsigned char*>(output);
    std::memset(destination, 0, size);
    while (result.transferred < size) {
        const size_t remaining = size - result.transferred;
        iovec local{destination + result.transferred, remaining};
        iovec remote{reinterpret_cast<void*>(address + result.transferred), remaining};
        const ssize_t count = process_vm_readv(pid, &local, 1, &remote, 1, 0);
        if (count < 0) {
            if (errno == EINTR) continue;
            result.error = errno;
            return result;
        }
        if (count == 0) { result.error = EIO; return result; }
        result.transferred += static_cast<size_t>(count);
    }
    result.complete = true;
    return result;
}

std::string ProcessName(pid_t pid) {
    std::ifstream file("/proc/" + std::to_string(pid) + "/cmdline", std::ios::binary);
    std::string name;
    std::getline(file, name, '\0');
    return name.empty() ? "<unavailable>" : name;
}

class Renderer {
public:
    ANativeWindow* window = nullptr;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    ImGuiContext* imgui = nullptr;
    bool backend = false;

    bool initialize(JNIEnv* env, jobject javaSurface) {
        window = ANativeWindow_fromSurface(env, javaSurface);
        if (!window) return false;
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr) ||
            !eglBindAPI(EGL_OPENGL_ES_API)) return false;
        const EGLint attributes[]{
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 0, EGL_STENCIL_SIZE, 0, EGL_NONE
        };
        EGLConfig config{};
        EGLint count = 0, format = 0;
        if (!eglChooseConfig(display, attributes, &config, 1, &count) || count != 1 ||
            !eglGetConfigAttrib(display, config, EGL_NATIVE_VISUAL_ID, &format))
            return false;
        if (ANativeWindow_setBuffersGeometry(window, 0, 0, format) != 0) return false;
        const EGLint contextAttributes[]{EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
        if (context == EGL_NO_CONTEXT) return false;
        surface = eglCreateWindowSurface(display, config, window, nullptr);
        if (surface == EGL_NO_SURFACE ||
            !eglMakeCurrent(display, surface, surface, context)) return false;
        eglSwapInterval(display, 1);
        IMGUI_CHECKVERSION();
        imgui = ImGui::CreateContext();
        ImGui::SetCurrentContext(imgui);
        ImGui::GetIO().IniFilename = nullptr;
        ImGui::GetIO().LogFilename = nullptr;
        ImGui::StyleColorsDark();
        backend = ImGui_ImplOpenGL3_Init("#version 300 es");
        return backend;
    }

    ~Renderer() {
        if (imgui) {
            ImGui::SetCurrentContext(imgui);
            if (backend) ImGui_ImplOpenGL3_Shutdown();
            ImGui::DestroyContext(imgui);
        }
        if (display != EGL_NO_DISPLAY) {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
            eglTerminate(display);
        }
        if (window) ANativeWindow_release(window);
    }
};

void Draw(pid_t pid, const std::string& process, const ReadResult& selfTest,
          const ReadResult& targetRead, uint32_t value, int width, int height) {
    ImGui::SetNextWindowPos(ImVec2(16, 48), ImGuiCond_Always);
    ImGui::SetNextWindowSize(ImVec2(float(width > 560 ? 520 : width - 32), 0),
                             ImGuiCond_Always);
    ImGui::Begin("OverlayMod", nullptr,
        ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoMove |
        ImGuiWindowFlags_AlwaysAutoResize | ImGuiWindowFlags_NoCollapse |
        ImGuiWindowFlags_NoInputs);
    ImGui::Text("UID: %u | PID: %d", unsigned(getuid()), int(pid));
    ImGui::TextWrapped("Process: %s", process.c_str());
    ImGui::Text("Surface: %d x %d", width, height);
    ImGui::Text("process_vm_readv self-test: %s", selfTest.complete ? "passed" : "failed");
    if (!selfTest.complete)
        ImGui::TextWrapped("Self-test error: %s", std::strerror(selfTest.error));
    ImGui::Separator();
    ImGui::TextUnformatted("Target offsets: unconfigured");
    ImGui::Text("PLAYER_LIST: 0x%llx", (unsigned long long)Config::PLAYER_LIST);
    ImGui::Text("CAMERA: 0x%llx", (unsigned long long)Config::CAMERA);
    ImGui::Text("TRANSFORM_POSITION: 0x%llx",
                (unsigned long long)Config::TRANSFORM_POSITION);
    ImGui::Text("VIEW_MATRIX: 0x%llx", (unsigned long long)Config::VIEW_MATRIX);
    if constexpr (Config::READ_TEST_ADDRESS != 0) {
        if (targetRead.complete) ImGui::Text("Read value: 0x%08x", value);
        else ImGui::TextWrapped("Target read failed: %s (%zu bytes)",
            std::strerror(targetRead.error), targetRead.transferred);
    } else {
        ImGui::TextWrapped("Target reading disabled: READ_TEST_ADDRESS is zero.");
    }
    ImGui::Separator();
    ImGui::TextWrapped("ESP requires verified target layouts and player data.");
    ImGui::TextWrapped("Display-only layer. Stop it from the app.");
    ImGui::End();
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_mod_overlay_RootOverlay_nativeStop(JNIEnv*, jclass) {
    stopping.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mod_overlay_RootOverlay_nativeRun(
    JNIEnv* env, jclass, jobject javaSurface, jint requestedWidth,
    jint requestedHeight, jint targetPid) {
    if (requestedWidth <= 0 || requestedHeight <= 0 || targetPid <= 0 ||
        getuid() != 0) return;
    Renderer renderer;
    if (!renderer.initialize(env, javaSurface)) {
        std::fprintf(stderr, "EGL/ImGui initialization failed: 0x%x\n", eglGetError());
        return;
    }
    const uint32_t sentinel = 0x51A7C0DE;
    uint32_t copied = 0;
    ReadResult selfTest = ReadMemory(
        getpid(), reinterpret_cast<uintptr_t>(&sentinel), &copied, sizeof(copied));
    if (selfTest.complete && copied != sentinel) {
        selfTest.complete = false;
        selfTest.error = EIO;
    }
    std::string process = ProcessName(targetPid);
    ReadResult targetRead{};
    uint32_t targetValue = 0;
    auto previous = std::chrono::steady_clock::now();
    auto nextRead = previous, nextName = previous;
    while (!stopping.load(std::memory_order_acquire)) {
        EGLint width = 0, height = 0;
        if (!eglQuerySurface(renderer.display, renderer.surface, EGL_WIDTH, &width) ||
            !eglQuerySurface(renderer.display, renderer.surface, EGL_HEIGHT, &height) ||
            width <= 0 || height <= 0) break;
        const auto now = std::chrono::steady_clock::now();
        float delta = std::chrono::duration<float>(now - previous).count();
        previous = now;
        if (now >= nextRead) {
            if constexpr (Config::READ_TEST_ADDRESS != 0)
                targetRead = ReadMemory(targetPid, Config::READ_TEST_ADDRESS,
                                        &targetValue, sizeof(targetValue));
            nextRead = now + std::chrono::milliseconds(250);
        }
        if (now >= nextName) {
            process = ProcessName(targetPid);
            nextName = now + std::chrono::seconds(1);
        }
        auto& io = ImGui::GetIO();
        io.DisplaySize = ImVec2(float(width), float(height));
        io.DeltaTime = delta > 0.001f && delta < 0.2f ? delta : 1.0f / 60;
        ImGui_ImplOpenGL3_NewFrame();
        ImGui::NewFrame();
        Draw(targetPid, process, selfTest, targetRead, targetValue, width, height);
        ImGui::Render();
        glViewport(0, 0, width, height);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);
        ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
        if (!eglSwapBuffers(renderer.display, renderer.surface)) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(8));
    }
}
