import Foundation

/// Un proche. Ceux qui viennent de « Personnes » dans Photos gardent leur identifiant Photos.
struct Person: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    /// Identifiant de la personne dans la photothèque Photos (nil si ajoutée à la main).
    var photosId: String?
}

enum CaptionSource: String, Codable, CaseIterable {
    case wikipedia, claude
}

enum PageStatus: String, Codable {
    case todo, running, done, error
}

/// Une photo du livre. La photo elle-même reste dans Photos : on ne garde que son identifiant.
struct Page: Codable, Identifiable, Hashable {
    var id: String
    /// Identifiant de la photo dans Photos (PHAsset.localIdentifier).
    var assetId: String
    var takenAt: Date?
    var latitude: Double?
    var longitude: Double?
    /// Ligne en gras : le lieu précis s'il est connu, sinon la ville ou le village.
    var place: String
    /// Lieu saisi par vous : il sert de base à la légende.
    var placeByUser: Bool
    /// Texte court : 2 lignes, style télégraphique.
    var summary: String
    var wikiTitle: String
    var personIds: [String]
    var status: PageStatus
    var error: String?

    init(assetId: String, takenAt: Date?, latitude: Double?, longitude: Double?) {
        id = UUID().uuidString
        self.assetId = assetId
        self.takenAt = takenAt
        self.latitude = latitude
        self.longitude = longitude
        place = ""
        placeByUser = false
        summary = ""
        wikiTitle = ""
        personIds = []
        status = .todo
        error = nil
    }

    var hasGPS: Bool { latitude != nil && longitude != nil }
    /// Sans GPS, il faut indiquer le lieu ou le nom de la photo.
    var needsPlace: Bool { !hasGPS && place.trimmingCharacters(in: .whitespaces).isEmpty }

    /// La partie « UUID » de l'identifiant Photos, telle qu'elle figure dans la photothèque.
    var assetUUID: String { String(assetId.split(separator: "/").first ?? "") }

    func isNear(_ other: Page, radius: Double) -> Bool {
        if let a = latitude, let b = longitude, let c = other.latitude, let d = other.longitude {
            return Geo.distance(a, b, c, d) <= radius
        }
        let p = place.trimmingCharacters(in: .whitespaces)
        return !p.isEmpty && p.caseInsensitiveCompare(other.place.trimmingCharacters(in: .whitespaces)) == .orderedSame
    }
}

/// Une histoire (un voyage, une fête…) : son propre livre.
struct Story: Codable, Identifiable, Hashable {
    var id: String
    var title: String
    var region: String
    var travelerIds: [String]
    var radius: Double
    var createdAt: Date
    var updatedAt: Date
    var pages: [Page]

    init(title: String, region: String, travelerIds: [String]) {
        id = UUID().uuidString
        self.title = title
        self.region = region
        self.travelerIds = travelerIds
        radius = 300
        createdAt = Date()
        updatedAt = Date()
        pages = []
    }

    /// « 18 – 29 octobre 2026 », d'après les dates des photos.
    var dateRange: String {
        let dates = pages.compactMap(\.takenAt)
        guard let first = dates.min(), let last = dates.max() else { return "" }
        let day = DateFormat.make("d MMMM yyyy")
        if day.string(from: first) == day.string(from: last) { return day.string(from: first) }
        let sameMonth = DateFormat.make("MM yyyy").string(from: first) == DateFormat.make("MM yyyy").string(from: last)
        return DateFormat.make(sameMonth ? "d" : "d MMMM").string(from: first) + " – " + day.string(from: last)
    }
}

enum DateFormat {
    static func make(_ pattern: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateFormat = pattern
        return f
    }

    static func short(_ date: Date) -> String { make("d MMMM yyyy").string(from: date) }
    static func long(_ date: Date) -> String { make("EEEE d MMMM yyyy").string(from: date) }
}

enum Geo {
    /// Distance en mètres entre deux points GPS.
    static func distance(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let r = 6_371_000.0
        let dLat = (lat2 - lat1) * .pi / 180
        let dLon = (lon2 - lon1) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2)
            + cos(lat1 * .pi / 180) * cos(lat2 * .pi / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(min(1, sqrt(a)))
    }
}

/// Présentation commune des légendes : lieu en gras, texte sur 2 lignes, puis date et personnes.
enum CaptionFormat {
    static let maxSummary = 120

    static func names(_ page: Page, _ people: [Person]) -> [String] {
        people.filter { page.personIds.contains($0.id) }.map(\.name)
    }

    /// « 25 octobre 2026 · Avec Maylis, Victor »
    static func footer(_ page: Page, _ people: [Person]) -> String {
        var parts: [String] = []
        if let d = page.takenAt { parts.append(DateFormat.short(d)) }
        let n = names(page, people)
        if !n.isEmpty { parts.append("Avec " + n.joined(separator: ", ")) }
        return parts.joined(separator: " · ")
    }

    /// Coupe le texte à environ deux lignes, sur un mot entier.
    static func telegraphic(_ text: String) -> String {
        let t = text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        guard t.count > maxSummary else { return t }
        var cut = String(t.prefix(maxSummary))
        if let space = cut.lastIndex(of: " ") { cut = String(cut[..<space]) }
        return cut.trimmingCharacters(in: CharacterSet(charactersIn: ",;: ")) + "…"
    }
}

/// Les sept voyageurs de Guérande, proposés au premier lancement.
let defaultPeople: [Person] = ["Emmanuel", "Gaëlle", "Albéric", "Maylis", "Victor", "Juliette", "Charlotte"]
    .map { Person(id: "p-" + $0.lowercased(), name: $0, photosId: nil) }
