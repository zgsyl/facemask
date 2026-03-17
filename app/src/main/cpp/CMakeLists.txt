cmake_minimum_required(VERSION 3.22.1)

project("facemask")

# Define the path to the NCNN precompiled library for the current ABI
set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn)


find_package(ncnn REQUIRED)

add_library(facemask SHARED
            native-lib.cpp)

target_include_directories(facemask PRIVATE ${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/include)


find_package(OpenMP REQUIRED)

find_library(log-lib log)

target_link_libraries(facemask
                      ncnn
                      OpenMP::OpenMP_CXX
                      ${log-lib})
