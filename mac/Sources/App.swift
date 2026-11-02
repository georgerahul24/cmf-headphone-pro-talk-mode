import SwiftUI

@main
struct TalkModeApp: App {
    @StateObject private var talk = TalkMode()

    init() {
        // Only one copy may run. The copy started by launchd (login agent) wins,
        // so it can be restarted after a crash.
        let me = ProcessInfo.processInfo.processIdentifier
        let others = NSRunningApplication.runningApplications(withBundleIdentifier: Bundle.main.bundleIdentifier ?? "")
            .filter { $0.processIdentifier != me }
        let fromLaunchd = ProcessInfo.processInfo.environment["XPC_SERVICE_NAME"] == "dev.georgerahul.cmftalkmode.agent"
        if !others.isEmpty {
            if fromLaunchd { others.forEach { $0.terminate() } } else { exit(0) }
        }
    }

    var body: some Scene {
        MenuBarExtra {
            MenuContent(talk: talk)
        } label: {
            Image(systemName: talk.active ? "bubble.left.and.bubble.right.fill" : "bubble.left.and.bubble.right")
        }
        .menuBarExtraStyle(.window)
    }
}

struct MenuContent: View {
    @ObservedObject var talk: TalkMode

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("CMF Talk Mode").font(.headline)
            VStack(alignment: .leading, spacing: 2) {
                Text(talk.status)
                Text("Noise mode: \(talk.ancName)")
                if let b = talk.battery { Text("Battery: \(b)%") }
            }
            .font(.callout).foregroundStyle(.secondary)

            Divider()
            Toggle("Talk mode button", isOn: $talk.enabled)
            Text("When on, pressing the button set to Spatial Audio toggles talk mode. When off, the button works as normal Spatial Audio.")
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)

            Stepper("Talk volume: \(talk.talkVolumePercent)%", value: $talk.talkVolumePercent, in: 0...100, step: 5)

            Button(talk.active ? "End talk mode" : "Start talk mode") { talk.toggle() }
                .disabled(talk.status != "Connected")

            Divider()
            Toggle("Open at login", isOn: $talk.launchAtLogin)
            Button("Quit") { NSApplication.shared.terminate(nil) }
        }
        .padding(14)
        .frame(width: 280)
    }
}
