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
# P2-1d 吞吐修复（2026-10-23）：GGML_NATIVE=OFF 之后不再按本机 CPU 自动挑指令集，于是 Q4_K 的点积
# 可能退回通用实现——真机实测提示读取只有 16-33 token/秒，比同类硬件低一个数量级。
# 目标机（SM8850 / armv9.2）确定支持 dotprod + i8mm + fp16，这里显式声明，让 ggml 编出对应内核。
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)
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
add_library(mycelium_llm SHARED engine.cpp
    "${LLAMA_SOURCE}/common/jinja/lexer.cpp"
    "${LLAMA_SOURCE}/common/jinja/parser.cpp"
    "${LLAMA_SOURCE}/common/jinja/runtime.cpp"
    "${LLAMA_SOURCE}/common/jinja/value.cpp"
    "${LLAMA_SOURCE}/common/jinja/string.cpp"
    "${LLAMA_SOURCE}/common/unicode.cpp"
    "${LLAMA_SOURCE}/common/json.cpp")
# llama.cpp's headers are third-party (pinned, never patched): mark them SYSTEM so their own
# warnings (for example unused functions in jinja headers) stay exempt while our file does not.
target_include_directories(mycelium_llm SYSTEM PRIVATE "${LLAMA_SOURCE}/common" "${LLAMA_SOURCE}/vendor")
target_compile_options(mycelium_llm PRIVATE -O2 -fexceptions)
# Our own JNI file must be warning-free. The strict flags go on engine.cpp only: the same target
# also compiles llama.cpp's jinja/json sources, which keep their upstream flags (see llm/README.md).
set_source_files_properties(engine.cpp PROPERTIES COMPILE_OPTIONS "-Wall;-Wextra;-Werror")
target_link_libraries(mycelium_llm PRIVATE llama)

