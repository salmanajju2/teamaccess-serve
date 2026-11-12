cmake_minimum_required(VERSION 3.22.1)

project("teamaccess_native")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Include directories
include_directories(src/main/cpp)

# Source files
add_library(
    teamaccess_native
    SHARED
    src/main/cpp/native_bridge.cpp
    src/main/cpp/input_processor.cpp
    src/main/cpp/video_streamer.cpp
)

# Find Android system libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

# Link libraries
target_link_libraries(
    teamaccess_native
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)
