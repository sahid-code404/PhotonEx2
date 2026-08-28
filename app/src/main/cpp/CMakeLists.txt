cmake_minimum_required(VERSION 3.22.1)
project(photonex2_cameraids LANGUAGES CXX)

add_library(photonex2_cameraids SHARED ndk_camera_ids.cpp)
target_compile_features(photonex2_cameraids PRIVATE cxx_std_20)
target_compile_options(photonex2_cameraids PRIVATE -Wall -Wextra -Werror)
target_link_libraries(photonex2_cameraids PRIVATE dl log)
