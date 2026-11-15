import AppKit
import Foundation
import Photos

/// L'état de l'application : vos histoires, vos proches, et les actions possibles.
@MainActor
final class Store: ObservableObject {
    @Published var stories: [Story] = []
    @Published var people: [Person] = []
    @Published var selectedStoryId: String?
    @Published var busy: String?
    @Published var message: String?
    @Published var apiKeySet = !Keychain.apiKey.isEmpty
    @Published var captionSource: CaptionSource {
        didSet { UserDefaults.standard.set(captionSource.rawValue, forKey: "source") }
    }
    @Published var libraryPath: String {
        didSet { UserDefaults.standard.set(libraryPath, forKey: "photothèque") }
    }

    private let folder: URL
    private var storiesDir: URL { folder.appendingPathComponent("histoires", isDirectory: true) }
    private var peopleFile: URL { folder.appendingPathComponent("proches.json") }
    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        e.dateEncodingStrategy = .iso8601
        return e
    }()
    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        folder = support.appendingPathComponent("Livre de voyage", isDirectory: true)
        captionSource = CaptionSource(rawValue: UserDefaults.standard.string(forKey: "source") ?? "") ?? .wikipedia
        libraryPath = UserDefaults.standard.string(forKey: "photothèque") ?? PhotosPeople.defaultLibrary.path
        try? FileManager.default.createDirectory(at: storiesDir, withIntermediateDirectories: true)
        load()
    }

    var story: Story? { stories.first { $0.id == selectedStoryId } }

    // MARK: - Enregistrement

    private func load() {
        let files = (try? FileManager.default.contentsOfDirectory(at: storiesDir, includingPropertiesForKeys: nil)) ?? []
        stories = files.filter { $0.pathExtension == "json" }
            .compactMap { try? decoder.decode(Story.self, from: Data(contentsOf: $0)) }
            .map { s in
                var s = s
                for i in s.pages.indices where s.pages[i].status == .running { s.pages[i].status = .todo }
                return s
            }
            .sorted { $0.updatedAt > $1.updatedAt }
        people = (try? decoder.decode([Person].self, from: Data(contentsOf: peopleFile))) ?? defaultPeople
    }

    private func save(_ story: Story) {
        if let data = try? encoder.encode(story) {
            try? data.write(to: storiesDir.appendingPathComponent(story.id + ".json"), options: .atomic)
        }
    }

    private func savePeople() {
        if let data = try? encoder.encode(people) { try? data.write(to: peopleFile, options: .atomic) }
    }

    /// Modifie une histoire puis l'enregistre.
    func edit(_ id: String, _ change: (inout Story) -> Void) {
        guard let i = stories.firstIndex(where: { $0.id == id }) else { return }
        change(&stories[i])
        stories[i].updatedAt = Date()
        save(stories[i])
    }

    func editPage(_ storyId: String, _ pageId: String, _ change: (inout Page) -> Void) {
        edit(storyId) { s in
            if let j = s.pages.firstIndex(where: { $0.id == pageId }) { change(&s.pages[j]) }
        }
    }

    // MARK: - Histoires

    func createStory(title: String, region: String, travelerIds: [String]) {
        let story = Story(title: title.isEmpty ? "Nouvelle histoire" : title, region: region, travelerIds: travelerIds)
        stories.insert(story, at: 0)
        save(story)
        selectedStoryId = story.id
    }

    func duplicate(_ id: String) {
        guard var copy = stories.first(where: { $0.id == id }) else { return }
        copy.id = UUID().uuidString
        copy.title += " (copie)"
        copy.createdAt = Date()
        copy.updatedAt = Date()
        copy.pages = copy.pages.map { p in var p = p; p.id = UUID().uuidString; return p }
        stories.insert(copy, at: 0)
        save(copy)
        selectedStoryId = copy.id
    }

    func delete(_ id: String) {
        stories.removeAll { $0.id == id }
        try? FileManager.default.removeItem(at: storiesDir.appendingPathComponent(id + ".json"))
        if selectedStoryId == id { selectedStoryId = stories.first?.id }
    }

    // MARK: - Photos

    /// Ajoute des photos de Photos à l'histoire (sans doublon), dans l'ordre chronologique.
    func addPhotos(_ ids: [String], to storyId: String) async {
        guard await PhotosService.requestAccess() else {
            message = "Autorisez l'accès à Photos dans Réglages Système › Confidentialité et sécurité › Photos."
            return
        }
        let existing = Set(stories.first { $0.id == storyId }?.pages.map(\.assetId) ?? [])
        let assets = PhotosService.assets(ids.filter { !existing.contains($0) })
        guard !assets.isEmpty else { return }
        let pages = assets.map {
            Page(assetId: $0.localIdentifier, takenAt: $0.creationDate,
                 latitude: $0.location?.coordinate.latitude, longitude: $0.location?.coordinate.longitude)
        }
        edit(storyId) { s in
            s.pages = (s.pages + pages).sorted { ($0.takenAt ?? .distantFuture) < ($1.takenAt ?? .distantFuture) }
        }
        let noGPS = pages.filter { !$0.hasGPS }.count
        message = "\(pages.count) photo(s) ajoutée(s)" + (noGPS > 0 ? " – \(noGPS) sans GPS : indiquez leur lieu." : ".")
        // Les personnes déjà nommées dans Photos sont cochées tout de suite, si possible.
        await applyPhotosPeople(storyId, quiet: true)
    }

    func importPhotosAppSelection(to storyId: String) async {
        do {
            let ids = try PhotosService.selectionInPhotosApp()
            if ids.isEmpty {
                message = "Aucune photo sélectionnée dans Photos. Sélectionnez-les dans Photos, puis réessayez."
                return
            }
            await addPhotos(ids, to: storyId)
        } catch {
            message = "Impossible de lire la sélection de Photos : \(error.localizedDescription). " +
                "Autorisez « Livre de voyage » à contrôler Photos (Réglages Système › Confidentialité et sécurité › Automatisation)."
        }
    }

    func removePages(_ ids: Set<String>, from storyId: String) {
        edit(storyId) { s in s.pages.removeAll { ids.contains($0.id) } }
    }

    // MARK: - Personnes (noms de Photos)

    /// Coche sur chaque photo les personnes identifiées dans « Personnes » de Photos.
    func applyPhotosPeople(_ storyId: String, quiet: Bool = false) async {
        guard let story = stories.first(where: { $0.id == storyId }) else { return }
        let uuids = Set(story.pages.map(\.assetUUID))
        let library = URL(fileURLWithPath: libraryPath)
        busy = quiet ? busy : "Lecture des Personnes de Photos…"
        defer { if !quiet { busy = nil } }
        let result: [String: [NamedFace]]
        do {
            result = try await Task.detached { try PhotosPeople.facesByAsset(library: library, assetUUIDs: uuids) }.value
        } catch {
            if !quiet { message = error.localizedDescription }
            return
        }
        // Proches : on rattache chaque personne de Photos (par identifiant, sinon par prénom).
        var ticked = 0
        for faces in result.values {
            for face in faces where !people.contains(where: { $0.photosId == face.personId }) {
                if let i = people.firstIndex(where: { $0.photosId == nil && $0.name.caseInsensitiveCompare(face.name) == .orderedSame }) {
                    people[i].photosId = face.personId
                } else {
                    people.append(Person(id: UUID().uuidString, name: face.name, photosId: face.personId))
                }
            }
        }
        savePeople()
        edit(storyId) { s in
            for i in s.pages.indices {
                let ids = (result[s.pages[i].assetUUID] ?? []).compactMap { face in
                    people.first { $0.photosId == face.personId }?.id
                }
                let merged = Array(Set(s.pages[i].personIds + ids))
                if merged.count != s.pages[i].personIds.count { ticked += 1 }
                s.pages[i].personIds = merged
            }
        }
        if !quiet {
            message = result.isEmpty
                ? "Aucun visage nommé dans Photos pour ces photos."
                : "Personnes cochées sur \(ticked) photo(s) d'après Photos."
        }
    }

    func addPerson(_ name: String) {
        let n = name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else { return }
        people.append(Person(id: UUID().uuidString, name: n, photosId: nil))
        savePeople()
    }

    func removePerson(_ id: String) {
        people.removeAll { $0.id == id }
        savePeople()
        for s in stories where s.travelerIds.contains(id) || s.pages.contains(where: { $0.personIds.contains(id) }) {
            edit(s.id) { s in
                s.travelerIds.removeAll { $0 == id }
                for i in s.pages.indices { s.pages[i].personIds.removeAll { $0 == id } }
            }
        }
    }

    // MARK: - Légendes

    func saveApiKey(_ key: String) {
        Keychain.apiKey = key
        apiKeySet = !Keychain.apiKey.isEmpty
        message = apiKeySet ? "Clé API enregistrée dans le trousseau." : "Clé API effacée."
    }

    func writeCaptions(_ storyId: String, pageIds: [String]? = nil, source: CaptionSource, rewrite: Bool) async {
        captionSource = source
        let key = Keychain.apiKey
        if source == .claude && key.isEmpty {
            message = "Pour Claude, enregistrez d'abord votre clé API dans les réglages."
            return
        }
        if rewrite && pageIds == nil {
            edit(storyId) { s in for i in s.pages.indices { s.pages[i].status = .todo } }
        }
        guard let story = stories.first(where: { $0.id == storyId }) else { return }
        let targets = story.pages.filter { pageIds?.contains($0.id) ?? ($0.status != .done) }.map(\.id)
        var missing = 0
        defer { busy = nil }
        for (n, pageId) in targets.enumerated() {
            guard let current = stories.first(where: { $0.id == storyId }),
                  let page = current.pages.first(where: { $0.id == pageId }) else { continue }
            if page.needsPlace {
                missing += 1
                editPage(storyId, pageId) { $0.status = .error; $0.error = "Pas de position GPS : indiquez le lieu ou le nom de la photo." }
                continue
            }
            busy = "Rédaction des légendes… \(n + 1)/\(targets.count)"
            editPage(storyId, pageId) { $0.status = .running; $0.error = nil }
            do {
                let updated = try await Captioner.annotate(page: page, story: current, source: source, apiKey: key)
                editPage(storyId, pageId) { p in
                    let persons = p.personIds
                    p = updated
                    p.personIds = persons
                }
            } catch {
                editPage(storyId, pageId) { $0.status = .error; $0.error = error.localizedDescription }
                if source == .claude {
                    message = error.localizedDescription
                    edit(storyId) { s in for i in s.pages.indices where s.pages[i].status == .running { s.pages[i].status = .todo } }
                    return
                }
            }
        }
        message = missing > 0 ? "Légendes prêtes. \(missing) photo(s) sans GPS attendent leur lieu." : "Légendes prêtes."
    }

    // MARK: - PDF

    func exportPDF(_ storyId: String, quality: PDFQuality) async -> URL? {
        guard let story = stories.first(where: { $0.id == storyId }) else { return nil }
        defer { busy = nil }
        busy = "Fabrication du PDF…"
        do {
            return try await PDFExporter.export(story: story, people: people, quality: quality) { i, n in
                Task { @MainActor in self.busy = "Fabrication du PDF… photo \(i)/\(n)" }
            }
        } catch {
            message = error.localizedDescription
            return nil
        }
    }
}
