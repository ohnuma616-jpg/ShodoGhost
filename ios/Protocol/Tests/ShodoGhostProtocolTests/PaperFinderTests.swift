import Foundation
import Testing
@testable import ShodoGhostProtocol

@Test func paperFinderReadsTheBrightSheet() throws {
    let width = 40
    let height = 30
    var gray = [UInt8](repeating: 20, count: width * height)
    for y in 6..<24 {
        for x in 8..<32 {
            gray[y * width + x] = 230
        }
    }
    let quad = try #require(PaperFinder.quad(width: width, height: height, gray: gray))
    #expect(abs(quad.topLeft.x - 8 * Limits.normMax / (width - 1)) < 800)
    #expect(abs(quad.topLeft.y - 6 * Limits.normMax / (height - 1)) < 800)
    #expect(abs(quad.bottomRight.x - 31 * Limits.normMax / (width - 1)) < 800)
    #expect(abs(quad.bottomRight.y - 23 * Limits.normMax / (height - 1)) < 800)
    #expect(quad.topLeft.x < quad.topRight.x)
    #expect(quad.topLeft.y < quad.bottomLeft.y)
}

@Test func paperFinderIgnoresABlankFrame() {
    let gray = [UInt8](repeating: 200, count: 32 * 24)
    #expect(PaperFinder.quad(width: 32, height: 24, gray: gray) == nil)
}

@Test func paperFinderIgnoresADarkFrame() {
    let gray = [UInt8](repeating: 12, count: 32 * 24)
    #expect(PaperFinder.quad(width: 32, height: 24, gray: gray) == nil)
}
