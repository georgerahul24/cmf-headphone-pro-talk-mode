import Foundation
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

    /// True while any app is playing through the default output (music, video, calls).
    static func isPlaying() -> Bool {
        guard let dev = defaultOutput() else { return false }
        var running = UInt32(0)
        var size = UInt32(MemoryLayout<UInt32>.size)
        var a = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyDeviceIsRunningSomewhere,
                                           mScope: kAudioObjectPropertyScopeGlobal,
                                           mElement: kAudioObjectPropertyElementMain)
        return AudioObjectGetPropertyData(dev, &a, 0, nil, &size, &running) == noErr && running != 0
    }
}

/// Play and pause for whatever app is playing media (Music, Spotify, a browser...),
/// through the system's Now Playing controls, like the media keys on the keyboard.
enum NowPlaying {
    private typealias Send = @convention(c) (UInt32, CFDictionary?) -> Bool
    private static let send: Send? = {
        guard let h = dlopen("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote", RTLD_LAZY),
              let f = dlsym(h, "MRMediaRemoteSendCommand") else { return nil }
        return unsafeBitCast(f, to: Send.self)
    }()

    static func play() { _ = send?(0, nil) }
    static func pause() { _ = send?(1, nil) }
}
