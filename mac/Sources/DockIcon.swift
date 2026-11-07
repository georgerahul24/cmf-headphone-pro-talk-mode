import AppKit

/// The app icon, drawn live in the Dock: the sound-wave bars bounce while audio plays (faster while
/// talking) and rest when it's quiet. Same design as AppIcon.icns. Only runs while the window is
/// open, since that's the only time the app is in the Dock.
final class DockIcon {
    static let shared = DockIcon()
    private var timer: Timer?
    private weak var talk: TalkMode?
    private let view = DockIconView(frame: NSRect(x: 0, y: 0, width: 128, height: 128))
    private var phase = 0.0
    private var level = 0.0 // 0 = resting, 1 = full bounce; eases between the two

    func start(_ talk: TalkMode) {
        self.talk = talk
        NSApp.dockTile.contentView = view
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0 / 15, repeats: true) { [weak self] _ in self?.tick() }
        timer?.tolerance = 0.02
        tick()
    }

    func stop() {
        timer?.invalidate(); timer = nil
        NSApp.dockTile.contentView = nil
        NSApp.dockTile.display()
    }

    private func tick() {
        guard let talk else { return }
        let target = talk.active || talk.playing ? 1.0 : 0.0
        if target == 0 && level < 0.01 { if level != 0 { level = 0; redraw() }; return } // still: nothing to do
        level += (target - level) * 0.15
        phase += talk.active ? 0.55 : 0.35
        redraw()
    }

    private func redraw() {
        // Each bar moves on its own wave, so it looks like a live sound meter.
        view.scales = (0..<5).map { i in
            let wave = (sin(phase + Double(i) * 1.3) + sin(phase * 0.7 + Double(i) * 2.1)) / 4 + 0.5 // 0...1
            return 1 - level * 0.55 + level * 0.55 * wave * 1.6
        }
        NSApp.dockTile.display()
    }
}

final class DockIconView: NSView {
    /// Height of each bar compared with the still icon.
    var scales: [Double] = [1, 1, 1, 1, 1]

    override func draw(_ dirtyRect: NSRect) {
        guard let g = NSGraphicsContext.current?.cgContext else { return }
        let s = bounds.width / 1024
        g.saveGState()
        g.scaleBy(x: s, y: s)
        g.translateBy(x: 0, y: 1024); g.scaleBy(x: 1, y: -1)
        AppIconArt.draw(g, bars: scales)
        g.restoreGState()
    }
}

/// The icon artwork on a 1024 canvas (macOS icon grid: an 824 square at 100), y pointing down.
enum AppIconArt {
    static let bars: [(x: CGFloat, h: CGFloat)] = [(392, 96), (452, 176), (512, 250), (572, 176), (632, 96)]

    private static func c(_ hex: UInt32, _ a: CGFloat = 1) -> CGColor {
        CGColor(srgbRed: CGFloat(hex >> 16 & 0xFF) / 255, green: CGFloat(hex >> 8 & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: a)
    }

    static func draw(_ g: CGContext, bars scales: [Double]) {
        let cs = CGColorSpace(name: CGColorSpace.sRGB)!
        let square = CGPath(roundedRect: CGRect(x: 100, y: 100, width: 824, height: 824), cornerWidth: 185, cornerHeight: 185, transform: nil)
        g.saveGState(); g.addPath(square); g.clip()
        let bg = CGGradient(colorsSpace: cs, colors: [c(0x2A2347), c(0x0E0D15)] as CFArray, locations: [0, 1])!
        g.drawLinearGradient(bg, start: CGPoint(x: 250, y: 100), end: CGPoint(x: 774, y: 924),
                             options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
        func glow(_ hex: UInt32, _ a: CGFloat, _ x: CGFloat, _ y: CGFloat, _ r: CGFloat) {
            let gr = CGGradient(colorsSpace: cs, colors: [c(hex, a), c(hex, 0)] as CFArray, locations: [0, 1])!
            g.drawRadialGradient(gr, startCenter: CGPoint(x: x, y: y), startRadius: 0, endCenter: CGPoint(x: x, y: y), endRadius: r, options: [])
        }
        glow(0x8B5CF6, 0.75, 640, 330, 400)
        glow(0xEC5C9A, 0.45, 360, 730, 340)
        let panel = CGPath(roundedRect: CGRect(x: 232, y: 302, width: 560, height: 420), cornerWidth: 132, cornerHeight: 132, transform: nil)
        g.saveGState(); g.addPath(panel); g.clip()
        let glass = CGGradient(colorsSpace: cs, colors: [c(0xFFFFFF, 0.22), c(0xFFFFFF, 0.06)] as CFArray, locations: [0, 1])!
        g.drawLinearGradient(glass, start: CGPoint(x: 232, y: 302), end: CGPoint(x: 792, y: 722), options: [])
        g.restoreGState()
        g.addPath(panel); g.setStrokeColor(c(0xFFFFFF, 0.35)); g.setLineWidth(4); g.strokePath()
        g.setFillColor(c(0xFFFFFF, 0.96))
        for (i, b) in bars.enumerated() {
            let h = max(38, min(380, b.h * CGFloat(i < scales.count ? scales[i] : 1)))
            g.addPath(CGPath(roundedRect: CGRect(x: b.x - 19, y: 512 - h / 2, width: 38, height: h), cornerWidth: 19, cornerHeight: 19, transform: nil))
            g.fillPath()
        }
        g.restoreGState()
    }
}
