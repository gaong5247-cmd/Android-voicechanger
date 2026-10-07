#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build
c++ -std=c++17 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined -pthread -Iinclude tests/core_test.cpp -o build/core_test
./build/core_test
