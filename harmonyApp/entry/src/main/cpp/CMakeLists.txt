cmake_minimum_required(VERSION 3.5.0)
project(bucc_entry)
if(DEFINED PACKAGE_FIND_FILE)
    include(${PACKAGE_FIND_FILE})
endif()
set(BUCC_ENTRY_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../..")
list(APPEND CMAKE_PREFIX_PATH "${BUCC_ENTRY_ROOT}/oh_modules/@cpf-kmp-cmp/compose")
find_package(compose REQUIRED CONFIG)
add_library(entry SHARED bucc_host.cpp)
target_link_libraries(entry PUBLIC
    libace_napi.z.so
    libhilog_ndk.z.so
    libdeviceinfo_ndk.z.so
    librawfile.z.so
    "${BUCC_ENTRY_ROOT}/libs/arm64-v8a/libbucc_compose.so"
    compose::skikobridge
    libc++_shared.so
)
