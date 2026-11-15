import CoreLocation
import Foundation

struct WikiArticle {
    let title: String
    let extract: String
    let distance: Double?
}

struct CaptionError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Wikipédia en français (gratuit, sans clé).
enum Wikipedia {
    private static let api = "https://fr.wikipedia.org/w/api.php"

    private static func get(_ params: [String: String]) async throws -> [String: Any] {
        var components = URLComponents(string: api)!
        components.queryItems = params.map { URLQueryItem(name: $0.key, value: $0.value) } + [URLQueryItem(name: "format", value: "json")]
        var request = URLRequest(url: components.url!)
        request.setValue("LivreDeVoyage/1.0 (application Mac personnelle)", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 20
        let (data, _) = try await URLSession.shared.data(for: request)
        return (try JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    /// Articles géolocalisés les plus proches de la photo.
    static func nearby(lat: Double, lon: Double) async -> [WikiArticle] {
        for radius in [1000, 5000, 10000] {
            guard let json = try? await get([
                "action": "query", "list": "geosearch", "gscoord": "\(lat)|\(lon)",
                "gsradius": "\(radius)", "gslimit": "3",
            ]) else { return [] }
            let items = ((json["query"] as? [String: Any])?["geosearch"] as? [[String: Any]]) ?? []
            if items.isEmpty { continue }
            let found = items.compactMap { item -> (String, Double?)? in
                guard let title = item["title"] as? String else { return nil }
                return (title, item["dist"] as? Double)
            }
            let extracts = await self.extracts(found.map(\.0))
            return found.compactMap { title, dist in
                guard let e = extracts[title], !e.isEmpty else { return nil }
                return WikiArticle(title: title, extract: e, distance: dist)
            }
        }
        return []
    }

    /// Recherche par texte (photo sans GPS, lieu indiqué à la main).
    static func search(_ query: String) async -> [WikiArticle] {
        guard !query.isEmpty, let json = try? await get(["action": "query", "list": "search", "srsearch": query, "srlimit": "2"]) else { return [] }
        let titles = (((json["query"] as? [String: Any])?["search"] as? [[String: Any]]) ?? []).compactMap { $0["title"] as? String }
        let extracts = await self.extracts(titles)
        return titles.compactMap { t in extracts[t].flatMap { $0.isEmpty ? nil : WikiArticle(title: t, extract: $0, distance: nil) } }
    }

    private static func extracts(_ titles: [String]) async -> [String: String] {
        guard !titles.isEmpty, let json = try? await get([
            "action": "query", "prop": "extracts", "exintro": "1", "explaintext": "1",
            "exlimit": "\(titles.count)", "redirects": "1", "titles": titles.joined(separator: "|"),
        ]) else { return [:] }
        let query = json["query"] as? [String: Any] ?? [:]
        var renamed: [String: String] = [:]
        for key in ["normalized", "redirects"] {
            for item in (query[key] as? [[String: Any]]) ?? [] {
                if let from = item["from"] as? String, let to = item["to"] as? String { renamed[to] = renamed[from] ?? from }
            }
        }
        var result: [String: String] = [:]
        for (_, value) in (query["pages"] as? [String: Any]) ?? [:] {
            guard let page = value as? [String: Any], let title = page["title"] as? String else { continue }
            result[renamed[title] ?? title] = ((page["extract"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return result
    }

    static func firstSentence(_ text: String) -> String {
        if let r = text.range(of: #"[.!?](\s|$)"#, options: .regularExpression) {
            return String(text[..<r.lowerBound]) + "."
        }
        return text
    }
}

/// Appel direct de l'API Claude (Anthropic).
enum Claude {
    static let model = "claude-opus-5-5"

    static let systemPrompt = """
    Tu rédiges les légendes d'un livre-souvenir de voyage, en français.

    La légende repose sur la localisation de la photo : les coordonnées GPS et l'adresse fournies, \
    ou le lieu indiqué par le photographe quand la photo n'a pas de GPS. Ne déduis jamais le lieu \
    de l'image seule. L'image sert seulement à choisir, parmi les articles Wikipédia fournis, celui \
    qui correspond à ce qu'on voit (monument, plage, marais, rue…) ; ignore ceux qui n'ont pas de rapport.

    Format, très court :
    - LIEU : le nom du lieu précis s'il est certain (monument, site, quartier), sinon la commune \
    (ville ou village). Quelques mots, sans la région.
    - TEXTE : style télégraphique, 2 lignes au plus (110 caractères maximum), sans phrase complète \
    ni formule creuse. Un fait historique ou géographique sûr, tiré des extraits (date, particularité). \
    Exemple : « Remparts XIVe-XVe s., 1,4 km, parmi les mieux conservés d'Europe. »
    - N'invente ni nom ni date. Si les extraits ne suffisent pas, décris ce qui se voit en quelques mots.
    - Ne parle pas des personnes présentes : elles sont ajoutées à part.
    - Si des photos voisines ont déjà une légende, garde le même LIEU et écris un TEXTE sur un autre \
    aspect (un détail visible, un autre point de vue), sans répéter.

    Réponds exactement sous cette forme, sans rien d'autre :
    LIEU : <lieu>
    TEXTE : <texte>
    """

    /// Renvoie (lieu, texte), ou nil si Claude décline.
    static func caption(apiKey: String, jpeg: Data, context: String) async throws -> (String, String)? {
        let body: [String: Any] = [
            "model": model,
            "max_tokens": 4000,
            "system": systemPrompt,
            "output_config": ["effort": "low"],
            "messages": [[
                "role": "user",
                "content": [
                    ["type": "image", "source": ["type": "base64", "media_type": "image/jpeg", "data": jpeg.base64EncodedString()]],
                    ["type": "text", "text": context],
                ],
            ]],
        ]
        var request = URLRequest(url: URL(string: "https://api.anthropic.com/v1/messages")!)
        request.httpMethod = "POST"
        request.timeoutInterval = 120
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        request.setValue("2026-10-21", forHTTPHeaderField: "anthropic-version")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        for attempt in 0..<2 {
            let data: Data
            let response: URLResponse
            do {
                (data, response) = try await URLSession.shared.data(for: request)
            } catch {
                throw CaptionError(message: "Pas de connexion Internet, ou Claude injoignable.")
            }
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
            if (200..<300).contains(code) {
                if json["stop_reason"] as? String == "refusal" { return nil }
                let text = ((json["content"] as? [[String: Any]]) ?? [])
                    .filter { $0["type"] as? String == "text" }
                    .compactMap { $0["text"] as? String }
                    .joined(separator: "\n")
                return parse(text)
            }
            let message = (json["error"] as? [String: Any])?["message"] as? String
            switch code {
            case 401: throw CaptionError(message: "Clé API refusée : vérifiez-la dans les réglages.")
            case 403: throw CaptionError(message: "Cette clé API n'a pas accès à Claude.")
            case 400 where (message ?? "").localizedCaseInsensitiveContains("credit"):
                throw CaptionError(message: "Crédit Anthropic épuisé : ajoutez du crédit sur console.anthropic.com.")
            case 400: throw CaptionError(message: "Demande refusée par Claude : \(message ?? "erreur 400")")
            case 429, 500...599:
                if attempt == 0 { try? await Task.sleep(nanoseconds: 5_000_000_000); continue }
                throw CaptionError(message: "Claude est momentanément indisponible (erreur \(code)).")
            default: throw CaptionError(message: "Erreur \(code) de Claude.")
            }
        }
        return nil
    }

    static func parse(_ text: String) -> (String, String)? {
        func value(_ pattern: String) -> String? {
            guard let r = text.range(of: pattern, options: [.regularExpression, .caseInsensitive]) else { return nil }
            return String(text[r.upperBound...]).components(separatedBy: "\n").first?.trimmingCharacters(in: .whitespaces)
        }
        let place = value(#"LIEU\s*:\s*"#) ?? ""
        let summary = value(#"TEXTE\s*:\s*"#) ?? ""
        if summary.isEmpty && place.isEmpty { return nil }
        return (place, summary)
    }
}

/// Rédige la légende d'une page à partir de sa localisation (GPS, ou lieu indiqué).
enum Captioner {

    static func annotate(page: Page, story: Story, source: CaptionSource, apiKey: String) async throws -> Page {
        var page = page
        let userPlace = (page.placeByUser || !page.hasGPS) ? page.place.trimmingCharacters(in: .whitespaces) : ""
        if !page.hasGPS && userPlace.isEmpty {
            throw CaptionError(message: "Pas de position GPS : indiquez le lieu ou le nom de la photo.")
        }

        var precise: String?
        var town: String?
        if let lat = page.latitude, let lon = page.longitude,
           let mark = try? await CLGeocoder().reverseGeocodeLocation(CLLocation(latitude: lat, longitude: lon), preferredLocale: Locale(identifier: "fr_FR")).first {
            precise = mark.name ?? mark.thoroughfare
            town = mark.locality ?? mark.subAdministrativeArea
        }
        let address = [precise, town].compactMap { $0 }.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }.joined(separator: ", ")

        let articles: [WikiArticle]
        if let lat = page.latitude, let lon = page.longitude {
            articles = await Wikipedia.nearby(lat: lat, lon: lon)
        } else {
            articles = await Wikipedia.search([userPlace, story.region].filter { !$0.isEmpty }.joined(separator: " "))
        }

        // Photos voisines déjà légendées : on ne répète pas leur histoire.
        let neighbours = story.pages.filter {
            $0.id != page.id && $0.status == .done && !$0.summary.isEmpty && page.isNear($0, radius: story.radius)
        }
        let used = Set(neighbours.map(\.wikiTitle))

        var claude: (String, String)?
        if source == .claude {
            guard let jpeg = await PhotosService.jpeg(page.assetId, maxSide: 1568, quality: 0.85) else {
                throw CaptionError(message: "Photo introuvable dans Photos.")
            }
            var ctx = ""
            if !story.region.isEmpty { ctx += "Voyage : \(story.region)\n" }
            if let d = page.takenAt { ctx += "Date de la photo : \(DateFormat.long(d))\n" }
            if let lat = page.latitude, let lon = page.longitude { ctx += String(format: "Coordonnées GPS de la photo : %.5f, %.5f\n", lat, lon) }
            if !address.isEmpty { ctx += "Adresse correspondant au GPS : \(address)\n" }
            if !userPlace.isEmpty { ctx += "Lieu indiqué par le photographe : \(userPlace)\n" }
            if articles.isEmpty {
                ctx += "Aucun article Wikipédia trouvé pour ce lieu.\n"
            } else {
                ctx += "\nArticles Wikipédia sur ce lieu ou à proximité :\n"
                for a in articles {
                    let dist = a.distance.map { " (à \(Int($0)) m)" } ?? ""
                    ctx += "<article titre=\"\(a.title)\"\(dist)>\n\(a.extract.prefix(2500))\n</article>\n"
                }
            }
            if !neighbours.isEmpty {
                ctx += "\nLégendes déjà écrites pour des photos prises au même endroit (ne pas les répéter) :\n"
                for n in neighbours { ctx += "- \(n.place) : \(n.summary)\n" }
            }
            ctx += "\nRédige la légende de cette photo."
            claude = try await Claude.caption(apiKey: apiKey, jpeg: jpeg, context: ctx)
        }

        let best = articles.first { !used.contains($0.title) }
        let close = best.flatMap { ($0.distance ?? 0) <= 200 ? $0 : nil }
        let place = [userPlace, claude?.0 ?? "", close?.title ?? "", neighbours.first?.place ?? "", town ?? "", address]
            .first { !$0.isEmpty } ?? page.place
        let summary = claude.map { CaptionFormat.telegraphic($0.1) }
            ?? best.map { CaptionFormat.telegraphic(Wikipedia.firstSentence($0.extract)) }
            ?? ""

        page.place = place
        page.summary = summary
        page.wikiTitle = (best ?? articles.first)?.title ?? ""
        page.status = .done
        page.error = (source == .claude && claude == nil) ? "Claude n'a pas répondu : texte repris de Wikipédia." : nil
        return page
    }
}
