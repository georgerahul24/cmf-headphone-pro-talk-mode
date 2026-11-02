import CoreAudio
import AudioToolbox

/// System output volume (0...1) of the current default output device.
enum SystemVolume {
    private static func defaultOutput() -> AudioDeviceID? {
        var id = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice,
                                              mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        let r = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &id)
        return r == noErr ? id : nil
    }

    private static var addr = AudioObjectPropertyAddress(
        mSelector: kAudioHardwareServiceDeviceProperty_VirtualMainVolume,
        mScope: kAudioDevicePropertyScopeOutput,
        mElement: kAudioObjectPropertyElementMain)

    static func get() -> Float? {
        guard let dev = defaultOutput() else { return nil }
        var v = Float32(0)
        var size = UInt32(MemoryLayout<Float32>.size)
        let r = AudioObjectGetPropertyData(dev, &addr, 0, nil, &size, &v)
        return r == noErr ? v : nil
    }

    static func set(_ value: Float) {
        guard let dev = defaultOutput() else { return }
        var v = Float32(max(0, min(1, value)))
        AudioObjectSetPropertyData(dev, &addr, 0, nil, UInt32(MemoryLayout<Float32>.size), &v)
    }
}
