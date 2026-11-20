import AVFoundation
import FerrostarCore

extension VoiceGuidance {
    /// Ferrostar's observer for this setting. Ferrostar handles audio session ducking and muting.
    func makeObserver() -> SpokenInstructionObserver {
        switch self {
        case .disabled:
            SpokenInstructionObserver(synthesizer: SilentSynthesizer(), isMuted: true, managesAudioSession: false)
        case let .system(voiceLanguage):
            SpokenInstructionObserver(synthesizer: SystemSynthesizer(voiceLanguage: voiceLanguage), isMuted: false)
        case let .custom(speaker):
            SpokenInstructionObserver(synthesizer: CustomSynthesizer(speaker), isMuted: false)
        }
    }
}

/// AVSpeechSynthesizer with a chosen voice language.
private final class SystemSynthesizer: SpeechSynthesizer {
    private let synthesizer = AVSpeechSynthesizer()
    private let voice: AVSpeechSynthesisVoice?

    init(voiceLanguage: String?) {
        voice = voiceLanguage.flatMap(AVSpeechSynthesisVoice.init(language:))
    }

    var isSpeaking: Bool { synthesizer.isSpeaking }

    func speak(_ utterance: AVSpeechUtterance) {
        if let voice { utterance.voice = voice }
        synthesizer.speak(utterance)
    }

    func stopSpeaking(at boundary: AVSpeechBoundary) -> Bool {
        synthesizer.stopSpeaking(at: boundary)
    }
}

private final class CustomSynthesizer: SpeechSynthesizer {
    private let speaker: any InstructionSpeaker
    init(_ speaker: any InstructionSpeaker) { self.speaker = speaker }

    var isSpeaking: Bool { false }

    func speak(_ utterance: AVSpeechUtterance) {
        speaker.speak(text: utterance.speechString, ssml: nil)
    }

    func stopSpeaking(at _: AVSpeechBoundary) -> Bool {
        speaker.stop()
        return true
    }
}

private final class SilentSynthesizer: SpeechSynthesizer {
    var isSpeaking: Bool { false }
    func speak(_: AVSpeechUtterance) {}
    func stopSpeaking(at _: AVSpeechBoundary) -> Bool { true }
}
