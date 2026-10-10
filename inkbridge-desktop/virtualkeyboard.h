#ifndef VIRTUALKEYBOARD_H
#define VIRTUALKEYBOARD_H

class VirtualKeyboard {
public:
    VirtualKeyboard();
    ~VirtualKeyboard();
    VirtualKeyboard(const VirtualKeyboard &) = delete;
    VirtualKeyboard &operator=(const VirtualKeyboard &) = delete;
    void undo();

private:
    int m_fd = -1;
};

#endif
