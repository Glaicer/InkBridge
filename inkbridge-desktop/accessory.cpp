#include "accessory.h"
#include "linux-adk.h"
#include "protocol.h"
#include "virtualstylus.h"
#include "backend.h"
#include "usbvideochannel.h"
#include <thread>
#include <cstring>
#include <algorithm>

#include <iostream>
#include <vector>
#include <atomic>
#include <libusb-1.0/libusb.h>
#include <QDebug>

using namespace std;

namespace InkBridge {
    volatile std::atomic<bool> stop_acc(false);
}

#define AOA_ACCESSORY_INTERFACE 0

static void writeVideoPackets(libusb_device_handle *handle, unsigned char endpoint, UsbVideoChannel *channel) {
    while (!InkBridge::stop_acc && channel->connected()) {
        QByteArray packet = channel->takePacket(100);
        int offset = 0;
        int timeouts = 0;
        while (offset < packet.size() && !InkBridge::stop_acc && channel->connected()) {
            int transferred = 0;
            const int count = std::min(16384, int(packet.size()) - offset);
            const int result = libusb_bulk_transfer(handle, endpoint,
                reinterpret_cast<unsigned char *>(packet.data() + offset), count, &transferred, 200);
            offset += transferred;
            if (transferred > 0) timeouts = 0;
            else if (result == LIBUSB_ERROR_TIMEOUT) ++timeouts;
            if ((result < 0 && result != LIBUSB_ERROR_TIMEOUT) || timeouts >= 5 || (result == 0 && transferred == 0)) {
                channel->setConnected(false);
                InkBridge::stop_acc = true;
                return;
            }
        }
        channel->packetWritten();
    }
}

// ----------------------------------------------------------------------------
// Helper: Extract Data
// ----------------------------------------------------------------------------
static void fillEventData(AccessoryEventData& data, const PenPacket* packet) {
    data.toolType = packet->toolType;
    data.action = packet->action;
    data.x = packet->x;
    data.y = packet->y;
    // Pressure is encoded as (event.pressure * 4096) on Android.
    // Dividing by 4096.0f restores the original 0.0-1.0 float range.
    // The previous value of 1000.0f was mismatched, causing values above
    // 1.0 that PressureTranslator would clamp, squashing most of the
    // pressure range to maximum.
    data.pressure = static_cast<float>(packet->pressure) / 4096.0f;
    
    // --- NEW: Copy Tilt Data ---
    data.tiltX = packet->tiltX;
    data.tiltY = packet->tiltY;
}

// ----------------------------------------------------------------------------
// Main Capture Loop
// ----------------------------------------------------------------------------
void accessory_main(InkBridge::UsbConnection* conn, VirtualStylus* virtualStylus, UsbVideoChannel* videoChannel)
{
    if (!conn || !virtualStylus) return;

    int ret = 0;
    int transferred = 0;
    unsigned char acc_buf[512]; 

    // Tracker variables to filter out redundant coordinate data
    int lastAction = -1;
    int lastTool = -1;

    unsigned char inputEndpoint = 0;
    unsigned char outputEndpoint = 0;
    libusb_config_descriptor *config = nullptr;
    if (libusb_get_active_config_descriptor(libusb_get_device(conn->getHandle()), &config) != 0) return;
    for (int i = 0; i < config->bNumInterfaces; ++i) {
        for (int j = 0; j < config->interface[i].num_altsetting; ++j) {
            const auto &setting = config->interface[i].altsetting[j];
            if (setting.bInterfaceNumber != AOA_ACCESSORY_INTERFACE || setting.bAlternateSetting != 0) continue;
            for (int k = 0; k < setting.bNumEndpoints; ++k) {
                const auto &endpoint = setting.endpoint[k];
                if ((endpoint.bmAttributes & LIBUSB_TRANSFER_TYPE_MASK) != LIBUSB_TRANSFER_TYPE_BULK) continue;
                if (endpoint.bEndpointAddress & LIBUSB_ENDPOINT_IN) inputEndpoint = endpoint.bEndpointAddress;
                else outputEndpoint = endpoint.bEndpointAddress;
            }
        }
    }
    libusb_free_config_descriptor(config);
    if (inputEndpoint == 0) return;

    ret = libusb_claim_interface(conn->getHandle(), AOA_ACCESSORY_INTERFACE);
    if (ret != 0) {
        cerr << "Error claiming interface: " << libusb_error_name(ret) << endl;
        return;
    }

    cout << "Accessory interface claimed. Starting capture loop..." << endl;
    AccessoryEventData eventData;
    videoChannel->setConnected(outputEndpoint != 0);
    std::thread videoWriter;
    if (outputEndpoint != 0) {
        videoWriter = std::thread(writeVideoPackets, conn->getHandle(), outputEndpoint, videoChannel);
    }
    QByteArray pending;

    while (!InkBridge::stop_acc) {
        ret = libusb_bulk_transfer(conn->getHandle(),
                                   inputEndpoint,
                                   acc_buf,
                                   sizeof(acc_buf),
                                   &transferred,
                                   200);

        if (ret < 0 && ret != LIBUSB_ERROR_TIMEOUT) {
            cerr << "Bulk transfer error: " << libusb_error_name(ret) << endl;
            break;
        }
        if (transferred == 0) continue;
        pending.append(reinterpret_cast<const char *>(acc_buf), transferred);
        int processed = 0;
        while (processed + int(sizeof(PenPacket)) <= pending.size()) {
            PenPacket packet;
            std::memcpy(&packet, pending.constData() + processed, sizeof(packet));
            processed += sizeof(PenPacket);
            if (videoChannel->acceptControl(packet)) continue;
            fillEventData(eventData, &packet);

            // --- THE UPDATED DEBUGGER: STATE CHANGE ONLY ---
            if (Backend::isDebugMode) {
                // Only print if Action or ToolType changes (ignores coordinate/pressure jitter)
                if (eventData.action != lastAction || eventData.toolType != lastTool) {
                    qDebug() << "--- STATE CHANGE ---"
                             << "Action:" << eventData.action 
                             << "Tool:" << eventData.toolType;
                    
                    lastAction = eventData.action;
                    lastTool = eventData.toolType;
                }
            }
            // -----------------------------------------------

            virtualStylus->handleAccessoryEventData(&eventData);
        }
        pending.remove(0, processed);
    }
    videoChannel->setConnected(false);
    if (videoWriter.joinable()) videoWriter.join();
    cout << "Capture loop finished." << endl;
}

// Legacy parser (unchanged, strictly for compilation)
bool parseAccessoryEventDataLine(const string &line, AccessoryEventData * accessoryEventData) {
    return false; // Disabled for now
}