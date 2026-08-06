import SwiftUI
import Shared

/// Phase 1 smoke test: proves `Shared.framework` actually embeds and links, and that
/// the `readBundledAsset` iOS actual (NSBundle-backed, previously unverified — see
/// README's "iOS port" section) can find and read a resource bundled into this target.
/// Not a real screen yet; superseded once the sign-in screen (Phase 4) lands.
struct ContentView: View {
    @State private var status: String = "Loading..."

    var body: some View {
        VStack(spacing: 12) {
            Text("Car Companion").font(.title)
            Text(status).font(.caption).multilineTextAlignment(.center).padding()
        }
        .padding()
        .onAppear(perform: runSmokeTest)
    }

    private func runSmokeTest() {
        let context = PlatformContext()
        let json = readBundledAsset(context: context, name: "departments_centroids.json")
        status = "Shared.framework linked.\ndepartments_centroids.json: \(json.count) chars"
    }
}
