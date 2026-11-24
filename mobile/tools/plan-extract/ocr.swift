import Foundation
import Vision
import CoreGraphics
import ImageIO

// Распознавание подписей на изображении (локально, встроенный Vision): текст и рамки в пикселях изображения (начало сверху слева).
let path = CommandLine.arguments[1]
guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
      let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { fputs("cannot read image\n", stderr); exit(1) }
let w = Double(img.width), h = Double(img.height)
let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.recognitionLanguages = ["ru-RU", "en-US"]
request.usesLanguageCorrection = false
request.minimumTextHeight = 0.004
let handler = VNImageRequestHandler(cgImage: img, options: [:])
try handler.perform([request])
var out: [[String: Any]] = []
for obs in (request.results ?? []) {
    guard let top = obs.topCandidates(1).first else { continue }
    let b = obs.boundingBox
    out.append(["text": top.string, "conf": top.confidence, "x": b.minX * w, "y": (1 - b.maxY) * h, "w": b.width * w, "h": b.height * h])
}
let data = try JSONSerialization.data(withJSONObject: ["width": w, "height": h, "items": out], options: [])
print(String(data: data, encoding: .utf8)!)
