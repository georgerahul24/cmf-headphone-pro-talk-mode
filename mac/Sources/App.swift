import SwiftUI

@main
struct TalkModeApp: App {
    @StateObject private var talk = TalkMode()

    var body: some Scene {
        MenuBarExtra {
            MenuContent(talk: talk)
        } label: {
            Image(systemName: talk.active ? "person.wave.2.fill" : "headphones")
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
