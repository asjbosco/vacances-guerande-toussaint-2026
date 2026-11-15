import AppKit
import SwiftUI

/// Avant de rédiger : d'où viennent les légendes ?
struct CaptionSourceSheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    let story: Story
    @State private var source: CaptionSource = .wikipedia
    @State private var rewrite = false

    var body: some View {
        let done = story.pages.filter { $0.status == .done }.count
        VStack(alignment: .leading, spacing: 14) {
            Text("D'où viennent les légendes ?").font(.title2).bold()
            Text("Dans les deux cas, la légende part de la position GPS de la photo (ou du lieu que vous avez indiqué).")
                .foregroundStyle(.secondary)
            Picker("", selection: $source) {
                VStack(alignment: .leading) {
                    Text("Sources libres (Wikipédia)").bold()
                    Text("Gratuit. Texte repris de l'article le plus proche, raccourci.").font(.caption)
                }.tag(CaptionSource.wikipedia)
                VStack(alignment: .leading) {
                    Text("Claude (Anthropic)").bold()
                    Text("La photo et les articles du lieu sont envoyés à Claude, qui rédige un texte court. Clé API nécessaire, ~1 centime par photo."
                         + (store.apiKeySet ? "" : "\n⚠️ Aucune clé enregistrée (Réglages)."))
                        .font(.caption)
                }.tag(CaptionSource.claude)
            }
            .pickerStyle(.radioGroup)
            .labelsHidden()
            if done > 0 {
                Toggle("Réécrire aussi les \(done) légende(s) existante(s)", isOn: $rewrite)
            }
            HStack {
                Spacer()
                Button("Annuler") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Rédiger") {
                    let id = story.id
                    let chosen = source
                    let all = rewrite
                    dismiss()
                    Task { await store.writeCaptions(id, source: chosen, rewrite: all) }
                }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .frame(width: 520)
        .onAppear {
            source = store.captionSource
            rewrite = done == story.pages.count
        }
    }
}

/// Livre en PDF puis partage (AirDrop, Messages, Mail, WhatsApp…).
struct PDFSheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    let storyId: String
    @State private var quality: PDFQuality = .high
    @State private var url: URL?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Livre en PDF").font(.title2).bold()
            Text("Couverture, puis une page A4 par photo. Les photos sont reprises de Photos en pleine résolution.")
                .foregroundStyle(.secondary)
            Picker("Qualité des photos", selection: $quality) {
                ForEach(PDFQuality.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.radioGroup)
            if let busy = store.busy { HStack { ProgressView().controlSize(.small); Text(busy) } }
            if let url {
                Text("✅ PDF prêt : \(url.lastPathComponent)")
                HStack {
                    ShareLink(item: url) { Label("Partager…", systemImage: "square.and.arrow.up") }
                    Button("Afficher dans le Finder") { NSWorkspace.shared.activateFileViewerSelecting([url]) }
                    Button("Ouvrir") { NSWorkspace.shared.open(url) }
                }
                Text("AirDrop, Messages, Mail ou WhatsApp : le PDF part comme document, sans recompression.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            HStack {
                Spacer()
                Button("Fermer") { dismiss() }.keyboardShortcut(.cancelAction)
                Button(url == nil ? "Créer le PDF" : "Recréer") {
                    Task { url = await store.exportPDF(storyId, quality: quality) }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(store.busy != nil)
            }
        }
        .padding(24)
        .frame(width: 520)
    }
}

/// Titre, région, voyageurs et distance des photos voisines.
struct StoryInfoSheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    let story: Story
    @State private var title = ""
    @State private var region = ""
    @State private var radius = 300.0
    @State private var travelers = Set<String>()

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Infos de l'histoire").font(.title2).bold()
            TextField("Titre", text: $title)
            TextField("Région visitée (facultatif)", text: $region)
            Text("Qui était du voyage ? (affiché sur la couverture)").font(.headline)
            PeopleChips(people: store.people, selected: $travelers)
            HStack {
                Text("Photos voisines : même histoire en dessous de")
                TextField("", value: $radius, format: .number).frame(width: 70)
                Text("mètres")
            }
            HStack {
                Spacer()
                Button("Annuler") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Enregistrer") {
                    store.edit(story.id) { s in
                        s.title = title.isEmpty ? s.title : title
                        s.region = region
                        s.travelerIds = store.people.map(\.id).filter(travelers.contains)
                        s.radius = max(0, radius)
                    }
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .frame(width: 480)
        .onAppear {
            title = story.title
            region = story.region
            radius = story.radius
            travelers = Set(story.travelerIds)
        }
    }
}

/// Feuilleter le livre : couverture puis une page par photo (flèches ← →).
struct BookViewer: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    let story: Story
    @State private var index = 0

    var body: some View {
        VStack(spacing: 0) {
            Group {
                if index == 0 { cover } else { page(story.pages[index - 1]) }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            HStack {
                Button { index = max(0, index - 1) } label: { Image(systemName: "chevron.left") }
                    .keyboardShortcut(.leftArrow, modifiers: []).disabled(index == 0)
                Spacer()
                Text(index == 0 ? "Couverture" : "Page \(index) / \(story.pages.count)").foregroundStyle(.secondary)
                Spacer()
                Button { index = min(story.pages.count, index + 1) } label: { Image(systemName: "chevron.right") }
                    .keyboardShortcut(.rightArrow, modifiers: []).disabled(index == story.pages.count)
                Button("Fermer") { dismiss() }.keyboardShortcut(.cancelAction)
            }
            .padding(12)
        }
        .frame(width: 900, height: 720)
    }

    private var cover: some View {
        VStack(spacing: 10) {
            if let first = story.pages.first { Thumbnail(assetId: first.assetId, side: 1600, fill: false).frame(height: 420) }
            Text(story.title).font(.system(size: 30, weight: .bold, design: .serif)).foregroundStyle(.teal)
            if !story.region.isEmpty { Text(story.region).font(.system(.title3, design: .serif)) }
            if !story.dateRange.isEmpty { Text(story.dateRange).italic().foregroundStyle(.secondary) }
            let travelers = store.people.filter { story.travelerIds.contains($0.id) }
            if !travelers.isEmpty { Text("Avec " + travelers.map(\.name).joined(separator: ", ")).italic() }
        }
        .padding()
    }

    private func page(_ page: Page) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Thumbnail(assetId: page.assetId, side: 1600, fill: false).id(page.id)
            Text(page.place.isEmpty ? "—" : page.place).font(.system(size: 22, weight: .bold, design: .serif)).foregroundStyle(.teal).lineLimit(2)
            if !page.summary.isEmpty { Text(page.summary).font(.system(size: 16, design: .serif)).lineLimit(2) }
            let footer = CaptionFormat.footer(page, store.people)
            if !footer.isEmpty { Text(footer).italic().foregroundStyle(.secondary) }
        }
        .padding(20)
    }
}

/// Réglages : proches, clé API, photothèque.
struct SettingsView: View {
    @EnvironmentObject var store: Store
    @State private var newName = ""
    @State private var key = ""

    var body: some View {
        TabView {
            Form {
                Section("Famille et proches (communs à toutes les histoires)") {
                    List {
                        ForEach(store.people) { p in
                            HStack {
                                Text(p.name)
                                if p.photosId != nil { Text("· Photos").font(.caption).foregroundStyle(.secondary) }
                                Spacer()
                                Button(role: .destructive) { store.removePerson(p.id) } label: { Image(systemName: "minus.circle") }
                                    .buttonStyle(.borderless)
                            }
                        }
                    }
                    .frame(height: 220)
                    HStack {
                        TextField("Prénom (ou nom)", text: $newName)
                        Button("Ajouter") { store.addPerson(newName); newName = "" }.disabled(newName.isEmpty)
                    }
                    Text("Les personnes nommées dans « Personnes » de Photos sont ajoutées automatiquement.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .tabItem { Label("Proches", systemImage: "person.2") }

            Form {
                Section("Claude (Anthropic)") {
                    Text(store.apiKeySet ? "✅ Une clé API est enregistrée dans le trousseau du Mac." : "Aucune clé API enregistrée.")
                    SecureField("Clé API (sk-ant-…)", text: $key)
                    HStack {
                        Button("Enregistrer la clé") { store.saveApiKey(key); key = "" }.disabled(key.isEmpty)
                        if store.apiKeySet { Button("Effacer") { store.saveApiKey("") } }
                    }
                    Text("Clé à créer sur console.anthropic.com (rubrique API Keys). ~1 centime par photo.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section("Photothèque (pour les noms des Personnes)") {
                    Text(store.libraryPath).font(.caption).textSelection(.enabled)
                    Button("Choisir une autre photothèque…") {
                        let panel = NSOpenPanel()
                        panel.canChooseFiles = true
                        panel.canChooseDirectories = true
                        panel.treatsFilePackagesAsDirectories = false
                        panel.directoryURL = FileManager.default.urls(for: .picturesDirectory, in: .userDomainMask).first
                        if panel.runModal() == .OK, let url = panel.url { store.libraryPath = url.path }
                    }
                    Text("Lire les noms des Personnes demande l'« Accès complet au disque » : Réglages Système › Confidentialité et sécurité › Accès complet au disque › ajoutez « Livre de voyage ».")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .tabItem { Label("Légendes et Photos", systemImage: "gearshape") }
        }
        .padding(20)
        .frame(width: 560, height: 460)
    }
}
