import Foundation
import IOBluetooth

/// Keeps an RFCOMM link open to the CMF Headphone Pro control service and
/// reconnects when the headphones come back.
final class Headset: NSObject, IOBluetoothRFCOMMChannelDelegate {
    var onFrame: ((Frame) -> Void)?
    var onState: ((String) -> Void)?

    private(set) var connected = false
    private var device: IOBluetoothDevice?
    private var channel: IOBluetoothRFCOMMChannel?
    private var parser = FrameParser()
    private var seq: UInt8 = 0
    private var timer: Timer?
    private var connectNote: IOBluetoothUserNotification?

    func start() {
        connectNote = IOBluetoothDevice.register(forConnectNotifications: self,
                                                 selector: #selector(deviceConnected(_:device:)))
        timer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            self?.tryConnect()
        }
        tryConnect()
    }

    @objc private func deviceConnected(_ note: IOBluetoothUserNotification, device: IOBluetoothDevice) {
        if isTarget(device) {
            // Give the audio profiles a moment to settle first.
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.tryConnect() }
        }
    }

    private func isTarget(_ d: IOBluetoothDevice) -> Bool {
        (d.name ?? "").contains("CMF Headphone Pro")
    }

    private func findDevice() -> IOBluetoothDevice? {
        let paired = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
        return paired.first { isTarget($0) && $0.isConnected() }
    }

    // Called on start, on the system "device connected" event, and once a minute
    // as a fallback. Returns at once when already connected.
    func tryConnect() {
        if connected { return }
        guard let dev = findDevice() else { onState?("Headphones not connected"); return }
        device = dev
        let uuid = IOBluetoothSDPUUID(bytes: Proto.serviceUUID, length: 16)
        var chID: BluetoothRFCOMMChannelID = 0
        if dev.getServiceRecord(for: uuid)?.getRFCOMMChannelID(&chID) != kIOReturnSuccess {
            dev.performSDPQuery(nil, uuids: [uuid])
            onState?("Looking up control channel...")
            return // next timer tick retries with the fresh SDP record
        }
        var ch: IOBluetoothRFCOMMChannel?
        let r = dev.openRFCOMMChannelSync(&ch, withChannelID: chID, delegate: self)
        if r == kIOReturnSuccess, let ch {
            channel = ch
            connected = true
            parser = FrameParser()
            onState?("Connected")
            send(Proto.ancGet)
            send(Proto.spatialGet)
            send(Proto.batteryGet)
        } else {
            onState?("Could not open control channel (\(r))")
        }
    }

    /// Connect button: asks macOS to connect the headphones (like Connect in the
    /// Bluetooth menu). Audio profiles follow, then the control channel opens.
    func connectNow() {
        if connected { return }
        let paired = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
        guard let dev = paired.first(where: isTarget) else { onState?("Headphones not paired with this Mac"); return }
        if dev.isConnected() { tryConnect(); return }
        onState?("Connecting...")
        if dev.openConnection(self) != kIOReturnSuccess {
            onState?("Couldn't reach them. Turn the headphones on and try again.")
        }
    }

    @objc func connectionComplete(_ device: IOBluetoothDevice!, status: IOReturn) {
        if status == kIOReturnSuccess {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.tryConnect() }
        } else {
            onState?("Couldn't reach them. Turn the headphones on and try again.")
        }
    }

    func send(_ type: UInt16, _ payload: [UInt8] = []) {
        guard connected, let channel else { return }
        seq = seq >= 250 ? 1 : seq + 1
        var p = Proto.encode(type: type, payload: payload, seq: seq)
        channel.writeSync(&p, length: UInt16(p.count))
    }

    func rfcommChannelData(_ ch: IOBluetoothRFCOMMChannel!, data p: UnsafeMutableRawPointer!, length n: Int) {
        let bytes = Array(UnsafeBufferPointer(start: p.assumingMemoryBound(to: UInt8.self), count: n))
        for f in parser.feed(bytes) { onFrame?(f) }
    }

    func rfcommChannelClosed(_ ch: IOBluetoothRFCOMMChannel!) {
        connected = false
        channel = nil
        onState?("Disconnected")
    }
}
