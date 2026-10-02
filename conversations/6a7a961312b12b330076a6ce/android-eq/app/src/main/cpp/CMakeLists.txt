cmake_minimum_required(VERSION 3.22.1)
project("neondsp")

# Native NeonDsp engine: preamp -> parametric EQ -> graphic EQ (10/15/31)
# -> bass/treble shelves -> compressor -> stereo stage -> convolver -> limiter.
add_library(neondsp SHARED neondsp.cpp)
target_link_libraries(neondsp android)
