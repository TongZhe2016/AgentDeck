import AppKit
import SwiftUI
import Darwin

enum HelmIcon {
    static func image(size: CGFloat) -> NSImage {
        NSImage(size: NSSize(width: size, height: size), flipped: false) { _ in
            guard let context = NSGraphicsContext.current?.cgContext else { return false }
            context.scaleBy(x: size / 108, y: size / 108)
            let navy = NSColor(red: 0.082, green: 0.141, blue: 0.231, alpha: 1)
            let mint = NSColor(red: 0.337, green: 0.878, blue: 0.761, alpha: 1)
            navy.setFill()
            NSBezierPath(roundedRect: NSRect(x: 0, y: 0, width: 108, height: 108), xRadius: 24, yRadius: 24).fill()
            NSColor.white.setStroke()
            let ring = NSBezierPath(ovalIn: NSRect(x: 34.5, y: 34.5, width: 39, height: 39))
            ring.lineWidth = 4; ring.stroke()
            for index in 0..<8 {
                let angle = CGFloat(index) * .pi / 4
                let line = NSBezierPath()
                line.move(to: NSPoint(x: 54 + 20.5 * cos(angle), y: 54 + 20.5 * sin(angle)))
                line.line(to: NSPoint(x: 54 + 28 * cos(angle), y: 54 + 28 * sin(angle)))
                line.lineWidth = 4.5; line.lineCapStyle = .round
                (index == 2 ? mint : NSColor.white).setStroke(); line.stroke()
            }
            mint.setStroke()
            let terminal = NSBezierPath()
            terminal.move(to: NSPoint(x: 45.5, y: 60.5)); terminal.line(to: NSPoint(x: 52, y: 54)); terminal.line(to: NSPoint(x: 45.5, y: 47.5))
            terminal.move(to: NSPoint(x: 55.5, y: 47)); terminal.line(to: NSPoint(x: 63, y: 47))
            terminal.lineWidth = 3.6; terminal.lineCapStyle = .round; terminal.lineJoinStyle = .round; terminal.stroke()
            return true
        }
    }
}

func localAddresses() -> [String] {
    var interfaces: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&interfaces) == 0 else { return [] }
    defer { freeifaddrs(interfaces) }
    var addresses: [String] = []
    var cursor = interfaces
    while let interface = cursor {
        defer { cursor = interface.pointee.ifa_next }
        guard let address = interface.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET),
              String(cString: interface.pointee.ifa_name).hasPrefix("en") else { continue }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
            addresses.append(String(cString: host))
        }
    }
    return Array(Set(addresses)).sorted()
}

@MainActor
final class ServiceController: ObservableObject {
    @Published var running = false
    @Published var status = "正在检查服务…"
    @Published var busy = false
    @Published var error: String?
    @Published var addresses = localAddresses()
    @Published var confirmStop = false
    let dataDirectory = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".agentdeck")
    var plist: String { FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/LaunchAgents/com.worldcopy.agentdeck.plist").path }
    var domain: String { "gui/\(getuid())" }
    private var refreshing = false
    var statusChanged: (() -> Void)?

    func refresh() async {
        guard !refreshing else { return }
        refreshing = true
        defer { refreshing = false; statusChanged?() }
        addresses = localAddresses()
        do {
            let token = try String(contentsOf: dataDirectory.appendingPathComponent("token"), encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
            var request = URLRequest(url: URL(string: "http://127.0.0.1:4317/v1/health")!)
            request.timeoutInterval = 2
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            let (data, response) = try await URLSession.shared.data(for: request)
            let health = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            running = (response as? HTTPURLResponse)?.statusCode == 200 && health?["protocol"] as? Int == 1
            status = running ? "服务运行中" : "服务响应异常，请查看日志"
        } catch {
            running = false
            status = FileManager.default.fileExists(atPath: plist) ? "服务未运行" : "请先安装电脑服务"
        }
    }

    private func launchctl(_ arguments: [String]) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            DispatchQueue.global(qos: .userInitiated).async {
                let process = Process()
                process.executableURL = URL(fileURLWithPath: "/bin/launchctl")
                process.arguments = arguments
                let pipe = Pipe(); process.standardOutput = pipe; process.standardError = pipe
                do {
                    try process.run()
                    let output = pipe.fileHandleForReading.readDataToEndOfFile()
                    process.waitUntilExit()
                    if process.terminationStatus == 0 { continuation.resume() }
                    else {
                        let detail = String(data: output, encoding: .utf8) ?? "launchctl 操作失败"
                        continuation.resume(throwing: NSError(domain: "AgentDeck", code: Int(process.terminationStatus), userInfo: [NSLocalizedDescriptionKey: detail]))
                    }
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    func start() async {
        busy = true; error = nil
        defer { busy = false }
        do {
            // A loaded service can be restarted; a stopped (booted-out) service needs bootstrap.
            do { try await launchctl(["kickstart", "\(domain)/com.worldcopy.agentdeck"]) }
            catch { try await launchctl(["bootstrap", domain, plist]) }
            for _ in 0..<6 {
                await refresh()
                if running { return }
                try await Task.sleep(nanoseconds: 500_000_000)
            }
            error = "服务未能启动，请打开错误日志检查原因。"
        } catch { self.error = error.localizedDescription }
    }

    func stop() async {
        busy = true; error = nil
        defer { busy = false }
        do { try await launchctl(["bootout", "\(domain)/com.worldcopy.agentdeck"]) }
        catch { self.error = error.localizedDescription }
        await refresh()
    }

    func openLogs() { NSWorkspace.shared.open(dataDirectory) }
    func copyConnection() {
        let text = "SSH 地址：\(addresses.joined(separator: " / "))\nSSH 端口：22\n用户名：\(NSUserName())\n服务目录：~/.agentdeck"
        NSPasteboard.general.clearContents(); NSPasteboard.general.setString(text, forType: .string)
    }
}

struct Dashboard: View {
    @ObservedObject var service: ServiceController
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(spacing: 14) {
                Image(nsImage: HelmIcon.image(size: 64)).resizable().frame(width: 64, height: 64)
                VStack(alignment: .leading, spacing: 5) {
                    Text("掌舵 · 电脑服务").font(.title2.bold())
                    Text("让手机继续这台 Mac 上的工作").foregroundStyle(.secondary)
                }
            }
            GroupBox {
                HStack {
                    Circle().fill(service.running ? Color.green : Color.secondary).frame(width: 9, height: 9)
                    Text(service.status).font(.headline)
                    Spacer()
                    Button(service.running ? "停止服务" : "启动服务") {
                        if service.running { service.confirmStop = true }
                        else { Task { await service.start() } }
                    }.disabled(service.busy)
                }.padding(8)
            }
            GroupBox("手机连接") {
                VStack(alignment: .leading, spacing: 12) {
                    LabeledContent("SSH 地址", value: service.addresses.isEmpty ? "当前没有局域网地址" : service.addresses.joined(separator: " / "))
                    LabeledContent("SSH 端口", value: "22")
                    LabeledContent("用户名", value: NSUserName())
                    Divider()
                    Text("在系统设置 → 通用 → 共享中开启“远程登录”。手机添加主机后，选择密码或 SSH 密钥登录。")
                        .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    Button("复制连接信息", action: service.copyConnection)
                }.padding(8).textSelection(.enabled)
            }
            Text("Codex 使用电脑已有认证。服务令牌由手机通过 SSH 自动获取。后台服务随登录启动，退出菜单栏 App 后仍可继续运行。")
                .font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            if let error = service.error { Text(error).foregroundStyle(.red).textSelection(.enabled) }
            HStack {
                Button("打开日志", action: service.openLogs)
                Spacer()
                Button("刷新状态") { Task { await service.refresh() } }.disabled(service.busy)
            }
        }.padding(24).frame(width: 500)
            .alert("停止电脑服务？", isPresented: $service.confirmStop) {
                Button("取消", role: .cancel) {}
                Button("停止服务", role: .destructive) { Task { await service.stop() } }
            } message: { Text("正在执行的电脑任务可能中断。手机断开连接时无需停止服务。") }
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let service = ServiceController()
    var item: NSStatusItem!
    var statusLine: NSMenuItem!
    var startStop: NSMenuItem!
    var window: NSWindow!
    var timer: Timer?

    func applicationDidFinishLaunching(_ notification: Notification) {
        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        item.button?.image = NSImage(systemSymbolName: "terminal", accessibilityDescription: "掌舵电脑服务")
        let menu = NSMenu()
        statusLine = menu.addItem(withTitle: service.status, action: nil, keyEquivalent: "")
        menu.addItem(.separator())
        add("打开控制台…", #selector(showDashboard), to: menu)
        startStop = add("启动服务", #selector(toggleService), to: menu)
        add("复制手机连接信息", #selector(copyConnection), to: menu)
        add("打开日志…", #selector(openLogs), to: menu)
        menu.addItem(.separator())
        add("退出菜单栏 App", #selector(quit), to: menu)
        item.menu = menu
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 548, height: 560), styleMask: [.titled, .closable, .miniaturizable], backing: .buffered, defer: false)
        window.title = "掌舵 · AgentDeck Server"; window.isReleasedWhenClosed = false
        window.contentViewController = NSHostingController(rootView: Dashboard(service: service))
        window.center()
        service.statusChanged = { [weak self] in
            guard let self else { return }
            self.statusLine.title = self.service.status
            self.startStop.title = self.service.running ? "停止服务…" : "启动服务"
            self.item.button?.toolTip = "掌舵：\(self.service.status)"
        }
        timer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.service.refresh() }
        }
        Task { await service.refresh() }
        if !CommandLine.arguments.contains("--background") { showDashboard() }
    }
    @discardableResult private func add(_ title: String, _ action: Selector, to menu: NSMenu) -> NSMenuItem {
        let entry = menu.addItem(withTitle: title, action: action, keyEquivalent: ""); entry.target = self; return entry
    }
    @objc func showDashboard() { NSApp.activate(ignoringOtherApps: true); window.makeKeyAndOrderFront(nil) }
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        showDashboard(); return true
    }
    @objc func copyConnection() { service.copyConnection() }
    @objc func openLogs() { service.openLogs() }
    @objc func toggleService() {
        guard !service.busy else { return }
        if service.running {
            let alert = NSAlert(); alert.messageText = "停止电脑服务？"
            alert.informativeText = "正在执行的电脑任务可能中断。手机断开连接时无需停止服务。"
            alert.addButton(withTitle: "取消"); alert.addButton(withTitle: "停止服务")
            if alert.runModal() == .alertSecondButtonReturn { Task { await service.stop() } }
        } else { Task { await service.start() } }
    }
    @objc func quit() { NSApp.terminate(nil) }
}

@main
struct AgentDeckLauncher {
    @MainActor static func main() throws {
        if CommandLine.arguments.dropFirst() == ["--quit"] {
            let applications = NSRunningApplication.runningApplications(withBundleIdentifier: "com.worldcopy.agentdeck.server")
                .filter { $0.processIdentifier != getpid() }
            applications.forEach { _ = $0.terminate() }
            let deadline = Date().addingTimeInterval(3)
            while applications.contains(where: { !$0.isTerminated }) && Date() < deadline {
                RunLoop.current.run(until: Date().addingTimeInterval(0.1))
            }
            if applications.contains(where: { !$0.isTerminated }) {
                throw NSError(domain: "AgentDeck", code: 1, userInfo: [NSLocalizedDescriptionKey: "请先退出正在运行的 AgentDeck 菜单栏 App。"])
            }
        } else if CommandLine.arguments.count == 3 && CommandLine.arguments[1] == "--write-icon" {
            let folder = URL(fileURLWithPath: CommandLine.arguments[2])
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            for size in [16, 32, 128, 256, 512] {
                for scale in [1, 2] {
                    let image = HelmIcon.image(size: CGFloat(size * scale))
                    let bitmap = NSBitmapImageRep(data: image.tiffRepresentation!)!
                    let png = bitmap.representation(using: .png, properties: [:])!
                    try png.write(to: folder.appendingPathComponent("icon_\(size)x\(size)\(scale == 2 ? "@2x" : "").png"))
                }
            }
        } else {
            let app = NSApplication.shared
            let delegate = AppDelegate()
            app.delegate = delegate; app.setActivationPolicy(.accessory); app.run()
        }
    }
}
