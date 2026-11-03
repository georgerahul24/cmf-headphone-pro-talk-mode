import Foundation

/// Nothing / CMF control protocol (RFCOMM). Only the settings commands that the
/// official Nothing X app also sends are used here. Firmware / OTA services are
/// never touched.
///
/// Frame: 55 60 01 | type lo, type hi | len lo, len hi | seq | payload | crc lo, crc hi
/// CRC is CRC-16/ARC style (poly 0xA001, init 0xFFFF) over everything before it.
enum Proto {
    static let serviceUUID: [UInt8] = [
        0xae, 0xac, 0x4a, 0x03, 0xdf, 0xf5, 0x49, 0x8f,
        0x84, 0x3a, 0x34, 0x48, 0x7c, 0xf1, 0x33, 0xeb,
    ]

    // Requests
    static let ancGet: UInt16 = 0xC01E
    static let ancSet: UInt16 = 0xF00F
    static let spatialGet: UInt16 = 0xC04F
    static let spatialSet: UInt16 = 0xF052
    static let batteryGet: UInt16 = 0xC007

    // Replies / notifications
    static let ancNotify: UInt16 = 0xE003
    static let ancReply: UInt16 = 0x401E
    static let spatialNotify: UInt16 = 0x404F
    static let batteryNotify: UInt16 = 0xE001
    static let batteryReply: UInt16 = 0x4007

    // Noise control modes (CMF Headphone Pro, model B175)
    static let ancOff: UInt8 = 0x05
    static let ancTransparency: UInt8 = 0x07
    static let ancHigh: UInt8 = 0x01
    static let ancMid: UInt8 = 0x02
    static let ancLow: UInt8 = 0x03
    static let ancAdaptive: UInt8 = 0x04
    static let ancLevels: Set<UInt8> = [0x01, 0x02, 0x03, 0x04]

    static let unsolicitedSeq: UInt8 = 0xFF

    static func crc16(_ d: [UInt8]) -> UInt16 {
        var crc: UInt16 = 0xFFFF
        for b in d {
            crc ^= UInt16(b)
            for _ in 0..<8 { crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xA001 : crc >> 1 }
        }
        return crc
    }

    static func encode(type: UInt16, payload: [UInt8], seq: UInt8) -> [UInt8] {
        var p: [UInt8] = [0x55, 0x60, 0x01,
                          UInt8(type & 0xFF), UInt8(type >> 8),
                          UInt8(payload.count & 0xFF), UInt8(payload.count >> 8),
                          seq]
        p += payload
        let c = crc16(p)
        p += [UInt8(c & 0xFF), UInt8(c >> 8)]
        return p
    }
}

struct Frame {
    let type: UInt16
    let seq: UInt8
    let payload: [UInt8]
}

/// Splits the byte stream into frames. Several frames can arrive in one read.
struct FrameParser {
    private var buf: [UInt8] = []

    mutating func feed(_ bytes: [UInt8]) -> [Frame] {
        buf += bytes
        var out: [Frame] = []
        while true {
            guard let start = buf.firstIndex(of: 0x55) else { buf.removeAll(); break }
            if start > 0 { buf.removeFirst(start) }
            if buf.count < 8 { break }
            let control = UInt16(buf[1]) | UInt16(buf[2]) << 8
            let hasCrc = (control & 0x20) != 0
            let len = Int(buf[5]) | Int(buf[6]) << 8
            let total = 8 + len + (hasCrc ? 2 : 0)
            if len > 1024 { buf.removeFirst(); continue }
            if buf.count < total { break }
            let type = UInt16(buf[3]) | UInt16(buf[4]) << 8
            out.append(Frame(type: type, seq: buf[7], payload: Array(buf[8..<(8 + len)])))
            buf.removeFirst(total)
        }
        return out
    }
}
