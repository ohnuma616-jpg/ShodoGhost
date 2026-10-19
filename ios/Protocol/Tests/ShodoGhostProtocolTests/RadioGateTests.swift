import Testing
@testable import ShodoGhostProtocol

@Test func radioScansWhenNoPeripheralIsRemembered() {
    #expect(RadioGate.step(poweredOn: true, awaitingPrepare: true, known: .absent) == .scan)
}

@Test func radioConnectsARememberedPeripheralInsteadOfWaiting() {
    #expect(RadioGate.step(poweredOn: true, awaitingPrepare: true, known: .disconnected) == .connect)
    #expect(RadioGate.step(poweredOn: true, awaitingPrepare: true, known: .connecting) == .connect)
}

@Test func radioUsesAPeripheralThatIsAlreadyConnected() {
    #expect(RadioGate.step(poweredOn: true, awaitingPrepare: true, known: .connected) == .discoverServices)
}

@Test func radioHoldsUntilBluetoothAndPrepareAreReady() {
    #expect(RadioGate.step(poweredOn: false, awaitingPrepare: true, known: .absent) == .hold)
    #expect(RadioGate.step(poweredOn: true, awaitingPrepare: false, known: .disconnected) == .hold)
}
