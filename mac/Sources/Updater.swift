import AppKit

/// Updates from GitHub Releases: checks the latest release, downloads the Mac zip,
/// swaps this app for the new one and opens it.
final class Updater: ObservableObject {
    static let shared = Updater()
    private let api = URL(string: "https://api.github.com/repos/georgerahul24/cmf-headphone-pro-talk-mode/releases/latest")!

    @Published private(set) var status: String?
    @Published private(set) var busy = false

    static var current: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0" }

    /// Newer if any number is bigger, left to right: 2.10.0 > 2.9.3.
    static func newer(_ a: String, than b: String) -> Bool {
        let x = a.split(separator: ".").map { Int($0) ?? 0 }, y = b.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(x.count, y.count) {
            let d = (i < x.count ? x[i] : 0) - (i < y.count ? y[i] : 0)
            if d != 0 { return d > 0 }
        }
        return false
    }

    private func set(_ s: String?, done: Bool = false) {
        DispatchQueue.main.async { self.status = s; if done { self.busy = false } }
    }

    /// Checks GitHub; with `install`, downloads and installs a newer version.
    func check(install: Bool) {
        guard !busy else { return }
        busy = true
        status = "Checking..."
        Task.detached { [self] in
            do {
                var req = URLRequest(url: api, timeoutInterval: 20)
                req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
                req.setValue("cmf-talk-mode", forHTTPHeaderField: "User-Agent")
                let (data, _) = try await URLSession.shared.data(for: req)
                guard let j = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let tag = (j["tag_name"] as? String)?.replacingOccurrences(of: "v", with: "") else { throw URLError(.badServerResponse) }
                guard Self.newer(tag, than: Self.current) else { set("Up to date (\(Self.current))", done: true); return }
                let assets = j["assets"] as? [[String: Any]] ?? []
                guard let a = assets.first(where: { ($0["name"] as? String)?.hasSuffix("-mac.zip") == true }),
                      let urlStr = a["browser_download_url"] as? String, let url = URL(string: urlStr) else {
                    set("Version \(tag) is out, but has no Mac download yet", done: true); return
                }
                guard install else { set("Version \(tag) is available", done: true); return }
                set("Downloading \(tag)...")
                var dl = URLRequest(url: url, timeoutInterval: 120)
                dl.setValue("cmf-talk-mode", forHTTPHeaderField: "User-Agent")
                let (zip, resp) = try await URLSession.shared.download(for: dl)
                if let size = a["size"] as? Int, (resp as? HTTPURLResponse)?.statusCode == 200,
                   let got = (try? FileManager.default.attributesOfItem(atPath: zip.path)[.size]) as? Int, got != size {
                    set("Download was cut off. Will try again later.", done: true); return
                }
                try await MainActor.run { try self.replaceAndRelaunch(zip: zip, tag: tag) }
            } catch {
                log("update: \(error)")
                set("Couldn't check for updates. Will try again later.", done: true)
            }
        }
    }

    /// Unzips the new app, puts it where this one is and opens it. Then this one quits.
    @MainActor private func replaceAndRelaunch(zip: URL, tag: String) throws {
        let fm = FileManager.default
        let dir = fm.temporaryDirectory.appendingPathComponent("cmf-update-\(tag)")
        try? fm.removeItem(at: dir)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let unzip = Process()
        unzip.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        unzip.arguments = ["-x", "-k", zip.path, dir.path]
        try unzip.run(); unzip.waitUntilExit()
        let new = dir.appendingPathComponent("CMF Talk Mode.app")
        guard unzip.terminationStatus == 0,
              let v = Bundle(url: new)?.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String, v == tag else {
            set("Update file looked wrong. Will try again later.", done: true); return
        }
        let dest = Bundle.main.bundleURL
        _ = try fm.replaceItemAt(dest, withItemAt: new)
        set("Restarting...")
        log("updated to \(tag), relaunching")
        let cfg = NSWorkspace.OpenConfiguration()
        cfg.createsNewApplicationInstance = true
        NSWorkspace.shared.openApplication(at: dest, configuration: cfg) { _, _ in
            DispatchQueue.main.async { NSApplication.shared.terminate(nil) }
        }
    }
}
