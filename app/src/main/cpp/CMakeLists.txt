cmake_minimum_required(VERSION 3.22.1)
project(OverlayMod LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Android ARM64 toolchain required")
endif()
include(FetchContent)
FetchContent_Declare(imgui
    URL https://github.com/ocornut/imgui/archive/refs/tags/v1.91.8.tar.gz)
FetchContent_MakeAvailable(imgui)
add_library(overlay SHARED
    main.cpp
    "${imgui_SOURCE_DIR}/imgui.cpp"
    "${imgui_SOURCE_DIR}/imgui_draw.cpp"
    "${imgui_SOURCE_DIR}/imgui_tables.cpp"
    "${imgui_SOURCE_DIR}/imgui_widgets.cpp"
    "${imgui_SOURCE_DIR}/backends/imgui_impl_opengl3.cpp")
target_include_directories(overlay PRIVATE "${imgui_SOURCE_DIR}")
target_compile_options(overlay PRIVATE -Wall -Wextra)
target_link_libraries(overlay PRIVATE android EGL GLESv3 log)
target_link_options(overlay PRIVATE "-Wl,-z,max-page-size=16384")
