// Sync LAN con el peerServer de la PC/Android (src/net/peerServer.js). Cada 4s:
//   (a) POST http://<peerHost>/peers  -> body {screenWidth, peers:[{id,displayName,device,x,y,lastSay}]}
//   (b) GET  http://<peerHost>/peers  -> {screenWidth, peers:[...]} guardado en remotePeers.
// Cualquier fallo se ignora en silencio.

import Foundation
import SwiftUI

@MainActor
final class PeerClient: ObservableObject {

  struct RemotePeer: Identifiable {
    let id: String
    let displayName: String
    let x: CGFloat
    let y: CGFloat
    let lastSay: String?
    let device: String?
  }

  @Published private(set) var remotePeers: [RemotePeer] = []

  // La World la provee cada tick para mandar el estado actual de los locales.
  var localPeersProvider: (() -> [[String: Any]]) = { [] }
  var screenWidthProvider: (() -> CGFloat) = { 0 }

  private let syncInterval: TimeInterval = 4.0
  private var timer: Timer?
  private let session: URLSession

  init(host: String?) {
    self.host = host
    let config = URLSessionConfiguration.ephemeral
    config.timeoutIntervalForRequest = 4
    config.waitsForConnectivity = false
    self.session = URLSession(configuration: config)
  }

  private var host: String?

  func setHost(_ newHost: String?) {
    host = newHost
  }

  func start() {
    guard timer == nil else { return }
    syncNow()
    let t = Timer(timeInterval: syncInterval, target: self, selector: #selector(syncTick),
                  userInfo: nil, repeats: true)
    RunLoop.main.add(t, forMode: .common)
    timer = t
  }

  func stop() {
    timer?.invalidate()
    timer = nil
  }

  @objc private func syncTick() {
    syncNow()
  }

  private func syncNow() {
    guard let value = host, let url = URL(string: value) else { return }
    postPeers(to: url)
    fetchPeers(from: url)
  }

  private func postPeers(to baseURL: URL) {
    let url = baseURL.appendingPathComponent("peers")
    let peers = localPeersProvider()
    let screenWidth = Double(screenWidthProvider())
    let body: [String: Any] = ["screenWidth": screenWidth, "peers": peers]
    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.httpBody = try? JSONSerialization.data(withJSONObject: body)
    session.dataTask(with: request) { _, _, _ in }.resume()
  }

  private func fetchPeers(from baseURL: URL) {
    let url = baseURL.appendingPathComponent("peers")
    let task = session.dataTask(with: URLRequest(url: url)) { [weak self] data, response, _ in
      guard let self, let data else { return }
      if let http = response as? HTTPURLResponse, http.statusCode != 200 { return }
      do {
        let json = try JSONSerialization.jsonObject(with: data)
        let peersArray = Self.extractPeersArray(json)
        let parsed = Self.parsePeers(peersArray)
        Task { @MainActor in
          self.remotePeers = parsed
        }
      } catch {
        // payload invalido: ignorar
      }
    }
    task.resume()
  }

  // Acepta tanto {"peers":[...]} como un array pelado.
  static func extractPeersArray(_ json: Any) -> [[String: Any]] {
    if let arr = json as? [[String: Any]] { return arr }
    if let dict = json as? [String: Any], let arr = dict["peers"] as? [[String: Any]] { return arr }
    return []
  }

  static func parsePeers(_ arr: [[String: Any]]) -> [RemotePeer] {
    arr.compactMap { p in
      guard let id = p["id"] as? String else { return nil }
      let displayName = p["displayName"] as? String ?? id
      let x = number(p["x"]) ?? 0
      let y = number(p["y"]) ?? 0
      let lastSay = p["lastSay"] as? String
      let device = p["device"] as? String
      return RemotePeer(id: id, displayName: displayName, x: CGFloat(x),
                        y: CGFloat(y), lastSay: lastSay, device: device)
    }
  }

  private static func number(_ value: Any?) -> Double? {
    if let n = value as? Double { return n }
    if let i = value as? Int { return Double(i) }
    if let f = value as? Float { return Double(f) }
    return nil
  }
}