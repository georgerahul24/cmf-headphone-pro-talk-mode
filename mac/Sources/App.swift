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
            Panel(talk: talk)
        } label: {
            Image(systemName: talk.active ? "bubble.left.and.bubble.right.fill" : "bubble.left.and.bubble.right")
        }
        .menuBarExtraStyle(.window)
    }
}

// MARK: - look

enum Pal {
    static let bg = Color(hex: 0x15141E)
    static let bgTop = Color(hex: 0x221E36)
    static let card = Color(hex: 0x211F30)
    static let card2 = Color(hex: 0x2B293D)
    static let text = Color(hex: 0xF4F3FA)
    static let sub = Color(hex: 0x908DAA)
    static let faint = Color(hex: 0x5E5B78)
    static let blue1 = Color(hex: 0x3B6FF5)
    static let blue2 = Color(hex: 0x5FA2FF)
    static let purple = Color(hex: 0x8B5CF6)
    static let violet = Color(hex: 0xB46CF7)
    static let pink = Color(hex: 0xEC5C9A)
    static let teal = Color(hex: 0x35D0B2)
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double(hex >> 16 & 0xFF) / 255, green: Double(hex >> 8 & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

private let spring = Animation.spring(response: 0.4, dampingFraction: 0.8)

// MARK: - panel

struct Panel: View {
    @ObservedObject var talk: TalkMode

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            let now = ctx.date
            ScrollView(showsIndicators: false) {
                VStack(spacing: 12) {
                    header
                    hero(now)
                    talkButton(now)
                    noiseCard
                    spatialCard
                    saverCard(now)
                    statsCard(now)
                    settingsCard
                }
                .padding(14)
            }
        }
        .frame(width: 340, height: 640)
        .background(LinearGradient(stops: [.init(color: Pal.bgTop, location: 0), .init(color: Pal.bg, location: 0.45)],
                                   startPoint: .top, endPoint: .bottom))
        .foregroundStyle(Pal.text)
        .environment(\.colorScheme, .dark)
        .animation(spring, value: talk.active)
        .animation(spring, value: talk.ancMode)
        .animation(spring, value: talk.connected)
    }

    private var header: some View {
        HStack(spacing: 9) {
            RoundedRectangle(cornerRadius: 9)
                .fill(LinearGradient(colors: [Pal.purple, Pal.violet], startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(width: 28, height: 28)
                .overlay(Image(systemName: "bubble.left.and.bubble.right.fill").font(.system(size: 12)).foregroundStyle(.white))
            Text("talk mode").font(.system(size: 14, weight: .semibold))
            Spacer()
            HStack(spacing: 6) {
                Circle().fill(talk.connected ? Pal.teal : Pal.faint).frame(width: 7, height: 7)
                Text(talk.connected ? "Connected" : (talk.status.hasPrefix("Connecting") ? "Connecting" : "Offline"))
                    .font(.system(size: 11, weight: .medium)).foregroundStyle(Pal.sub)
            }
            .padding(.horizontal, 10).padding(.vertical, 5)
            .background(Capsule().fill(Pal.card))
        }
    }

    private func hero(_ now: Date) -> some View {
        let colors = talk.active ? [Pal.purple, Pal.pink] : [Pal.blue1, Pal.blue2]
        return HStack {
            VStack(alignment: .leading, spacing: 6) {
                Text(talk.active ? "TALKING" : (talk.charging ? "CHARGING" : "BATTERY"))
                    .font(.system(size: 9, weight: .semibold)).tracking(1)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Capsule().fill(.white.opacity(0.18)))
                if talk.active {
                    Text(clock(now.timeIntervalSince(talk.talkSince ?? now)))
                        .font(.system(size: 34, weight: .semibold)).monospacedDigit()
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                } else {
                    HStack(alignment: .lastTextBaseline, spacing: 2) {
                        Text(talk.battery.map(String.init) ?? "--").font(.system(size: 38, weight: .semibold))
                            .contentTransition(.numericText())
                        Text("%").font(.system(size: 16)).opacity(0.7)
                    }
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
                Text(talk.active ? "Transparency on · music at \(talk.talkVolumePercent)%"
                     : (talk.connected ? talk.ancName : talk.status))
                    .font(.system(size: 11)).opacity(0.85).lineLimit(1)
            }
            Spacer()
            ZStack {
                Ring(p: talk.active ? 1 : Double(talk.battery ?? 0) / 100, colors: [.white, .white],
                     track: .white.opacity(0.2), line: 6)
                Image(systemName: talk.active ? "waveform" : "bolt.fill").font(.system(size: 18))
            }
            .frame(width: 64, height: 64)
        }
        .foregroundStyle(.white)
        .padding(18)
        .background(
            ZStack {
                LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
                GeometryReader { g in
                    Circle().fill(.white.opacity(0.08)).frame(width: g.size.height * 1.5)
                        .position(x: g.size.width * 0.95, y: 0)
                    Circle().fill(.white.opacity(0.06)).frame(width: g.size.height * 0.9)
                        .position(x: g.size.width * 0.12, y: g.size.height * 1.1)
                }
            }
        )
        .clipShape(RoundedRectangle(cornerRadius: 22))
        .shadow(color: colors[0].opacity(0.45), radius: 14, y: 6)
    }

    private func talkButton(_ now: Date) -> some View {
        Button { talk.toggle() } label: {
            HStack(spacing: 8) {
                Image(systemName: talk.active ? "xmark" : "bubble.left.and.bubble.right.fill")
                Text(talk.active ? "End talk mode" : "Start talking").font(.system(size: 13, weight: .semibold))
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity).frame(height: 40)
            .background(LinearGradient(colors: talk.active ? [Pal.pink, Pal.violet] : [Pal.purple, Pal.violet],
                                       startPoint: .leading, endPoint: .trailing))
            .clipShape(RoundedRectangle(cornerRadius: 13))
            .shadow(color: Pal.purple.opacity(0.4), radius: 10, y: 4)
            .opacity(talk.connected ? 1 : 0.45)
        }
        .buttonStyle(Press())
        .disabled(!talk.connected)
    }

    private var noiseCard: some View {
        let isAnc = talk.ancMode.map { Proto.ancLevels.contains($0) } ?? false
        return Card(title: "Noise control") {
            Seg(options: [(Proto.ancOff, "Off"), (Proto.ancTransparency, "Transparency"), (0xFF, "ANC")],
                selected: isAnc ? 0xFF : talk.ancMode, enabled: talk.connected) { v in
                talk.setAnc(v == 0xFF ? (isAnc ? talk.ancMode! : Proto.ancAdaptive) : v)
            }
            if isAnc {
                Seg(options: [(Proto.ancLow, "Low"), (Proto.ancMid, "Mid"), (Proto.ancHigh, "High"), (Proto.ancAdaptive, "Adaptive")],
                    selected: talk.ancMode, enabled: talk.connected, colors: [Pal.blue1, Pal.blue2]) { talk.setAnc($0) }
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
    }

    private var spatialCard: some View {
        Card(title: "Spatial audio") {
            Seg(options: [(0, "Off"), (2, "Concert"), (3, "Mode 2")], selected: talk.spatial, enabled: talk.connected) {
                talk.setSpatial($0)
            }
        }
    }

    private func saverCard(_ now: Date) -> some View {
        let quiet = talk.quietSince.map { dur(now.timeIntervalSince($0)) } ?? "a while"
        var big = "Idle", small = "Nothing played for \(quiet).", p = 0.0
        var colors = [Pal.faint, Pal.faint]
        if !talk.idleSaver { big = "Off"; small = "Battery saver is off." }
        else if !talk.connected { big = "Waiting"; small = "Connect your headphones to start." }
        else if let t = talk.idleOffSince {
            big = "Saving"; small = "Noise control off for \(dur(now.timeIntervalSince(t))). Comes back when audio plays."
            p = 1; colors = [Pal.teal, Pal.blue2]
        } else if talk.active { big = "Talking"; small = "The timer waits until you finish talking." }
        else if talk.playing { big = "Playing"; small = "The 10 minute timer starts when audio stops." }
        else if let d = talk.idleDeadline {
            let left = max(0, d.timeIntervalSince(now))
            big = clock(left.rounded(.up)); small = "until noise control turns off. Nothing played for \(quiet)."
            p = 1 - left / TalkMode.idleSeconds; colors = [Pal.purple, Pal.pink]
        } else if talk.ancMode == Proto.ancOff { small = "Nothing played for \(quiet). Noise control is already off." }

        return Card(title: "Battery saver") {
            HStack(spacing: 14) {
                ZStack {
                    Ring(p: p, colors: colors, track: Pal.card2, line: 6)
                    Image(systemName: "leaf.fill").font(.system(size: 15))
                        .foregroundStyle(talk.idleOffSince != nil ? Pal.teal : Pal.sub)
                }
                .frame(width: 58, height: 58)
                VStack(alignment: .leading, spacing: 2) {
                    Text(big).font(.system(size: 20, weight: .semibold)).monospacedDigit()
                    Text(small).font(.system(size: 11)).foregroundStyle(Pal.sub).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
        }
    }

    private func statsCard(_ now: Date) -> some View {
        let s = talk.stats
        let talkSecs = s.talkSeconds + (talk.talkSince.map { now.timeIntervalSince($0) } ?? 0)
        let restSecs = s.idleOffSeconds + (talk.idleOffSince.map { now.timeIntervalSince($0) } ?? 0)
        return Card(title: "Statistics") {
            HStack(spacing: 8) {
                Stat(value: "\(s.talkCount)", label: "Times talked", color: Pal.pink)
                Stat(value: dur(talkSecs), label: "Time talking", color: Pal.violet)
            }
            HStack(spacing: 8) {
                Stat(value: "\(s.idleOffCount)", label: "Saver turned ANC off", color: Pal.blue2)
                Stat(value: dur(restSecs), label: "ANC rested for", color: Pal.teal)
            }
            HStack {
                Text("\(s.restoreCount) restored when music played · since \(s.since.formatted(date: .abbreviated, time: .omitted))")
                    .font(.system(size: 10)).foregroundStyle(Pal.faint)
                Spacer()
                Button("Reset") { talk.resetStats() }.buttonStyle(.plain).font(.system(size: 10)).foregroundStyle(Pal.faint)
            }
        }
    }

    private var settingsCard: some View {
        Card(title: "Settings") {
            Toggle("Button starts talk mode", isOn: $talk.enabled)
            Toggle("Battery saver (10 min idle)", isOn: $talk.idleSaver)
            Stepper("Music while talking: \(talk.talkVolumePercent)%", value: $talk.talkVolumePercent, in: 0...100, step: 5)
            Toggle("Open at login", isOn: $talk.launchAtLogin)
            HStack {
                Text("Unofficial app").font(.system(size: 10)).foregroundStyle(Pal.faint)
                Spacer()
                Button("Quit") { NSApplication.shared.terminate(nil) }.buttonStyle(.plain)
                    .font(.system(size: 12, weight: .medium)).foregroundStyle(Pal.sub)
            }
        }
        .toggleStyle(.switch).tint(Pal.purple).controlSize(.small).font(.system(size: 12))
    }
}

// MARK: - parts

struct Card<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.system(size: 12, weight: .medium)).foregroundStyle(Pal.sub)
            content
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18).fill(Pal.card))
    }
}

/// Pill selector with a sliding highlight.
struct Seg: View {
    let options: [(UInt8, String)]
    let selected: UInt8?
    let enabled: Bool
    var colors: [Color] = [Pal.purple, Pal.violet]
    let pick: (UInt8) -> Void

    var body: some View {
        let idx = options.firstIndex { $0.0 == selected }
        GeometryReader { g in
            let w = (g.size.width - 6) / CGFloat(options.count)
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 9)
                    .fill(LinearGradient(colors: colors, startPoint: .leading, endPoint: .trailing))
                    .frame(width: w, height: 28)
                    .offset(x: 3 + w * CGFloat(idx ?? 0))
                    .opacity(idx == nil ? 0 : 1)
                HStack(spacing: 0) {
                    ForEach(options.indices, id: \.self) { i in
                        Text(options[i].1)
                            .font(.system(size: 11.5, weight: .medium))
                            .foregroundStyle(i == idx ? .white : (enabled ? Pal.sub : Pal.faint))
                            .frame(width: w, height: 28)
                            .contentShape(Rectangle())
                            .onTapGesture { if enabled { pick(options[i].0) } }
                    }
                }
                .padding(.horizontal, 3)
            }
            .frame(width: g.size.width, height: g.size.height)
            .animation(spring, value: idx)
        }
        .frame(height: 34)
        .background(RoundedRectangle(cornerRadius: 12).fill(Pal.card2))
    }
}

struct Ring: View {
    let p: Double
    let colors: [Color]
    let track: Color
    let line: CGFloat

    var body: some View {
        ZStack {
            Circle().stroke(track, lineWidth: line)
            Circle()
                .trim(from: 0, to: max(0, min(1, p)))
                .stroke(AngularGradient(colors: colors, center: .center),
                        style: StrokeStyle(lineWidth: line, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .padding(line / 2)
        .animation(.easeOut(duration: 0.8), value: p)
    }
}

struct Stat: View {
    let value: String
    let label: String
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            RoundedRectangle(cornerRadius: 2).fill(color).frame(width: 14, height: 4)
            Text(value).font(.system(size: 17, weight: .semibold)).monospacedDigit()
                .contentTransition(.numericText())
            Text(label).font(.system(size: 10)).foregroundStyle(Pal.sub).lineLimit(1)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(Pal.card2))
    }
}

/// Shrinks a little while pressed.
struct Press: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

// MARK: - text

func clock(_ secs: TimeInterval) -> String {
    let s = Int(max(0, secs))
    let h = s / 3600, m = s % 3600 / 60, sec = s % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
}

func dur(_ secs: TimeInterval) -> String {
    let m = Int(secs / 60)
    if secs <= 0 { return "0 min" }
    if secs < 60 { return "\(Int(secs)) s" }
    if m < 60 { return "\(m) min" }
    return "\(m / 60) h \(m % 60) min"
}
