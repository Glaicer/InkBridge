#include "virtualkeyboard.h"
#include "uinput.h"
#include <linux/input.h>
#include <array>
#include <vector>
#include <cstdlib>
#include <iostream>
#include <cstring>

static std::vector<std::array<int, 3>> events;
static int destroyed = -1;
static int keyboardFd = 42;
static bool failWrite = false;

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

extern "C" int init_uinput_keyboard(const char *name, Error *error) {
    require(std::strcmp(name, "InkBridge Shortcuts") == 0, "keyboard has a separate device identity");
    if (keyboardFd < 0) error->code = 1;
    return keyboardFd;
}

extern "C" void destroy_uinput_device(int fd) {
    destroyed = fd;
}

extern "C" void send_uinput_event(int fd, int type, int code, int value, Error *error) {
    require(fd == keyboardFd, "shortcut uses its own keyboard descriptor");
    events.push_back({type, code, value});
    if (failWrite && value == 1) error->code = 1;
}

int main() {
    const std::vector<std::array<int, 3>> expected = {
        {EV_KEY, KEY_LEFTCTRL, 1}, {EV_KEY, KEY_Z, 1}, {EV_SYN, SYN_REPORT, 0},
        {EV_KEY, KEY_Z, 0}, {EV_KEY, KEY_LEFTCTRL, 0}, {EV_SYN, SYN_REPORT, 0}
    };
    {
        VirtualKeyboard keyboard;
        keyboard.undo();
        require(events == expected, "Undo presses Ctrl+Z and releases both keys in sync reports");
        events.clear();
        failWrite = true;
        keyboard.undo();
        require(events == expected, "failed key-down still attempts to release both keys");
    }
    require(destroyed == 42, "keyboard is destroyed on desktop shutdown");
    events.clear();
    keyboardFd = -1;
    destroyed = -1;
    {
        VirtualKeyboard keyboard;
        keyboard.undo();
    }
    require(events.empty() && destroyed == -1, "unavailable uinput does not inject or close an invalid descriptor");
    std::cout << "PASS: separate shortcut keyboard, Ctrl+Z framing, key release and failure cleanup.\n";
}
