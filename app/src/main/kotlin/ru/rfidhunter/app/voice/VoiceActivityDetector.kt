package ru.rfidhunter.app.voice

import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sqrt

data class VadDecision(
    val ended: Boolean,
    val state: String,
    val speechStarted: Boolean,
    val speechStartSample: Int,
    val lastVoiceSample: Int,
    val noiseFloorDbfs: Double,
    val startThresholdDbfs: Double,
    val continueThresholdDbfs: Double,
    val quietMs: Long,
)

data class VadSegment(
    val speechDetected: Boolean,
    val startSample: Int = -1,
    val endSample: Int = -1,
)

/**
 * Adaptive energy VAD used by the Android recorder and mirrored by
 * scripts/vad_reference.py. It deliberately has no device-specific absolute
 * gain assumption: thresholds follow a robust lower-percentile noise floor.
 */
class AdaptiveVoiceActivityDetector(
    private val sampleRate: Int = AudioCaptureSession.SAMPLE_RATE,
) {
    companion object {
        private const val FRAME_MS = 20L
        private const val BASELINE_MS = 300L
        private const val NOISE_HISTORY_MS = 2_000L
        private const val START_MARGIN_DB = 10.0
        private const val CONTINUE_MARGIN_DB = 4.0
        private const val MIN_START_DBFS = -42.0
        private const val MIN_CONTINUE_DBFS = -48.0
        private const val START_CONFIRM_MS = 180L
        private const val END_SILENCE_MS = 1_100L
        private const val REACTIVATION_GUARD_MS = 250L
        private const val REACTIVATION_CONFIRM_MS = 40L

        const val PRE_ROLL_MS = 280L
        const val POST_ROLL_MS = 160L
    }

    val frameSamples: Int = (sampleRate * FRAME_MS / 1_000L).toInt().coerceAtLeast(1)
    private val baselineFrames = (BASELINE_MS / FRAME_MS).toInt().coerceAtLeast(1)
    private val historyCapacity = (NOISE_HISTORY_MS / FRAME_MS).toInt().coerceAtLeast(1)
    private val startConfirmFrames = ceil(START_CONFIRM_MS.toDouble() / FRAME_MS).toInt().coerceAtLeast(1)
    private val endSilenceFrames = ceil(END_SILENCE_MS.toDouble() / FRAME_MS).toInt().coerceAtLeast(1)
    private val reactivationGuardFrames = ceil(REACTIVATION_GUARD_MS.toDouble() / FRAME_MS).toInt().coerceAtLeast(1)
    private val reactivationConfirmFrames = ceil(REACTIVATION_CONFIRM_MS.toDouble() / FRAME_MS).toInt().coerceAtLeast(1)

    private val noiseHistory = ArrayDeque<Double>(historyCapacity)
    private val baselineValues = ArrayList<Double>(baselineFrames)
    private var noiseFloorDbfs = -50.0
    private var baselineComplete = false
    private var speechStarted = false
    private var speechStartSample = -1
    private var lastVoiceSample = -1
    private var startHits = 0
    private var quietFrames = 0
    private var reactivationHits = 0

    fun process(samples: ShortArray, frameStartSample: Int): VadDecision {
        val rms = rms(samples)
        val dbfs = dbfs(rms)

        if (!baselineComplete) {
            if (dbfs > -100.0) baselineValues += dbfs
            if (baselineValues.size >= baselineFrames) {
                noiseHistory.clear()
                baselineValues.forEach { addNoise(it) }
                noiseFloorDbfs = percentile(noiseHistory.toList(), 20.0)
                baselineComplete = true
            }
            return decision(false, "WAITING", dbfs, frameStartSample + samples.size)
        }

        val startThreshold = maxOf(MIN_START_DBFS, noiseFloorDbfs + START_MARGIN_DB)
        val continueThreshold = maxOf(MIN_CONTINUE_DBFS, noiseFloorDbfs + CONTINUE_MARGIN_DB)
        if (!speechStarted) {
            if (dbfs >= startThreshold) {
                startHits++
                if (startHits >= startConfirmFrames) {
                    speechStarted = true
                    val candidateStart = frameStartSample - (startConfirmFrames - 1) * frameSamples
                    speechStartSample = (candidateStart - preRollSamples()).coerceAtLeast(0)
                    lastVoiceSample = frameStartSample + samples.size
                    quietFrames = 0
                    reactivationHits = 0
                    return decision(false, "ACTIVE", dbfs, frameStartSample + samples.size, startThreshold, continueThreshold)
                }
            } else {
                startHits = 0
                addNoise(dbfs)
                noiseFloorDbfs = percentile(noiseHistory.toList(), 20.0)
            }
            return decision(false, "WAITING", dbfs, frameStartSample + samples.size, startThreshold, continueThreshold)
        }

        if (dbfs >= continueThreshold) {
            if (quietFrames >= reactivationGuardFrames) {
                reactivationHits++
                if (reactivationHits < reactivationConfirmFrames) {
                    return decision(false, "TAIL", dbfs, frameStartSample + samples.size, startThreshold, continueThreshold)
                }
            }
            quietFrames = 0
            reactivationHits = 0
            lastVoiceSample = frameStartSample + samples.size
            return decision(false, "ACTIVE", dbfs, frameStartSample + samples.size, startThreshold, continueThreshold)
        }

        quietFrames++
        reactivationHits = 0
        val ended = quietFrames >= endSilenceFrames
        return decision(ended, "TAIL", dbfs, frameStartSample + samples.size, startThreshold, continueThreshold)
    }

    fun finish(totalSamples: Int): VadSegment {
        if (!speechStarted || speechStartSample < 0 || lastVoiceSample < 0) return VadSegment(false)
        val end = (lastVoiceSample + postRollSamples()).coerceAtMost(totalSamples)
        return VadSegment(true, speechStartSample.coerceAtMost((end - 1).coerceAtLeast(0)), end)
    }

    fun noiseFloorDbfs(): Double = noiseFloorDbfs

    private fun decision(
        ended: Boolean,
        state: String,
        @Suppress("UNUSED_PARAMETER") dbfs: Double,
        @Suppress("UNUSED_PARAMETER") frameEndSample: Int,
        startThreshold: Double = maxOf(MIN_START_DBFS, noiseFloorDbfs + START_MARGIN_DB),
        continueThreshold: Double = maxOf(MIN_CONTINUE_DBFS, noiseFloorDbfs + CONTINUE_MARGIN_DB),
    ): VadDecision = VadDecision(
        ended = ended,
        state = state,
        speechStarted = speechStarted,
        speechStartSample = speechStartSample,
        lastVoiceSample = lastVoiceSample,
        noiseFloorDbfs = noiseFloorDbfs,
        startThresholdDbfs = startThreshold,
        continueThresholdDbfs = continueThreshold,
        quietMs = quietFrames * FRAME_MS,
    )

    private fun addNoise(value: Double) {
        if (value <= -100.0) return
        if (noiseHistory.size >= historyCapacity) noiseHistory.removeFirst()
        noiseHistory.addLast(value)
    }

    private fun preRollSamples(): Int = (sampleRate * PRE_ROLL_MS / 1_000L).toInt()
    private fun postRollSamples(): Int = (sampleRate * POST_ROLL_MS / 1_000L).toInt()

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        samples.forEach { value -> sum += value.toDouble() * value.toDouble() }
        return sqrt(sum / samples.size)
    }

    private fun dbfs(rms: Double): Double = maxOf(-120.0, 20.0 * log10(maxOf(rms / 32_768.0, 1e-6)))

    private fun percentile(values: List<Double>, percent: Double): Double {
        if (values.isEmpty()) return noiseFloorDbfs
        val sorted = values.sorted()
        val position = ((sorted.size - 1) * percent.coerceIn(0.0, 100.0) / 100.0)
        val lower = floor(position).toInt()
        val upper = position.toInt().coerceAtMost(sorted.lastIndex)
        if (lower == upper) return sorted[lower]
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }
}
