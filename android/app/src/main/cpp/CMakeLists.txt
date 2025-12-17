cmake_minimum_required(VERSION 3.30)
project(super3_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../" ABSOLUTE)

# --- Pre-generated Musashi opcode tables ---
# The code generator (m68kmake) is a host tool; to avoid requiring a full
# Windows toolchain during Gradle builds we check in its outputs under
# android/app/src/main/cpp/generated.
set(M68K_GENERATED_DIR "${CMAKE_CURRENT_LIST_DIR}/generated")
set(M68K_GENERATED_SOURCES
  "${M68K_GENERATED_DIR}/m68kops.c"
  "${M68K_GENERATED_DIR}/m68kopac.c"
  "${M68K_GENERATED_DIR}/m68kopdm.c"
  "${M68K_GENERATED_DIR}/m68kopnz.c"
)
set(M68K_GENERATED_HEADER "${M68K_GENERATED_DIR}/m68kops.h")

foreach(gen_file IN LISTS M68K_GENERATED_SOURCES M68K_GENERATED_HEADER)
  if(NOT EXISTS "${gen_file}")
    message(FATAL_ERROR "Missing ${gen_file}. Run m68kmake <outdir> Src/CPU/68K/Musashi/m68k_in.c to regenerate.")
  endif()
endforeach()

# --- Main Android Library ---

file(GLOB_RECURSE SUPER3_SOURCES
  "${REPO_ROOT}/Src/*.cpp"
  "${REPO_ROOT}/Src/*.c"
)

# Normalize paths to forward slashes so regex filtering works on Windows.
set(SUPER3_SOURCES_NORM "")
foreach(src ${SUPER3_SOURCES})
  string(REPLACE "\\" "/" src_norm "${src}")
  list(APPEND SUPER3_SOURCES_NORM "${src_norm}")
endforeach()
set(SUPER3_SOURCES "${SUPER3_SOURCES_NORM}")

# Exclude desktop entry points, code generators, and tools that should not be
# built for Android.
set(EXCLUDE_REGEX
  "/Src/OSD/SDL/Main\\.cpp$|"
  "/Src/OSD/SDL/SDLMain_tmpl\\.m$|"
  "/Src/CPU/68K/Musashi/m68k_in\\.c$|"
  "/Src/CPU/68K/Musashi/m68kmake\\.c$|"
  "/Src/CPU/68K/Turbo68K/Make68K\\.c$|"
  "/Src/CPU/PowerPC/ppc603\\.c$|"
  "/Src/CPU/PowerPC/ppc_ops\\.c$|"
  "/Src/CPU/PowerPC/PPCDisasm\\.cpp$|"
  "/Src/Model3/53C810Disasm\\.cpp$|"
  "/Src/OSD/.*\\.(c|cpp)$|"
  "/Src/Graphics/.*\\.(c|cpp)$|"
  "/Src/Network/.*\\.(c|cpp)$|"
  "/Src/Sound/SCSPLFO\\.cpp$|"
  "/Src/Pkgs/GL/.*\\.(c|cpp|h)$|"
  "/Src/Pkgs/glew\\.c$|"
  "/Src/Util/Test_.*\\.(c|cpp)$"
)
list(FILTER SUPER3_SOURCES EXCLUDE REGEX "${EXCLUDE_REGEX}")

# On Windows paths may contain backslashes, so explicitly drop any stragglers in
# excluded directories in case the regex misses them.
set(TO_REMOVE "")
foreach(src ${SUPER3_SOURCES})
  if(src MATCHES "/Src/CPU/68K/Musashi/m68k_in\\.c$"
     OR src MATCHES "/Src/CPU/68K/Musashi/m68kmake\\.c$"
     OR src MATCHES "/Src/CPU/68K/Turbo68K/Make68K\\.c$"
     OR src MATCHES "/Src/CPU/PowerPC/ppc603\\.c$"
     OR src MATCHES "/Src/CPU/PowerPC/ppc_ops\\.c$"
     OR src MATCHES "/Src/CPU/PowerPC/PPCDisasm\\.cpp$"
     OR src MATCHES "/Src/Model3/53C810Disasm\\.cpp$"
     OR src MATCHES "/Src/OSD/"
     OR src MATCHES "/Src/Graphics/"
     OR src MATCHES "/Src/Network/"
     OR src MATCHES "/Src/Sound/SCSPLFO\\.cpp$"
     OR src MATCHES "/Src/Util/Test_"
     OR src MATCHES "/Src/Pkgs/GL/"
     OR src MATCHES "/Src/Pkgs/glew\\.c$"
     )
    list(APPEND TO_REMOVE "${src}")
  endif()
endforeach()
if(TO_REMOVE)
  list(REMOVE_ITEM SUPER3_SOURCES ${TO_REMOVE})
endif()

# --- SDL2 (for future Android OSD wiring) ---
# Prefer a local checkout if provided via -DSDL2_LOCAL_DIR, otherwise fetch the
# latest release tarball.
option(SUPER3_FETCH_SDL2 "Download and build SDL2 for Android" ON)
set(SDL2_VERSION "2.32.10")

if(DEFINED SDL2_LOCAL_DIR)
  message(STATUS "Using local SDL2 at ${SDL2_LOCAL_DIR}")
  add_subdirectory("${SDL2_LOCAL_DIR}" "${CMAKE_BINARY_DIR}/sdl2-local")
elseif(SUPER3_FETCH_SDL2)
  FetchContent_Declare(
    SDL2
    URL "https://github.com/libsdl-org/SDL/archive/refs/tags/release-${SDL2_VERSION}.zip"
  )
  FetchContent_MakeAvailable(SDL2)
endif()

add_library(super3 SHARED
  native-lib.cpp
  android_shim.cpp
  model3_stubs.cpp
  android_input_system.cpp
  gles_presenter.cpp
  gles_stub_render3d.cpp
  render2d_android.cpp
  "${REPO_ROOT}/Src/Graphics/New3D/Mat4.cpp"
  "${REPO_ROOT}/Src/Graphics/New3D/Model.cpp"
  "${REPO_ROOT}/Src/Graphics/New3D/New3D.cpp"
  "${REPO_ROOT}/Src/Graphics/New3D/PolyHeader.cpp"
  "${REPO_ROOT}/Src/Graphics/New3D/R3DFloat.cpp"
  "${REPO_ROOT}/Src/Graphics/New3D/R3DShader.cpp"
  "${REPO_ROOT}/Src/Graphics/New3D/Texture.cpp"
  "${REPO_ROOT}/Src/Graphics/New3D/TextureSheet.cpp"
  "${REPO_ROOT}/Src/Graphics/New3D/VBO.cpp"
  "${REPO_ROOT}/Src/Graphics/New3D/Vec.cpp"
  ${SUPER3_SOURCES}
  ${M68K_GENERATED_SOURCES}
)

target_compile_definitions(super3 PRIVATE ANDROID __ANDROID__)

target_include_directories(super3 PRIVATE
  "${REPO_ROOT}/Src"
  "${REPO_ROOT}/Src/CPU/68K/Musashi"
  "${REPO_ROOT}/Src/OSD/SDL" # Provides Types.h until an Android OSD is added
  "${M68K_GENERATED_DIR}"
)

find_library(log-lib log)
find_library(android-lib android)
find_library(z-lib z)
find_library(egl-lib EGL)
find_library(glesv3-lib GLESv3)

target_link_libraries(super3 PRIVATE
  ${log-lib}
  ${android-lib}
  ${z-lib}
  ${egl-lib}
  ${glesv3-lib}
  SDL2::SDL2 # present when SUPER3_FETCH_SDL2 is ON
)
