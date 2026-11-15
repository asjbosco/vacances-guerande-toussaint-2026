import AppKit
import CoreGraphics

/// Qualité des photos dans le PDF.
enum PDFQuality: String, CaseIterable, Identifiable {
    case high, light
    var id: String { rawValue }
    var maxSide: CGFloat { self == .high ? 3000 : 1800 }
    var jpegQuality: Double { self == .high ? 0.92 : 0.85 }
    var label: String { self == .high ? "Haute qualité (impression)" : "Léger (envoi rapide)" }
}

/**
 Livre en PDF, format A4 : une couverture puis une page par photo
 (lieu en gras, texte sur 2 lignes, date et personnes).
 Les photos viennent de Photos en pleine résolution et sont intégrées en JPEG, sans perte supplémentaire.
 */
enum PDFExporter {
    private static let pageSize = CGSize(width: 595, height: 842)
    private static let margin: CGFloat = 40
    private static let accent = NSColor(red: 31 / 255, green: 95 / 255, blue: 122 / 255, alpha: 1)
    private static let paper = NSColor(red: 251 / 255, green: 247 / 255, blue: 240 / 255, alpha: 1)

    static func export(story: Story, people: [Person], quality: PDFQuality, progress: (Int, Int) -> Void) async throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("livre", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let name = story.title.replacingOccurrences(of: "/", with: "-").trimmingCharacters(in: .whitespaces)
        let url = dir.appendingPathComponent((name.isEmpty ? "Livre de voyage" : name) + ".pdf")

        var mediaBox = CGRect(origin: .zero, size: pageSize)
        guard let ctx = CGContext(url as CFURL, mediaBox: &mediaBox, [kCGPDFContextTitle as String: story.title] as CFDictionary) else {
            throw CaptionError(message: "Impossible de créer le PDF.")
        }
        let travelers = people.filter { story.travelerIds.contains($0.id) }

        // Couverture
        ctx.beginPDFPage(nil)
        withNSContext(ctx) {
            paper.setFill()
            CGRect(origin: .zero, size: pageSize).fill()
        }
        let coverHeight = pageSize.height * 0.48
        if let first = story.pages.first, let image = await jpegImage(first.assetId, quality) {
            drawFilled(image, in: CGRect(x: 0, y: pageSize.height - coverHeight, width: pageSize.width, height: coverHeight), ctx: ctx)
        }
        withNSContext(ctx) {
            var y = pageSize.height - coverHeight - 50
            y = drawText(story.title, font: serif(30, bold: true), color: accent, top: y, center: true)
            if !story.region.isEmpty { y = drawText(story.region, font: serif(15), color: .black, top: y - 10, center: true) }
            if !story.dateRange.isEmpty { y = drawText(story.dateRange, font: sans(12, italic: true), color: .gray, top: y - 6, center: true) }
            if !travelers.isEmpty {
                _ = drawText("Avec " + travelers.map(\.name).joined(separator: ", "), font: serif(13), color: .black, top: y - 30, center: true)
            }
        }
        ctx.endPDFPage()

        // Une page par photo
        for (i, page) in story.pages.enumerated() {
            progress(i + 1, story.pages.count)
            ctx.beginPDFPage(nil)
            let width = pageSize.width - 2 * margin
            let footer = CaptionFormat.footer(page, people)
            // Hauteur du texte (lieu, 2 lignes, pied) mesurée d'abord.
            let blocks: [(String, NSFont, NSColor, Int)] = [
                (page.place, serif(18, bold: true), accent, 2),
                (page.summary, serif(12), .black, 2),
                (footer, sans(10, italic: true), .gray, 3),
            ].filter { !$0.0.isEmpty }
            let textHeight = blocks.reduce(CGFloat(0)) { $0 + height($1.0, font: $1.1, width: width, lines: $1.3) + 8 }
            let photoMaxH = max(pageSize.height * 0.35, pageSize.height - 2 * margin - textHeight - 40)

            var top = pageSize.height - margin
            if let image = await jpegImage(page.assetId, quality) {
                let scale = min(width / CGFloat(image.width), photoMaxH / CGFloat(image.height))
                let w = CGFloat(image.width) * scale
                let h = CGFloat(image.height) * scale
                ctx.draw(image, in: CGRect(x: (pageSize.width - w) / 2, y: top - h, width: w, height: h))
                top -= h + 20
            }
            withNSContext(ctx) {
                var y = top
                for b in blocks { y = drawText(b.0, font: b.1, color: b.2, top: y, lines: b.3) - 8 }
                _ = drawText("\(i + 2)", font: sans(9), color: .gray, top: margin - 8, center: true)
            }
            ctx.endPDFPage()
        }
        ctx.closePDF()
        return url
    }

    /// Image JPEG de la photo ; Quartz l'intègre telle quelle dans le PDF.
    private static func jpegImage(_ assetId: String, _ quality: PDFQuality) async -> CGImage? {
        guard let data = await PhotosService.jpeg(assetId, maxSide: quality.maxSide, quality: quality.jpegQuality),
              let provider = CGDataProvider(data: data as CFData) else { return nil }
        return CGImage(jpegDataProviderSource: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    private static func drawFilled(_ image: CGImage, in rect: CGRect, ctx: CGContext) {
        let scale = max(rect.width / CGFloat(image.width), rect.height / CGFloat(image.height))
        let w = CGFloat(image.width) * scale
        let h = CGFloat(image.height) * scale
        ctx.saveGState()
        ctx.clip(to: rect)
        ctx.draw(image, in: CGRect(x: rect.midX - w / 2, y: rect.midY - h / 2, width: w, height: h))
        ctx.restoreGState()
    }

    private static func withNSContext(_ ctx: CGContext, _ draw: () -> Void) {
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
        draw()
        NSGraphicsContext.restoreGraphicsState()
    }

    private static func attributes(_ font: NSFont, _ color: NSColor, center: Bool) -> [NSAttributedString.Key: Any] {
        let style = NSMutableParagraphStyle()
        style.alignment = center ? .center : .left
        style.lineBreakMode = .byWordWrapping
        return [.font: font, .foregroundColor: color, .paragraphStyle: style]
    }

    private static func height(_ text: String, font: NSFont, width: CGFloat, lines: Int) -> CGFloat {
        let full = NSAttributedString(string: text, attributes: attributes(font, .black, center: false))
            .boundingRect(with: CGSize(width: width, height: 10_000), options: [.usesLineFragmentOrigin, .usesFontLeading]).height
        let lineHeight = NSLayoutManager().defaultLineHeight(for: font)
        return min(ceil(full), ceil(lineHeight * CGFloat(lines)))
    }

    /// Dessine un texte dont le haut est à [top] (repère PDF, origine en bas) ; renvoie le bas.
    @discardableResult
    private static func drawText(_ text: String, font: NSFont, color: NSColor, top: CGFloat, center: Bool = false, lines: Int = 3) -> CGFloat {
        let width = pageSize.width - 2 * margin
        let h = height(text, font: font, width: width, lines: lines)
        let rect = CGRect(x: margin, y: top - h, width: width, height: h)
        NSAttributedString(string: text, attributes: attributes(font, color, center: center))
            .draw(with: rect, options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine, .usesFontLeading])
        return top - h
    }

    static func serif(_ size: CGFloat, bold: Bool = false) -> NSFont {
        let base = NSFont.systemFont(ofSize: size, weight: bold ? .bold : .regular)
        if let d = base.fontDescriptor.withDesign(.serif), let f = NSFont(descriptor: d, size: size) { return f }
        return base
    }

    static func sans(_ size: CGFloat, italic: Bool = false) -> NSFont {
        let base = NSFont.systemFont(ofSize: size)
        return italic ? NSFontManager.shared.convert(base, toHaveTrait: .italicFontMask) : base
    }
}
