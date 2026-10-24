cmake_minimum_required(VERSION 3.22.1)
project(mycelium_llm LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(LLAMA_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../../../vendor/llama.cpp")
find_package(Git REQUIRED)
execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${LLAMA_SOURCE}" rev-parse HEAD
    OUTPUT_VARIABLE LLAMA_COMMIT OUTPUT_STRIP_TRAILING_WHITESPACE RESULT_VARIABLE GIT_RESULT)
if(NOT GIT_RESULT EQUAL 0 OR NOT LLAMA_COMMIT STREQUAL "5e03bdd8700948b9c41c54dd1b00f28a2aebc03f")
    message(FATAL_ERROR "Run scripts/prepare-llama.ps1 with a local source repository first")
endif()
execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${LLAMA_SOURCE}" status --porcelain --untracked-files=all
    OUTPUT_VARIABLE LLAMA_DIRTY RESULT_VARIABLE GIT_RESULT)
if(NOT GIT_RESULT EQUAL 0 OR NOT LLAMA_DIRTY STREQUAL "")
    message(FATAL_ERROR "llama.cpp source is modified")
endif()
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# 不用 ccache：没装 ccache 时 ggml 会打印「ccache not found」警告。P2-1d 的快库是单独一次 CMake 构建，
# 它的配置输出不被 AGP 吞掉，CI 门禁（零警告）就判失败了（2026-10-24 run 40100757411）。统筹补改。
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
# P2-1d 运行时指令集挑选（2026-10-23 夜，任务书第一段第 1 条）。
#
# 背景：原先这里写死 `armv8.2-a+dotprod+i8mm+fp16`。带这些指令的内核在**不支持 i8mm 的老手机**上
# 会直接 SIGILL（一加载本地模型就崩）。所以改成编两份、运行时挑：
#   - `mycelium_llm`（主库）：基线 `armv8-a`，任何 arm64 设备都能跑，是**保底**那一份；
#   - `mycelium_llm_fast`：`armv8.2-a+dotprod+i8mm+fp16`，真机实测提示读取快 3 倍。
# 挑选逻辑在 Kotlin 侧（`NativeLibraryVariant`），依据 `/proc/cpuinfo` 的 Features 与 getauxval。
#
# 为什么不是 `GGML_BACKEND_DL` + `GGML_CPU_ALL_VARIANTS`：看过固定版本的
# `ggml/src/ggml-cpu/CMakeLists.txt`——arm64 分支在 ALL_VARIANTS 下仍只按构建期探测出的特性
# **编出一个**变体（`-march=${ARM_MCPU}${ARCH_TAGS}` 只 append 一次），没有多份 .so 可供运行时挑。
# 那条路在 Android arm64 上走不通，故用两库方案。
set(MYCELIUM_ARM_ARCH "armv8-a" CACHE STRING "编译 ggml CPU 内核用的 -march")
set(MYCELIUM_LIB_NAME "mycelium_llm" CACHE STRING "JNI 共享库的名字")
set(GGML_CPU_ARM_ARCH "${MYCELIUM_ARM_ARCH}" CACHE STRING "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_TOOLS_INSTALL OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
add_subdirectory("${LLAMA_SOURCE}" llama)
# Only the Jinja interpreter; llama-common includes HTTP/download code.
add_library(${MYCELIUM_LIB_NAME} SHARED engine.cpp
    "${LLAMA_SOURCE}/common/jinja/lexer.cpp"
    "${LLAMA_SOURCE}/common/jinja/parser.cpp"
    "${LLAMA_SOURCE}/common/jinja/runtime.cpp"
    "${LLAMA_SOURCE}/common/jinja/value.cpp"
    "${LLAMA_SOURCE}/common/jinja/string.cpp"
    "${LLAMA_SOURCE}/common/unicode.cpp"
    "${LLAMA_SOURCE}/common/json.cpp")
# llama.cpp's headers are third-party (pinned, never patched): mark them SYSTEM so their own
# warnings (for example unused functions in jinja headers) stay exempt while our file does not.
target_include_directories(${MYCELIUM_LIB_NAME} SYSTEM PRIVATE "${LLAMA_SOURCE}/common" "${LLAMA_SOURCE}/vendor")
target_compile_options(${MYCELIUM_LIB_NAME} PRIVATE -O2 -fexceptions)
# Our own JNI file must be warning-free. The strict flags go on engine.cpp only: the same target
# also compiles llama.cpp's jinja/json sources, which keep their upstream flags (see llm/README.md).
set_source_files_properties(engine.cpp PROPERTIES COMPILE_OPTIONS "-Wall;-Wextra;-Werror")
target_link_libraries(${MYCELIUM_LIB_NAME} PRIVATE llama)
# 变体标识：Kotlin 侧读 `nativeArchVariant()` 就能知道当前加载的是哪一份（真机验收要靠它）。
target_compile_definitions(${MYCELIUM_LIB_NAME} PRIVATE MYCELIUM_ARCH_VARIANT="${MYCELIUM_ARM_ARCH}")

