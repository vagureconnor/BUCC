import UIKit
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        TdNetworkBridgeInstaller.install()
        CalendarBridgeInstaller.shared.install(platform: CourseCalendarBridge())
        HomeworkNotificationBridgeInstaller.install()
        BuccNotificationPlatformInstaller.install()
        return MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.container, edges: .all)
            .ignoresSafeArea(.keyboard)
    }
}



