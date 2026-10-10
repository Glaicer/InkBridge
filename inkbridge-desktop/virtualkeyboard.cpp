#include "virtualkeyboard.h"
#include "uinput.h"
#include <QDebug>
#include <linux/input.h>

VirtualKeyboard::VirtualKeyboard() {
    Error error{};
    m_fd = init_uinput_keyboard("InkBridge Shortcuts", &error);
    if (error.code) qWarning() << "Cannot initialize InkBridge shortcuts:" << error.error_str;
}

VirtualKeyboard::~VirtualKeyboard() {
    if (m_fd >= 0) destroy_uinput_device(m_fd);
}

void VirtualKeyboard::undo() {
    if (m_fd < 0) return;
    Error error{};
    send_uinput_event(m_fd, EV_KEY, KEY_LEFTCTRL, 1, &error);
    send_uinput_event(m_fd, EV_KEY, KEY_Z, 1, &error);
    send_uinput_event(m_fd, EV_SYN, SYN_REPORT, 0, &error);
    send_uinput_event(m_fd, EV_KEY, KEY_Z, 0, &error);
    send_uinput_event(m_fd, EV_KEY, KEY_LEFTCTRL, 0, &error);
    send_uinput_event(m_fd, EV_SYN, SYN_REPORT, 0, &error);
    if (error.code) qWarning() << "Cannot send InkBridge Undo shortcut:" << error.error_str;
}
