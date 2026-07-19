# CMake for Cupcake Comics native image helpers
cmake_minimum_required(VERSION 3.22.1)
project(cupcake_image)

add_library(cupcake_image SHARED
    cupcake_image.cpp
    lanczos.cpp
    color_correct.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(cupcake_image
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)

target_compile_options(cupcake_image PRIVATE -O2 -ffast-math)

# NDK r27 does not enable flexible page sizes by default. Android 15+ devices
# may use 16 KiB pages, so every PT_LOAD segment must be 16 KiB aligned.
target_link_options(cupcake_image PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
