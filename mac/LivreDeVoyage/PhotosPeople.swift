import Foundation
import SQLite3

/// Un visage nommé dans « Personnes » de Photos, sur une photo donnée.
struct NamedFace: Hashable {
    let personId: String
    let name: String
}

enum PhotosPeopleError: LocalizedError {
    case libraryNotFound
    case noAccess
    case unreadable(String)

    var errorDescription: String? {
        switch self {
        case .libraryNotFound:
            return "Photothèque introuvable. Choisissez-la dans Réglages (fichier « Photothèque.photoslibrary » ou « Photos Library.photoslibrary » dans Images)."
        case .noAccess:
            return "Pour lire les noms des Personnes, autorisez « Livre de voyage » dans Réglages Système › Confidentialité et sécurité › Accès complet au disque, puis relancez l'application."
        case .unreadable(let detail):
            return "Impossible de lire les Personnes de Photos (\(detail))."
        }
    }
}

/**
 Lit les noms des visages déjà identifiés dans l'application Photos.

 Photos ne donne pas ces noms aux autres applications : on lit donc, en lecture seule,
 une copie de la base de la photothèque (il faut l'« accès complet au disque »).
 Les noms des tables et colonnes varient selon la version de macOS : ils sont détectés.
 */
enum PhotosPeople {

    static var defaultLibrary: URL {
        let pictures = FileManager.default.urls(for: .picturesDirectory, in: .userDomainMask).first!
        let candidates = ["Photos Library.photoslibrary", "Photothèque.photoslibrary", "Bibliothèque Photos.photoslibrary"]
        for name in candidates {
            let url = pictures.appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: url.path) { return url }
        }
        // Sinon, la première photothèque trouvée dans Images.
        let found = (try? FileManager.default.contentsOfDirectory(at: pictures, includingPropertiesForKeys: nil))?
            .first { $0.pathExtension == "photoslibrary" }
        return found ?? pictures.appendingPathComponent(candidates[0])
    }

    /// Pour chaque photo (UUID Photos), les personnes nommées qui y apparaissent.
    static func facesByAsset(library: URL, assetUUIDs: Set<String>) throws -> [String: [NamedFace]] {
        let database = library.appendingPathComponent("database/Photos.sqlite")
        guard FileManager.default.fileExists(atPath: library.path) else { throw PhotosPeopleError.libraryNotFound }
        guard FileManager.default.isReadableFile(atPath: database.path) else { throw PhotosPeopleError.noAccess }

        // Copie (base + journal) pour ne jamais gêner Photos.
        let temp = FileManager.default.temporaryDirectory.appendingPathComponent("photos-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: temp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: temp) }
        for suffix in ["", "-wal", "-shm"] {
            let src = URL(fileURLWithPath: database.path + suffix)
            guard FileManager.default.fileExists(atPath: src.path) else { continue }
            do {
                try FileManager.default.copyItem(at: src, to: temp.appendingPathComponent("Photos.sqlite" + suffix))
            } catch {
                throw PhotosPeopleError.noAccess
            }
        }

        var db: OpaquePointer?
        guard sqlite3_open_v2(temp.appendingPathComponent("Photos.sqlite").path, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else {
            sqlite3_close(db)
            throw PhotosPeopleError.unreadable("ouverture")
        }
        defer { sqlite3_close(db) }

        func columns(_ table: String) -> Set<String> {
            var stmt: OpaquePointer?
            var names = Set<String>()
            if sqlite3_prepare_v2(db, "PRAGMA table_info(\(table))", -1, &stmt, nil) == SQLITE_OK {
                while sqlite3_step(stmt) == SQLITE_ROW {
                    if let c = sqlite3_column_text(stmt, 1) { names.insert(String(cString: c)) }
                }
            }
            sqlite3_finalize(stmt)
            return names
        }

        let assetTable = columns("ZASSET").isEmpty ? "ZGENERICASSET" : "ZASSET"
        let faceCols = columns("ZDETECTEDFACE")
        let personCols = columns("ZPERSON")
        guard !faceCols.isEmpty, !personCols.isEmpty else { throw PhotosPeopleError.unreadable("tables absentes") }
        let facePerson = faceCols.contains("ZPERSONFORFACE") ? "ZPERSONFORFACE" : "ZPERSON"
        let faceAsset = faceCols.contains("ZASSETFORFACE") ? "ZASSETFORFACE" : "ZASSET"
        let personUUID = personCols.contains("ZPERSONUUID") ? "p.ZPERSONUUID" : "p.Z_PK"

        let sql = """
        SELECT a.ZUUID, \(personUUID), p.ZFULLNAME, p.ZDISPLAYNAME
        FROM ZDETECTEDFACE f
        JOIN ZPERSON p ON f.\(facePerson) = p.Z_PK
        JOIN \(assetTable) a ON f.\(faceAsset) = a.Z_PK
        """
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
            throw PhotosPeopleError.unreadable(String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(stmt) }

        func text(_ i: Int32) -> String? {
            if sqlite3_column_type(stmt, i) == SQLITE_NULL { return nil }
            if let c = sqlite3_column_text(stmt, i) { return String(cString: c) }
            return String(sqlite3_column_int64(stmt, i))
        }

        var result: [String: [NamedFace]] = [:]
        while sqlite3_step(stmt) == SQLITE_ROW {
            guard let asset = text(0), assetUUIDs.contains(asset), let person = text(1) else { continue }
            let name = [text(2), text(3)].compactMap { $0?.trimmingCharacters(in: .whitespaces) }.first { !$0.isEmpty }
            guard let name else { continue }
            let face = NamedFace(personId: person, name: name)
            if !(result[asset]?.contains(face) ?? false) { result[asset, default: []].append(face) }
        }
        return result
    }
}
