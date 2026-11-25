import RemaplyNavigationCore
import RemaplyNavigationUI
import SwiftUI

struct ContentView: View {
    @ObservedObject var navigator: RemaplyNavigator

    var body: some View {
        switch navigator.state.status {
        case .navigating, .arrived:
            RemaplyNavigationView(
                navigator: navigator,
                style: ExampleConfig.mapStyle
            )
                .ignoresSafeArea()
        case .idle, .routing:
            TripForm(navigator: navigator)
        }
    }
}

/// Pick a trip. The defaults are a short drive in Suva, Fiji.
private struct TripForm: View {
    @ObservedObject var navigator: RemaplyNavigator
    @State private var origin = "-18.1416, 178.4419"
    @State private var destination = "-18.1250, 178.4500"
    @State private var simulate = true
    @State private var error: String?
    @State private var events: [String] = []

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Simulate the drive", isOn: $simulate)
                    if simulate {
                        TextField("Origin (lat, lon)", text: $origin)
                    }
                    TextField("Destination (lat, lon)", text: $destination)
                } footer: {
                    Text(ExampleConfig.remaplyAPIKey.isEmpty ? "Routes: \(ExampleConfig.valhallaURL.absoluteString)" : "Routes and map: Remaply API")
                }

                Section {
                    Button(navigator.state.status == .routing ? "Finding route…" : "Start navigation", action: start)
                        .disabled(navigator.state.status == .routing)
                    if let error {
                        Text(error).foregroundStyle(.red)
                    }
                }

                if !events.isEmpty {
                    Section("Last trip's events") {
                        ForEach(Array(events.enumerated()), id: \.offset) { Text($0.element).font(.caption) }
                    }
                }
            }
            .navigationTitle("Remaply Navigation")
        }
        .task {
            for await event in navigator.events() {
                events.insert(describe(event), at: 0)
                events = Array(events.prefix(20))
            }
        }
    }

    private func start() {
        guard let to = parse(destination) else { error = "Destination must be \"lat, lon\""; return }
        let from = simulate ? parse(origin).map { UserLocation(coordinate: $0, horizontalAccuracy: 5) } : nil
        error = nil
        Task {
            do {
                try await navigator.navigate(to: [Waypoint(coordinate: to)], simulate: simulate, origin: from)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func parse(_ text: String) -> Coordinate? {
        let parts = text.split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
        guard parts.count == 2, (-90 ... 90).contains(parts[0]), (-180 ... 180).contains(parts[1]) else { return nil }
        return Coordinate(latitude: parts[0], longitude: parts[1])
    }

    private func describe(_ event: NavigationEvent) -> String {
        switch event {
        case let .navigationStarted(route, simulated): "Started (\(Int(route.distance)) m\(simulated ? ", simulated" : ""))"
        case let .stepAdvanced(instruction, _): "Next: \(instruction?.primaryText ?? "-")"
        case .offRoute: "Off route"
        case .backOnRoute: "Back on route"
        case let .rerouted(route): "Rerouted (\(Int(route.distance)) m)"
        case let .waypointReached(_, index): "Reached stop \(index + 1)"
        case .arrived: "Arrived"
        case .navigationStopped: "Stopped"
        case let .error(error): "Error: \(error.localizedDescription)"
        }
    }
}
