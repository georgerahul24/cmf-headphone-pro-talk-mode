import SwiftUI
import Charts

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
            if fromLaunchd { others.forEach { $0.terminate() } } else {
                // Opened again from Applications: ask the running copy to show its window.
                DistributedNotificationCenter.default().postNotificationName(TalkMode.showWindow, object: nil,
                                                                             deliverImmediately: true)
                exit(0)
            }
        }
    }

    var body: some Scene {
        MenuBarExtra {
            MenuPanel(talk: talk)
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
    // Noise mode chart colors (checked for color-blind separation on the card color).
    static func mode(_ m: ModeGroup) -> Color {
        switch m {
        case .anc: return Color(hex: 0x9466F5)
        case .transparency: return Color(hex: 0xC0832B)
        case .off: return Color(hex: 0x2BA88F)
        }
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double(hex >> 16 & 0xFF) / 255, green: Double(hex >> 8 & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

private let spring = Animation.spring(response: 0.4, dampingFraction: 0.8)

// MARK: - menu bar

/// The small popover: noise control, spatial audio and the battery saver timer.
struct MenuPanel: View {
    @ObservedObject var talk: TalkMode

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            let c = Cards(talk: talk)
            VStack(spacing: 12) {
                c.header
                if talk.connected { c.compactTalk(ctx.date) } else { c.connectCard }
                c.noiseCard
                c.spatialCard
                c.eqMenuCard
                c.saverCard(ctx.date)
                c.openAppRow
            }
            .padding(14)
        }
        .frame(width: 320)
        .background(LinearGradient(stops: [.init(color: Pal.bgTop, location: 0), .init(color: Pal.bg, location: 0.45)],
                                   startPoint: .top, endPoint: .bottom))
        .foregroundStyle(Pal.text)
        .environment(\.colorScheme, .dark)
        .animation(spring, value: talk.active)
        .animation(spring, value: talk.ancMode)
        .animation(spring, value: talk.connected)
    }
}

// MARK: - full window

/// The full app window, opened from the menu bar.
final class MainWindowController: NSObject, NSWindowDelegate {
    static let shared = MainWindowController()
    weak var talk: TalkMode?
    private var window: NSWindow?

    func show() {
        guard let talk else { return }
        if window == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 980, height: 700),
                             styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                             backing: .buffered, defer: false)
            w.title = "CMF Talk Mode"
            w.titlebarAppearsTransparent = true
            w.titleVisibility = .hidden
            w.isReleasedWhenClosed = false
            w.appearance = NSAppearance(named: .darkAqua)
            w.backgroundColor = NSColor(Pal.bg)
            w.minSize = NSSize(width: 780, height: 560)
            w.contentView = NSHostingView(rootView: MainWindow(talk: talk))
            w.center()
            w.setFrameAutosaveName("MainWindow")
            w.delegate = self
            window = w
        }
        // Show in the Dock and app switcher while the window is open.
        NSApp.setActivationPolicy(.regular)
        DockIcon.shared.start(talk)
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func windowWillClose(_ notification: Notification) {
        DockIcon.shared.stop()
        NSApp.setActivationPolicy(.accessory)
    }
}

struct MainWindow: View {
    @ObservedObject var talk: TalkMode
    @ObservedObject private var updater = Updater.shared
    @ObservedObject private var sync = Sync.shared
    @StateObject private var nav = Nav()
    private var page: Page { nav.page }

    final class Nav: ObservableObject { @Published var page = Page.home }
    @Namespace private var ns

    enum Page: String, CaseIterable {
        case home = "Home", sound = "Sound", activity = "Activity", settings = "Settings"
        var icon: String {
            switch self {
            case .home: return "house.fill"
            case .sound: return "slider.horizontal.3"
            case .activity: return "chart.bar.fill"
            case .settings: return "gearshape.fill"
            }
        }
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            let c = Cards(talk: talk)
            HStack(spacing: 0) {
                sidebar(c)
                ScrollView(showsIndicators: false) {
                    content(c, ctx.date)
                        .id(page)
                        .transition(.asymmetric(insertion: .opacity.combined(with: .offset(y: 14)), removal: .opacity))
                        .frame(maxWidth: 820)
                        .padding(.horizontal, 28).padding(.top, 34).padding(.bottom, 28)
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .background(LinearGradient(stops: [.init(color: Pal.bgTop, location: 0), .init(color: Pal.bg, location: 0.5)],
                                   startPoint: .topTrailing, endPoint: .bottomLeading).ignoresSafeArea())
        .foregroundStyle(Pal.text)
        .environment(\.colorScheme, .dark)
        .animation(spring, value: talk.active)
        .animation(spring, value: talk.ancMode)
        .animation(spring, value: talk.connected)
    }

    private func sidebar(_ c: Cards) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 10) {
                RoundedRectangle(cornerRadius: 10)
                    .fill(LinearGradient(colors: [Pal.purple, Pal.violet], startPoint: .topLeading, endPoint: .bottomTrailing))
                    .frame(width: 32, height: 32)
                    .overlay(Image(systemName: "bubble.left.and.bubble.right.fill").font(.system(size: 13)).foregroundStyle(.white))
                VStack(alignment: .leading, spacing: 0) {
                    Text("talk mode").font(.system(size: 14, weight: .semibold))
                    Text("Headphone Pro").font(.system(size: 11)).foregroundStyle(Pal.sub)
                }
            }
            .padding(.bottom, 22)
            ForEach(Page.allCases, id: \.self) { p in
                Button { withAnimation(spring) { nav.page = p } } label: {
                    HStack(spacing: 10) {
                        Image(systemName: p.icon).font(.system(size: 13)).frame(width: 18)
                        Text(p.rawValue).font(.system(size: 13, weight: .medium))
                        Spacer()
                    }
                    .foregroundStyle(page == p ? Pal.text : Pal.sub)
                    .padding(.horizontal, 12).frame(height: 36)
                    .background {
                        if page == p {
                            RoundedRectangle(cornerRadius: 10).fill(Pal.card2).matchedGeometryEffect(id: "sel", in: ns)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            Spacer()
            HStack(spacing: 7) {
                Circle().fill(talk.connected ? Pal.teal : Pal.faint).frame(width: 7, height: 7)
                Text(talk.connected ? "Connected" : "Not connected").font(.system(size: 11, weight: .medium)).foregroundStyle(Pal.sub)
                Spacer()
                if let b = talk.battery {
                    Text("\(b)%").font(.system(size: 11, weight: .medium)).monospacedDigit().foregroundStyle(Pal.sub)
                }
            }
            .padding(.horizontal, 12)
        }
        .padding(.horizontal, 14).padding(.top, 44).padding(.bottom, 18)
        .frame(width: 210)
        .frame(maxHeight: .infinity)
        .background(Pal.bg.opacity(0.6))
    }

    private let grid = [GridItem(.flexible(), spacing: 16, alignment: .top), GridItem(.flexible(), spacing: 16, alignment: .top)]

    @ViewBuilder private func content(_ c: Cards, _ now: Date) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(page.rawValue).font(.system(size: 28, weight: .semibold))
            switch page {
            case .home:
                if !talk.connected { c.connectCard }
                c.hero(now)
                c.talkButton(now)
                LazyVGrid(columns: grid, spacing: 16) {
                    c.saverCard(now)
                    c.eqSummary
                }
            case .sound:
                LazyVGrid(columns: grid, spacing: 16) {
                    VStack(spacing: 16) { c.noiseCard; c.spatialCard }
                    c.eqCard
                }
            case .activity:
                LazyVGrid(columns: grid, spacing: 16) {
                    c.historyCard(now)
                    c.statsCard(now)
                }
            case .settings:
                LazyVGrid(columns: grid, spacing: 16) {
                    c.settingsCard
                    VStack(spacing: 16) { c.syncCard; c.updatesCard }
                }
            }
        }
    }
}

// MARK: - cards

/// Every card, shared by the menu bar popover and the full window.
struct Cards {
    @ObservedObject var talk: TalkMode

    var header: some View {
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

    func hero(_ now: Date) -> some View {
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
                Text(talk.active ? (talk.talkPause ? "Transparency on · music paused" : "Transparency on · music at \(talk.talkVolumePercent)%")
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

    var connectCard: some View {
        let busy = talk.status.hasPrefix("Connecting")
        return Card(title: "Headphones not connected") {
            Text(talk.status == "Headphones not connected" ? "Turn them on, then click Connect." : talk.status)
                .font(.system(size: 11)).foregroundStyle(Pal.sub).fixedSize(horizontal: false, vertical: true)
            Button { talk.connect() } label: {
                HStack(spacing: 8) {
                    if busy { ProgressView().controlSize(.small).tint(.white) }
                    Text(busy ? "Connecting" : "Connect").font(.system(size: 13, weight: .semibold))
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity).frame(height: 36)
                .background(LinearGradient(colors: [Pal.purple, Pal.violet], startPoint: .leading, endPoint: .trailing))
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .opacity(busy ? 0.6 : 1)
            }
            .buttonStyle(Press())
            .disabled(busy)
        }
        .transition(.move(edge: .top).combined(with: .opacity))
    }

    func talkButton(_ now: Date) -> some View {
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

    var noiseCard: some View {
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

    var spatialCard: some View {
        Card(title: "Spatial audio") {
            Seg(options: [(0, "Off"), (2, "Concert"), (3, "Cinema")], selected: talk.spatial, enabled: talk.connected) {
                talk.setSpatial($0)
            }
        }
    }

    func saverCard(_ now: Date) -> some View {
        let quiet = talk.quietSince.map { dur(now.timeIntervalSince($0)) } ?? "a while"
        var big = "Idle", small = "Nothing played for \(quiet).", p = 0.0
        var colors = [Pal.faint, Pal.faint]
        if !talk.idleSaver { big = "Off"; small = "Battery saver is off." }
        else if !talk.connected { big = "Waiting"; small = "Connect your headphones to start." }
        else if let t = talk.idleOffSince {
            big = "Saving"; small = "Noise control off for \(dur(now.timeIntervalSince(t))). Comes back when audio plays."
            p = 1; colors = [Pal.teal, Pal.blue2]
        } else if talk.active { big = "Talking"; small = "The timer waits until you finish talking." }
        else if talk.playing { big = "Playing"; small = "The \(talk.idleMinutes) minute timer starts when audio stops." }
        else if let d = talk.idleDeadline {
            let left = max(0, d.timeIntervalSince(now))
            big = clock(left.rounded(.up)); small = "until noise control turns off. Nothing played for \(quiet)."
            p = 1 - left / talk.idleSeconds; colors = [Pal.purple, Pal.pink]
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

    func dayName(_ hv: HistoryView, _ i: Int) -> String {
        i == 6 ? "Today" : i == 5 ? "Yesterday" : hv.days[i].formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
    }

    func historyCard(_ now: Date) -> some View {
        let hv = talk.history(now: now)
        let day = talk.historyDay
        let top = max(hv.usage.max() ?? 0, 60)
        let spans = hv.spansOn(day)
        let totals = hv.totals(day)
        let all = max(totals.values.reduce(0, +), 1)
        let start = hv.days[day], len = hv.dayEnd(day).timeIntervalSince(start)
        let pts = hv.batteryOn(day)
        let hover = talk.batteryHover.flatMap { t in pts.min { abs($0.0.timeIntervalSince(t)) < abs($1.0.timeIntervalSince(t)) } }

        return Card(title: "Headphone time") {
            HStack(alignment: .lastTextBaseline) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(dur(hv.usage[day])).font(.system(size: 20, weight: .semibold)).monospacedDigit()
                        .contentTransition(.numericText())
                    Text(dayName(hv, day)).font(.system(size: 11)).foregroundStyle(Pal.sub)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 0) {
                    Text(dur(hv.usage.reduce(0, +))).font(.system(size: 12, weight: .medium)).monospacedDigit()
                    Text("last 7 days").font(.system(size: 10)).foregroundStyle(Pal.sub)
                }
            }
            // Seven bars; click one to pick the day.
            HStack(alignment: .bottom, spacing: 6) {
                ForEach(0..<7, id: \.self) { i in
                    VStack(spacing: 4) {
                        Spacer(minLength: 0)
                        UnevenRoundedRectangle(topLeadingRadius: 4, topTrailingRadius: 4)
                            .fill(Pal.mode(.anc).opacity(i == day ? 1 : 0.35))
                            .frame(height: max(2, 70 * hv.usage[i] / top))
                            .help("\(dayName(hv, i)): \(dur(hv.usage[i]))")
                        Text(hv.days[i].formatted(.dateTime.weekday(.narrow)))
                            .font(.system(size: 9, weight: .medium)).foregroundStyle(i == day ? Pal.text : Pal.faint)
                    }
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
                    .onTapGesture { withAnimation(spring) { talk.historyDay = i } }
                }
            }
            .frame(height: 90)
            .animation(spring, value: hv.usage)

            Divider().overlay(Pal.card2)
            Text("Noise control").font(.system(size: 12, weight: .medium)).foregroundStyle(Pal.sub)
            // 24 hour strip; hover a block to see its times.
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 4).fill(Pal.card2)
                    ForEach(spans) { s in
                        let x0 = s.start.timeIntervalSince(start) / len * g.size.width
                        let w = max(1.5, s.end.timeIntervalSince(s.start) / len * g.size.width - 2)
                        RoundedRectangle(cornerRadius: 4).fill(Pal.mode(s.mode))
                            .frame(width: w).offset(x: x0 + 1)
                            .help("\(s.mode.rawValue) · \(s.start.formatted(date: .omitted, time: .shortened)) to \(s.end.formatted(date: .omitted, time: .shortened))")
                    }
                }
            }
            .frame(height: 20)
            HStack {
                ForEach(["00", "06", "12", "18", "24"], id: \.self) { h in
                    Text(h).font(.system(size: 9)).foregroundStyle(Pal.faint).monospacedDigit()
                    if h != "24" { Spacer() }
                }
            }
            if spans.isEmpty { Text("No data for this day yet.").font(.system(size: 11)).foregroundStyle(Pal.sub) }
            ForEach(ModeGroup.allCases) { m in
                let t = totals[m] ?? 0
                HStack(spacing: 8) {
                    RoundedRectangle(cornerRadius: 2).fill(Pal.mode(m)).frame(width: 9, height: 9)
                    Text(m.rawValue).font(.system(size: 12))
                    Spacer()
                    Text(dur(t)).font(.system(size: 12, weight: .medium)).monospacedDigit()
                    Text("\(Int(t * 100 / all))%").font(.system(size: 10)).foregroundStyle(Pal.sub)
                        .monospacedDigit().frame(width: 32, alignment: .trailing)
                }
            }

            Divider().overlay(Pal.card2)
            HStack {
                Text("Battery").font(.system(size: 12, weight: .medium)).foregroundStyle(Pal.sub)
                Spacer()
                if let h = hover {
                    Text("\(h.0.formatted(date: .omitted, time: .shortened)) · \(h.1)%")
                        .font(.system(size: 11)).monospacedDigit()
                }
            }
            if pts.count < 2 {
                Text("No battery readings for this day yet.").font(.system(size: 11)).foregroundStyle(Pal.sub)
            } else {
                Chart {
                    ForEach(pts.indices, id: \.self) { i in
                        AreaMark(x: .value("Time", pts[i].0), y: .value("Battery", pts[i].1))
                            .foregroundStyle(LinearGradient(colors: [Pal.blue2.opacity(0.3), Pal.blue2.opacity(0)],
                                                            startPoint: .top, endPoint: .bottom))
                        LineMark(x: .value("Time", pts[i].0), y: .value("Battery", pts[i].1))
                            .foregroundStyle(Pal.blue2).lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round))
                    }
                    if let h = hover {
                        RuleMark(x: .value("Time", h.0)).foregroundStyle(Pal.sub.opacity(0.5)).lineStyle(StrokeStyle(lineWidth: 1))
                        PointMark(x: .value("Time", h.0), y: .value("Battery", h.1)).foregroundStyle(Pal.blue2).symbolSize(40)
                    }
                }
                .chartXScale(domain: start...hv.dayEnd(day))
                .chartYScale(domain: 0...100)
                .chartYAxis {
                    AxisMarks(values: [0, 50, 100]) { v in
                        AxisGridLine().foregroundStyle(Pal.card2)
                        AxisValueLabel { Text("\(v.as(Int.self) ?? 0)").font(.system(size: 9)).foregroundStyle(Pal.faint) }
                    }
                }
                .chartXAxis {
                    AxisMarks(values: .stride(by: .hour, count: 6)) { _ in
                        AxisValueLabel(format: .dateTime.hour(.twoDigits(amPM: .omitted))).font(.system(size: 9)).foregroundStyle(Pal.faint)
                    }
                }
                .chartOverlay { proxy in
                    Rectangle().fill(.clear).contentShape(Rectangle())
                        .onContinuousHover { phase in
                            switch phase {
                            case .active(let p): talk.batteryHover = proxy.value(atX: p.x, as: Date.self)
                            case .ended: talk.batteryHover = nil
                            }
                        }
                }
                .frame(height: 110)
            }
        }
    }

    func statsCard(_ now: Date) -> some View {
        let s = talk.allStats
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

    var settingsCard: some View {
        Card(title: "Settings") {
            Toggle("Button starts talk mode", isOn: $talk.enabled)
            Toggle("Battery saver", isOn: $talk.idleSaver)
            if talk.idleSaver {
                Picker("Turn off after", selection: $talk.idleMinutes) {
                    ForEach([5, 10, 15, 30, 60], id: \.self) { Text("\($0) min").tag($0) }
                }
                .pickerStyle(.menu)
            }
            VStack(alignment: .leading, spacing: 6) {
                Text("When you talk").font(.system(size: 11)).foregroundStyle(Pal.sub)
                Seg(options: [(0, "Lower music"), (1, "Pause music")], selected: talk.talkPause ? 1 : 0,
                    enabled: true) { v in withAnimation(.snappy) { talk.talkPause = v == 1 } }
                if talk.talkPause {
                    Text("Music pauses when talk mode starts and plays again when it ends.")
                        .font(.system(size: 11)).foregroundStyle(Pal.sub)
                        .fixedSize(horizontal: false, vertical: true)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                } else {
                    Stepper("Music while talking: \(talk.talkVolumePercent)%", value: $talk.talkVolumePercent, in: 0...100, step: 5)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            Toggle("Open at login", isOn: $talk.launchAtLogin)
            Button("Show history file") { NSWorkspace.shared.activateFileViewerSelecting([History.shared.url]) }
                .buttonStyle(.plain).foregroundStyle(Pal.sub)
            Text("Unofficial app. Not made by Nothing or CMF.").font(.system(size: 10)).foregroundStyle(Pal.faint)
        }
        .toggleStyle(.switch).tint(Pal.purple).controlSize(.small).font(.system(size: 12))
    }

    /// Menu bar: one line with battery and a talk button.
    func compactTalk(_ now: Date) -> some View {
        Button { talk.toggle() } label: {
            HStack(spacing: 8) {
                Image(systemName: talk.active ? "xmark" : "bubble.left.and.bubble.right.fill")
                Text(talk.active ? "End talk · \(clock(now.timeIntervalSince(talk.talkSince ?? now)))" : "Start talking")
                    .font(.system(size: 13, weight: .semibold)).monospacedDigit()
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity).frame(height: 38)
            .background(LinearGradient(colors: talk.active ? [Pal.pink, Pal.violet] : [Pal.blue1, Pal.blue2],
                                       startPoint: .leading, endPoint: .trailing))
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(Press())
    }

    var eqSummary: some View {
        Card(title: "EQ") {
            Text(talk.eqTitle).font(.system(size: 20, weight: .semibold)).contentTransition(.opacity)
            Text(talk.eqMode == EqMode.auto
                 ? (talk.eqApp.map { "Picked for \($0)." } ?? "Changes by itself with what you play.")
                 : "Change it on the Sound page.")
                .font(.system(size: 11)).foregroundStyle(Pal.sub)
        }
    }

    /// The preset whose curve to show: the one in use (Auto shows what it picked). Nil = Stock.
    var shownEq: Eq? {
        Eq(rawValue: talk.eqMode == EqMode.auto ? (talk.eqNow ?? talk.musicEq) : talk.eqMode)
    }

    private var eqPicker: some View {
        Seg(options: EqMode.all.indices.map { (UInt8($0), EqMode.label(EqMode.all[$0])) },
            selected: EqMode.all.firstIndex(of: talk.eqMode).map { UInt8($0) }, enabled: true) { v in
            withAnimation(spring) { talk.eqMode = EqMode.all[Int(v)] }
        }
    }

    /// Menu bar: picker and the curve.
    var eqMenuCard: some View {
        Card(title: "EQ") {
            HStack {
                Text(talk.eqTitle).font(.system(size: 15, weight: .semibold)).contentTransition(.opacity)
                Spacer()
                if let e = shownEq { Text("B \(gainText(e.gains.bass)) · M \(gainText(e.gains.mid)) · T \(gainText(e.gains.treble))").font(.system(size: 10)).foregroundStyle(Pal.faint).monospacedDigit() }
            }
            eqPicker
            EqCurve(eq: shownEq, height: 74, labels: true)
        }
    }

    var eqCard: some View {
        let about: String = {
            switch talk.eqMode {
            case EqMode.auto: return "Movie while you watch something (video apps and most browsers; Zen counts as music), your music preset while you listen. It changes by itself when a different app plays."
            case EqMode.stock: return "The headphones' own factory sound."
            default: return Eq(rawValue: talk.eqMode)?.about ?? ""
            }
        }()
        return Card(title: "EQ") {
            Text(talk.eqTitle).font(.system(size: 18, weight: .semibold)).contentTransition(.opacity)
            eqPicker
            Text(about).font(.system(size: 11)).foregroundStyle(Pal.sub).fixedSize(horizontal: false, vertical: true)
            if talk.eqMode == EqMode.auto {
                Text("Music plays with").font(.system(size: 11)).foregroundStyle(Pal.sub)
                Seg(options: [(0, "Pop"), (1, "Rock")], selected: talk.musicEq == Eq.rock.rawValue ? 1 : 0, enabled: true) { v in
                    talk.musicEq = v == 1 ? Eq.rock.rawValue : Eq.pop.rawValue
                }
                if let app = talk.eqApp {
                    Text("Last playing: \(app)").font(.system(size: 10)).foregroundStyle(Pal.faint)
                }
            }
            Divider().overlay(Pal.card2)
            HStack {
                Text(shownEq.map { "\($0.label) curve" } ?? "Stock: no changes").font(.system(size: 12, weight: .medium))
                Spacer()
                if shownEq == .movie { Text("plus the Energy Slider at full").font(.system(size: 10)).foregroundStyle(Pal.faint) }
            }
            EqCurve(eq: shownEq, height: 130, labels: true)
            if let e = shownEq { EqBandList(eq: e) }
            Divider().overlay(Pal.card2)
            Text("All presets").font(.system(size: 12, weight: .medium)).foregroundStyle(Pal.sub)
            HStack(spacing: 8) {
                ForEach(Eq.allCases, id: \.self) { e in
                    let on = talk.eqMode == e.rawValue
                    VStack(alignment: .leading, spacing: 4) {
                        Text(e.label).font(.system(size: 11, weight: .medium)).foregroundStyle(on ? Pal.text : Pal.sub)
                        EqCurve(eq: e, height: 44, labels: false, dots: false)
                    }
                    .padding(8)
                    .background(RoundedRectangle(cornerRadius: 10).fill(on ? Pal.card2 : Pal.card2.opacity(0.4)))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(on ? Pal.purple : .clear, lineWidth: 1))
                    .contentShape(Rectangle())
                    .onTapGesture { withAnimation(spring) { talk.eqMode = e.rawValue } }
                    .help("Use \(e.label)")
                }
            }
        }
    }

    var syncCard: some View {
        let s = Sync.shared
        let name = s.peerName ?? "your phone"
        return Card(title: "Sync with your phone") {
            if s.peerId == nil {
                if let code = s.pairCode {
                    Text("On your phone, open Settings, then Sync, tap Pair with Mac and enter:")
                        .font(.system(size: 11)).foregroundStyle(Pal.sub).fixedSize(horizontal: false, vertical: true)
                    Text(code.prefix(3) + " " + code.suffix(3))
                        .font(.system(size: 30, weight: .semibold, design: .rounded)).monospacedDigit().tracking(3)
                    HStack {
                        Text("Works for 5 minutes. Keep both on the same Wi-Fi.").font(.system(size: 10)).foregroundStyle(Pal.faint)
                        Spacer()
                        Button("Cancel") { s.stopPairing() }
                    }
                } else {
                    Text("Syncs history, stats and every setting with the Android app over your Wi-Fi. Nothing goes to the internet, and everything is encrypted.")
                        .font(.system(size: 11)).foregroundStyle(Pal.sub).fixedSize(horizontal: false, vertical: true)
                    Button("Pair a phone") { s.startPairing() }
                }
            } else {
                HStack(spacing: 7) {
                    Circle().fill(s.connected ? Pal.teal : Pal.faint).frame(width: 7, height: 7)
                    Text(s.connected ? "Connected to \(name)" : "\(name) is not on this network").font(.system(size: 13, weight: .medium))
                }
                if let t = s.lastSync {
                    (Text("Last synced ") + Text(t, style: .relative) + Text(" ago")).font(.system(size: 11)).foregroundStyle(Pal.sub)
                }
                HStack(spacing: 6) {
                    Image(systemName: s.everythingSynced ? "checkmark.circle.fill" : "clock")
                        .foregroundStyle(s.everythingSynced ? Pal.teal : Pal.sub)
                    Text(s.waitingText ?? "Everything from this Mac is synced").font(.system(size: 12))
                }
                Text("History: \(History.shared.ownCount) events from this Mac, \(s.haveFromPeer) from \(name). Settings and stats sync too.")
                    .font(.system(size: 11)).foregroundStyle(Pal.sub).fixedSize(horizontal: false, vertical: true)
                Button("Unpair") { s.unpair() }.buttonStyle(.plain).font(.system(size: 11)).foregroundStyle(Pal.faint)
            }
            if let n = s.note {
                Text(n).font(.system(size: 11)).foregroundStyle(Pal.sub).fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    var updatesCard: some View {
        let u = Updater.shared
        return Card(title: "Updates") {
            Toggle("Update automatically", isOn: $talk.autoUpdate)
            Text("Checks GitHub every 6 hours. When there is a new version, the app replaces itself and restarts.")
                .font(.system(size: 11)).foregroundStyle(Pal.sub).fixedSize(horizontal: false, vertical: true)
            HStack {
                Text(u.status ?? "Version \(Updater.current)").font(.system(size: 11)).foregroundStyle(Pal.sub)
                Spacer()
                Button(u.busy ? "Checking..." : "Check now") { u.check(install: true) }.disabled(u.busy)
            }
        }
        .toggleStyle(.switch).tint(Pal.purple).controlSize(.small).font(.system(size: 12))
    }

    var openAppRow: some View {
        HStack(spacing: 8) {
            Button { MainWindowController.shared.show() } label: {
                HStack(spacing: 7) {
                    Image(systemName: "macwindow")
                    Text("Open app").font(.system(size: 13, weight: .semibold))
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity).frame(height: 36)
                .background(LinearGradient(colors: [Pal.purple, Pal.violet], startPoint: .leading, endPoint: .trailing))
                .clipShape(RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(Press())
            Button { NSApplication.shared.terminate(nil) } label: {
                Image(systemName: "power").font(.system(size: 13, weight: .semibold)).foregroundStyle(Pal.sub)
                    .frame(width: 36, height: 36).background(RoundedRectangle(cornerRadius: 12).fill(Pal.card))
            }
            .buttonStyle(Press()).help("Quit")
        }
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

/// The EQ curve with a soft shadow under it, a 0 dB line and a dot on each band.
/// Hover a dot to see its frequency and gain.
struct EqCurve: View {
    let eq: Eq?
    var height: CGFloat = 90
    var labels = true
    var dots = true
    private let range = 8.0

    var body: some View {
        let fs = Eq.plotFreqs
        let g = fs.map { eq?.gain(at: $0) ?? 0 }
        VStack(spacing: 3) {
            GeometryReader { geo in
                let w = geo.size.width, h = geo.size.height
                let x = { (f: Double) in CGFloat(log10(f / 20) / 3) * w }
                let y = { (db: Double) in h / 2 - CGFloat(max(-range, min(range, db)) / range) * h / 2 }
                let line = Path { p in
                    for (i, f) in fs.enumerated() {
                        let pt = CGPoint(x: x(f), y: y(g[i]))
                        if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
                    }
                }
                ZStack {
                    Path { p in
                        for db in [-6.0, 6] { p.move(to: CGPoint(x: 0, y: y(db))); p.addLine(to: CGPoint(x: w, y: y(db))) }
                        for f in [100.0, 1000, 10000] { p.move(to: CGPoint(x: x(f), y: 0)); p.addLine(to: CGPoint(x: x(f), y: h)) }
                    }
                    .stroke(Pal.card2, style: StrokeStyle(lineWidth: 1, dash: [2, 3]))
                    Path { p in p.move(to: CGPoint(x: 0, y: y(0))); p.addLine(to: CGPoint(x: w, y: y(0))) }
                        .stroke(Pal.faint.opacity(0.7), lineWidth: 1)
                    // the shadow: area between the curve and 0 dB
                    Path { p in
                        p.move(to: CGPoint(x: 0, y: y(0)))
                        for (i, f) in fs.enumerated() { p.addLine(to: CGPoint(x: x(f), y: y(g[i]))) }
                        p.addLine(to: CGPoint(x: w, y: y(0)))
                        p.closeSubpath()
                    }
                    .fill(LinearGradient(colors: [Pal.purple.opacity(0.5), Pal.purple.opacity(0.08)], startPoint: .top, endPoint: .bottom))
                    line.stroke(LinearGradient(colors: [Pal.purple, Pal.violet, Pal.pink], startPoint: .leading, endPoint: .trailing),
                                style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                    if dots, let eq {
                        ForEach(eq.bands.indices, id: \.self) { i in
                            let b = eq.bands[i]
                            Circle().fill(.white).frame(width: 6, height: 6)
                                .overlay(Circle().stroke(Pal.card, lineWidth: 2))
                                .frame(width: 14, height: 14).contentShape(Circle())
                                .position(x: x(Double(b.freq)), y: y(eq.gain(at: Double(b.freq))))
                                .help("\(b.type == 0 ? "Bass" : b.type == 1 ? "Mid" : "Treble") \(freqText(b.freq)): \(gainText(b.gain))")
                        }
                    }
                    if labels {
                        Text("+6").font(.system(size: 8)).foregroundStyle(Pal.faint).position(x: 9, y: y(6) - 6)
                        Text("-6").font(.system(size: 8)).foregroundStyle(Pal.faint).position(x: 9, y: y(-6) + 6)
                    }
                }
                .id(eq?.rawValue ?? "stock")
                .transition(.opacity)
            }
            .frame(height: height)
            if labels {
                GeometryReader { geo in
                    ForEach([(20.0, "20"), (100, "100"), (1000, "1k"), (10000, "10k"), (20000, "20k")], id: \.0) { f, t in
                        Text(t).font(.system(size: 8)).foregroundStyle(Pal.faint).monospacedDigit()
                            .position(x: min(max(CGFloat(log10(f / 20) / 3) * geo.size.width, 8), geo.size.width - 9), y: 5)
                    }
                }
                .frame(height: 10)
            }
        }
    }
}

/// Every band of a preset: frequency and gain.
struct EqBandList: View {
    let eq: Eq

    var body: some View {
        let g = eq.gains
        HStack(spacing: 6) {
            ForEach([("Bass", "140 Hz", g.bass), ("Mid", "980 Hz", g.mid), ("Treble", "3.5 kHz", g.treble)], id: \.0) { name, f, v in
                VStack(alignment: .leading, spacing: 1) {
                    Text("\(name) · \(f)").font(.system(size: 10)).foregroundStyle(Pal.sub)
                    Text(gainText(v)).font(.system(size: 14, weight: .semibold)).monospacedDigit()
                        .foregroundStyle(v >= 0 ? Pal.text : Pal.sub)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 8).padding(.vertical, 6)
                .background(RoundedRectangle(cornerRadius: 8).fill(Pal.card2))
            }
        }
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
