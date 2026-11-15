import SwiftUI

@main
struct LivreDeVoyageApp: App {
    @StateObject private var store = Store()

    var body: some Scene {
        WindowGroup("Livre de voyage") {
            ContentView()
                .environmentObject(store)
                .frame(minWidth: 1000, minHeight: 640)
        }
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("Nouvelle histoire") { NotificationCenter.default.post(name: .newStory, object: nil) }
                    .keyboardShortcut("n")
            }
        }
        Settings {
            SettingsView().environmentObject(store)
        }
    }
}

extension Notification.Name {
    static let newStory = Notification.Name("nouvelleHistoire")
}
