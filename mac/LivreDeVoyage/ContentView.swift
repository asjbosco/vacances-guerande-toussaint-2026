import SwiftUI

/// Fenêtre principale : vos histoires à gauche, l'histoire choisie à droite.
struct ContentView: View {
    @EnvironmentObject var store: Store
    @State private var creating = false
    @State private var toDelete: Story?

    var body: some View {
        NavigationSplitView {
            List(selection: $store.selectedStoryId) {
                Section("Mes histoires") {
                    ForEach(store.stories) { story in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(story.title).fontWeight(.semibold).lineLimit(2)
                            Text(["\(story.pages.count) photo(s)", story.dateRange].filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 2)
                        .tag(story.id)
                        .contextMenu {
                            Button("Dupliquer") { store.duplicate(story.id) }
                            Button("Supprimer…", role: .destructive) { toDelete = story }
                        }
                    }
                }
            }
            .navigationSplitViewColumnWidth(min: 220, ideal: 260)
            .toolbar {
                Button { creating = true } label: { Label("Nouvelle histoire", systemImage: "plus") }
            }
        } detail: {
            if let story = store.story {
                StoryView(storyId: story.id).id(story.id)
            } else {
                VStack(spacing: 12) {
                    Image(systemName: "book.pages").font(.system(size: 48)).foregroundStyle(.secondary)
                    Text("Choisissez une histoire, ou créez-en une nouvelle.")
                    Button("Nouvelle histoire") { creating = true }.buttonStyle(.borderedProminent)
                }
            }
        }
        .sheet(isPresented: $creating) { NewStorySheet() }
        .onReceive(NotificationCenter.default.publisher(for: .newStory)) { _ in creating = true }
        .confirmationDialog(
            "Supprimer « \(toDelete?.title ?? "") » ?",
            isPresented: Binding(get: { toDelete != nil }, set: { if !$0 { toDelete = nil } })
        ) {
            Button("Supprimer", role: .destructive) { if let s = toDelete { store.delete(s.id) }; toDelete = nil }
        } message: {
            Text("Le livre et ses légendes sont effacés. Les photos restent dans Photos.")
        }
        .alert(store.message ?? "", isPresented: Binding(get: { store.message != nil }, set: { if !$0 { store.message = nil } })) {
            Button("OK") { store.message = nil }
        }
    }
}

/// Nouvelle histoire : elle part de zéro.
struct NewStorySheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var region = ""
    @State private var travelers = Set<String>()

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Nouvelle histoire").font(.title2).bold()
            TextField("Titre", text: $title)
            TextField("Région (facultatif)", text: $region)
            Text("Qui était là ?").font(.headline)
            PeopleChips(people: store.people, selected: $travelers)
            HStack {
                Spacer()
                Button("Annuler") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Créer") {
                    store.createStory(title: title, region: region, travelerIds: store.people.map(\.id).filter(travelers.contains))
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .frame(width: 460)
    }
}

/// Liste de prénoms à cocher.
struct PeopleChips: View {
    let people: [Person]
    @Binding var selected: Set<String>

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), alignment: .leading)], alignment: .leading, spacing: 6) {
            ForEach(people) { p in
                Toggle(p.name, isOn: Binding(
                    get: { selected.contains(p.id) },
                    set: { on in if on { selected.insert(p.id) } else { selected.remove(p.id) } }
                ))
                .toggleStyle(.checkbox)
            }
        }
    }
}
