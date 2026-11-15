import AppKit
import Photos

/// Accès à la photothèque Photos : autorisation, lecture des photos, sélection en cours.
enum PhotosService {

    static func requestAccess() async -> Bool {
        let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        return status == .authorized || status == .limited
    }

    static func assets(_ ids: [String]) -> [PHAsset] {
        let result = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
        var list: [PHAsset] = []
        result.enumerateObjects { asset, _, _ in list.append(asset) }
        return list
    }

    /// Image de la photo, au plus [maxSide] pixels de côté (téléchargée d'iCloud si besoin).
    static func image(_ assetId: String, maxSide: CGFloat) async -> CGImage? {
        guard let asset = assets([assetId]).first else { return nil }
        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.resizeMode = .exact
        options.isNetworkAccessAllowed = true
        options.isSynchronous = false
        let scale = min(1, maxSide / CGFloat(max(asset.pixelWidth, asset.pixelHeight, 1)))
        let size = CGSize(width: CGFloat(asset.pixelWidth) * scale, height: CGFloat(asset.pixelHeight) * scale)
        return await withCheckedContinuation { continuation in
            var resumed = false
            PHImageManager.default().requestImage(for: asset, targetSize: size, contentMode: .aspectFit, options: options) { image, info in
                let degraded = (info?[PHImageResultIsDegradedKey] as? Bool) ?? false
                if degraded || resumed { return }
                resumed = true
                continuation.resume(returning: image?.cgImage(forProposedRect: nil, context: nil, hints: nil))
            }
        }
    }

    /// La photo en JPEG (pour Claude, le PDF, les images partagées).
    static func jpeg(_ assetId: String, maxSide: CGFloat, quality: Double) async -> Data? {
        guard let image = await image(assetId, maxSide: maxSide) else { return nil }
        return jpeg(image, quality: quality)
    }

    static func jpeg(_ image: CGImage, quality: Double) -> Data? {
        NSBitmapImageRep(cgImage: image).representation(using: .jpeg, properties: [.compressionFactor: quality])
    }

    /// Les photos actuellement sélectionnées dans l'application Photos.
    static func selectionInPhotosApp() throws -> [String] {
        let source = """
        tell application "Photos"
            set ids to {}
            repeat with m in (get selection)
                set end of ids to (id of m)
            end repeat
            return ids
        end tell
        """
        var error: NSDictionary?
        guard let result = NSAppleScript(source: source)?.executeAndReturnError(&error) else {
            let message = (error?[NSAppleScript.errorMessage] as? String) ?? "Photos ne répond pas."
            throw NSError(domain: "Photos", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
        }
        var ids: [String] = []
        if result.numberOfItems > 0 {
            for i in 1...result.numberOfItems {
                if let id = result.atIndex(i)?.stringValue { ids.append(id) }
            }
        } else if let single = result.stringValue {
            ids.append(single)
        }
        return ids
    }
}
