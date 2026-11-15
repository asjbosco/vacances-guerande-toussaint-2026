import PhotosUI
import SwiftUI

/// Une histoire : la liste de ses photos à gauche, la légende de la photo choisie à droite.
struct StoryView: View {
    @EnvironmentObject var store: Store
    let storyId: String

    @State private var selection = Set<String>()
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var askSource = false
    @State private var showPDF = false
    @State private var showInfo = false
    @State private var showBook = false
    @State private var confirmDelete = false

    private var story: Story? { store.stories.first { $0.id == storyId } }

    var body: some View {
        if let story {
            VStack(spacing: 0) {
                if let busy = store.busy {
                    HStack { ProgressView().controlSize(.small); Text(busy) }
                        .frame(maxWidth: .infinity).padding(6).background(.yellow.opacity(0.15))
                }
                if story.pages.isEmpty {
                    emptyState
                } else {
                    HSplitView {
                        pageList(story).frame(minWidth: 340, idealWidth: 420)
                        detail(story).frame(minWidth: 420)
                    }
                }
            }
            .navigationTitle(story.title)
            .toolbar { toolbar(story) }
            .onChange(of: pickerItems) { items in
                let ids = items.compactMap(\.itemIdentifier)
                pickerItems = []
                guard !ids.isEmpty else { return }
                Task { await store.addPhotos(ids, to: storyId) }
            }
            .sheet(isPresented: $askSource) { CaptionSourceSheet(story: story) }
            .sheet(isPresented: $showPDF) { PDFSheet(storyId: storyId) }
            .sheet(isPresented: $showInfo) { StoryInfoSheet(story: story) }
            .sheet(isPresented: $showBook) { BookViewer(story: story) }
            .confirmationDialog("Retirer \(selection.count) photo(s) du livre ?", isPresented: $confirmDelete) {
                Button("Retirer", role: .destructive) {
                    store.removePages(selection, from: storyId)
                    selection = []
                }
            } message: { Text("Les photos restent dans Photos.") }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "photo.on.rectangle.angled").font(.system(size: 48)).foregroundStyle(.secondary)
            Text("Ce livre est vide.").font(.title3)
            Text("Ajoutez des photos depuis la photothèque, ou sélectionnez-les dans l'application Photos puis importez la sélection.")
                .multilineTextAlignment(.center).foregroundStyle(.secondary).frame(maxWidth: 420)
            HStack {
                PhotosPicker(selection: $pickerItems, matching: .images, photoLibrary: .shared()) {
                    Label("Choisir dans la photothèque", systemImage: "photo.badge.plus")
                }
                .buttonStyle(.borderedProminent)
                Button { Task { await store.importPhotosAppSelection(to: storyId) } } label: {
                    Label("Importer la sélection de Photos", systemImage: "square.and.arrow.down")
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    @ToolbarContentBuilder
    private func toolbar(_ story: Story) -> some ToolbarContent {
        ToolbarItemGroup {
            PhotosPicker(selection: $pickerItems, matching: .images, photoLibrary: .shared()) {
                Label("Ajouter des photos", systemImage: "photo.badge.plus")
            }
            .help("Choisir des photos dans la photothèque")
            Button { Task { await store.importPhotosAppSelection(to: storyId) } } label: {
                Label("Sélection de Photos", systemImage: "square.and.arrow.down")
            }
            .help("Importer les photos sélectionnées dans l'application Photos")
            Button { askSource = true } label: { Label("Légendes", systemImage: "text.quote") }
                .disabled(story.pages.isEmpty || store.busy != nil)
                .help("Rédiger les légendes")
            Button { Task { await store.applyPhotosPeople(storyId) } } label: { Label("Personnes", systemImage: "person.2") }
                .disabled(story.pages.isEmpty || store.busy != nil)
                .help("Cocher les personnes nommées dans Photos")
            Button { showBook = true } label: { Label("Feuilleter", systemImage: "book") }
                .disabled(story.pages.isEmpty)
            Button { showPDF = true } label: { Label("PDF et partage", systemImage: "square.and.arrow.up") }
                .disabled(story.pages.isEmpty || store.busy != nil)
            Button { showInfo = true } label: { Label("Infos", systemImage: "info.circle") }
        }
    }

    private func pageList(_ story: Story) -> some View {
        List(selection: $selection) {
            ForEach(Array(story.pages.enumerated()), id: \.element.id) { index, page in
                PageRow(index: index, page: page, people: store.people).tag(page.id)
            }
        }
        .onDeleteCommand { if !selection.isEmpty { confirmDelete = true } }
        .contextMenu(forSelectionType: String.self) { ids in
            Button("Rédiger la légende") {
                Task { await store.writeCaptions(storyId, pageIds: Array(ids), source: store.captionSource, rewrite: true) }
            }
            Button("Retirer du livre…", role: .destructive) { selection = ids; confirmDelete = true }
        }
    }

    @ViewBuilder
    private func detail(_ story: Story) -> some View {
        if selection.count == 1, let id = selection.first, let index = story.pages.firstIndex(where: { $0.id == id }) {
            PageEditor(storyId: storyId, page: story.pages[index], index: index, count: story.pages.count) { delta in
                let target = index + delta
                if story.pages.indices.contains(target) { selection = [story.pages[target].id] }
            }
            .id(id)
        } else {
            VStack(spacing: 8) {
                Text(selection.isEmpty ? "Choisissez une photo pour voir et modifier sa légende." : "\(selection.count) photos sélectionnées")
                    .foregroundStyle(.secondary)
                if selection.count > 1 {
                    Button("Retirer du livre…", role: .destructive) { confirmDelete = true }
                }
                Text("Astuce : ⌘-clic ou ⇧-clic pour sélectionner plusieurs photos, touche ⌫ pour les retirer.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

/// Une ligne : vignette, lieu en gras, texte sur 2 lignes, date et personnes.
struct PageRow: View {
    let index: Int
    let page: Page
    let people: [Person]

    var body: some View {
        HStack(spacing: 10) {
            Thumbnail(assetId: page.assetId, side: 64)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(index + 1). " + (page.place.isEmpty ? (page.needsPlace ? "Lieu à indiquer" : "Lieu à trouver") : page.place))
                    .fontWeight(.bold).lineLimit(1)
                Text(page.summary.isEmpty ? status : page.summary).font(.callout).lineLimit(2)
                let footer = CaptionFormat.footer(page, people)
                if !footer.isEmpty { Text(footer).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
            }
            Spacer(minLength: 0)
            switch page.status {
            case .running: ProgressView().controlSize(.small)
            case .error: Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            default:
                if page.needsPlace { Image(systemName: "location.slash").foregroundStyle(.orange) }
            }
        }
        .padding(.vertical, 3)
    }

    private var status: String {
        switch page.status {
        case .todo: return page.needsPlace ? "Sans GPS : indiquez le lieu ou le nom du cliché" : "Légende pas encore rédigée"
        case .running: return "Rédaction en cours…"
        case .error: return page.error ?? "Erreur"
        case .done: return ""
        }
    }
}

/// Vignette chargée depuis Photos.
struct Thumbnail: View {
    let assetId: String
    let side: CGFloat
    var fill = true
    @State private var image: CGImage?

    var body: some View {
        Group {
            if let image {
                Image(decorative: image, scale: 1).resizable().aspectRatio(contentMode: fill ? .fill : .fit)
            } else {
                Rectangle().fill(.quaternary)
            }
        }
        .frame(width: fill ? side : nil, height: fill ? side : nil)
        .frame(maxWidth: fill ? nil : .infinity, maxHeight: fill ? nil : .infinity)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .task(id: assetId) { image = await PhotosService.image(assetId, maxSide: fill ? side * 3 : side) }
    }
}

/// Modifier la légende d'une photo ; Précédente / Suivante pour enchaîner.
struct PageEditor: View {
    @EnvironmentObject var store: Store
    let storyId: String
    let page: Page
    let index: Int
    let count: Int
    let move: (Int) -> Void

    @State private var place = ""
    @State private var summary = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Thumbnail(assetId: page.assetId, side: 1400, fill: false).frame(height: 320)

                // Aperçu tel qu'il apparaîtra dans le livre.
                VStack(alignment: .leading, spacing: 3) {
                    Text(page.place.isEmpty ? "Lieu" : page.place).font(.title3).bold()
                    if !page.summary.isEmpty { Text(page.summary).lineLimit(2) }
                    let footer = CaptionFormat.footer(page, store.people)
                    if !footer.isEmpty { Text(footer).font(.callout).italic().foregroundStyle(.secondary) }
                }
                .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 8).fill(.quaternary.opacity(0.5)))

                if !page.hasGPS {
                    Text("📍 Cette photo n'a pas de position GPS. Indiquez le lieu ou le nom du cliché, puis « Rédiger la légende ».")
                        .foregroundStyle(.orange)
                }
                if let error = page.error { Text(error).foregroundStyle(.red).font(.callout) }

                TextField(page.hasGPS ? "Lieu (en gras)" : "Lieu ou nom du cliché (en gras)", text: $place)
                    .onChange(of: place) { value in
                        // Seule une vraie saisie compte comme « lieu indiqué par vous ».
                        guard value != page.place else { return }
                        store.editPage(storyId, page.id) { $0.place = value; $0.placeByUser = !value.isEmpty }
                    }
                TextField("Texte – 2 lignes, style télégraphique", text: $summary, axis: .vertical)
                    .lineLimit(2...4)
                    .onChange(of: summary) { value in
                        guard value != page.summary else { return }
                        store.editPage(storyId, page.id) { $0.summary = value }
                    }
                Text("\(summary.count) / \(CaptionFormat.maxSummary) caractères")
                    .font(.caption).foregroundStyle(summary.count > CaptionFormat.maxSummary ? .red : .secondary)

                Button {
                    Task { await store.writeCaptions(storyId, pageIds: [page.id], source: store.captionSource, rewrite: true) }
                } label: {
                    Label(page.status == .done ? "Réécrire la légende" : "Rédiger la légende", systemImage: "text.quote")
                }
                .disabled(store.busy != nil)
                Text(info).font(.caption).foregroundStyle(.secondary)

                Text("Personnes sur la photo").font(.headline)
                let story = store.stories.first { $0.id == storyId }
                let ordered = store.people.sorted { a, b in
                    let ta = story?.travelerIds.contains(a.id) ?? false
                    let tb = story?.travelerIds.contains(b.id) ?? false
                    return ta && !tb
                }
                PeopleChips(people: ordered, selected: Binding(
                    get: { Set(page.personIds) },
                    set: { ids in store.editPage(storyId, page.id) { $0.personIds = Array(ids) } }
                ))

                HStack {
                    Button { move(-1) } label: { Label("Précédente", systemImage: "chevron.left") }
                        .disabled(index == 0).keyboardShortcut(.leftArrow, modifiers: [.command])
                    Spacer()
                    Text("Photo \(index + 1) / \(count)").foregroundStyle(.secondary)
                    Spacer()
                    Button { move(1) } label: { Label("Suivante", systemImage: "chevron.right") }
                        .disabled(index == count - 1).keyboardShortcut(.rightArrow, modifiers: [.command])
                }
                .padding(.top, 8)
            }
            .padding(16)
        }
        .onAppear {
            place = page.place
            summary = page.summary
        }
        .onChange(of: page.place) { if $0 != place { place = $0 } }
        .onChange(of: page.summary) { if $0 != summary { summary = $0 } }
    }

    private var info: String {
        var parts: [String] = []
        if let d = page.takenAt { parts.append(DateFormat.long(d)) }
        if page.hasGPS { parts.append("position GPS connue") }
        if !page.wikiTitle.isEmpty { parts.append("Wikipédia : \(page.wikiTitle)") }
        parts.append("rédaction : " + (store.captionSource == .claude ? "Claude (Anthropic)" : "sources libres (Wikipédia)"))
        return parts.joined(separator: " · ")
    }
}
