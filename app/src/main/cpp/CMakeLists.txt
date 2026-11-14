cmake_minimum_required(VERSION 3.22.1)
project("cactus_jni" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CACTUS_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include")
set(CACTUS_PREBUILT_LIB "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libcactus.a")

find_library(log-lib log)
find_library(android-lib android)

if(EXISTS "${CACTUS_PREBUILT_LIB}")
    message(STATUS "Found prebuilt Cactus engine for ${ANDROID_ABI}: ${CACTUS_PREBUILT_LIB}")
    add_library(cactus_engine STATIC IMPORTED)
    set_target_properties(cactus_engine PROPERTIES
        IMPORTED_LOCATION "${CACTUS_PREBUILT_LIB}"
    )

    add_library(cactus_jni SHARED
        cactus_jni.cpp
    )

    target_compile_definitions(cactus_jni PRIVATE CACTUS_USING_STUB=0)
    target_include_directories(cactus_jni PRIVATE "${CACTUS_INCLUDE_DIR}")
    target_link_libraries(cactus_jni
        cactus_engine
        ${log-lib}
        ${android-lib}
    )
else()
    message(WARNING
        "Prebuilt Cactus engine not found at ${CACTUS_PREBUILT_LIB}. "
        "Run ./scripts/download_cactus.sh to fetch the android-arm64 engine and .cact weights. "
        "Building with fallback cactus_stub.cpp so CMake succeeds."
    )

    add_library(cactus_jni SHARED
        cactus_jni.cpp
        cactus_stub.cpp
    )

    target_compile_definitions(cactus_jni PRIVATE CACTUS_USING_STUB=1)
    target_include_directories(cactus_jni PRIVATE "${CACTUS_INCLUDE_DIR}")
    target_link_libraries(cactus_jni
        ${log-lib}
        ${android-lib}
    )
endif()
