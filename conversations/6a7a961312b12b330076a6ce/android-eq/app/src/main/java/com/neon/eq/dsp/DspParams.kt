package com.neon.eq.dsp

import com.neon.eq.engine.EqualizerEngine
import org.json.JSONArray
import org.json.JSONObject

/** One parametric EQ slot (native DSP supports 8). */
data class PeqSlot(var on: Boolean = false, var freq: Float = 1000f, var gain: Float = 0f, var q: Float = 1.0f)

/**
 * Build #117: the full DSP-chain parameter set — preamp, parametric slots,
 * bass/treble shelves, stereo width/balance/swap/mono, compressor, limiter.
 * Persisted as JSON (dsp_params_json) and applied to the native engine both
 * live (UI edits) and at capture start. Validated by the C++ layer too.
 */
class DspParams {
    var preamp = 0f
    var bass = 0f
    var treble = 0f
    var width = 1f
    var balance = 0f
    var mono = false
    var swap = false
    var compOn = false
    var compThresh = -18f
    var limiterOn = true
    var limThresh = -1f
    var slots = List(8) { PeqSlot() }

    fun applyTo(dsp: NeonDsp) {
        try {
            dsp.setPreamp(preamp)
            dsp.setShelves(bass, treble)
            dsp.setStereo(width, balance, swap, mono)
            dsp.setCompressor(compOn, compThresh, 4f, 5f, 150f)
            dsp.setLimiter(limiterOn, limThresh)
            slots.forEachIndexed { i, s -> dsp.setParametric(i, s.on, s.freq, s.gain, s.q) }
        } catch (_: Throwable) { }
    }

    fun save(engine: EqualizerEngine) {
        try {
            val o = JSONObject()
            o.put("preamp", preamp.toDouble()); o.put("bass", bass.toDouble()); o.put("treble", treble.toDouble())
            o.put("width", width.toDouble()); o.put("balance", balance.toDouble())
            o.put("mono", mono); o.put("swap", swap)
            o.put("compOn", compOn); o.put("compThresh", compThresh.toDouble())
            o.put("limiterOn", limiterOn); o.put("limThresh", limThresh.toDouble())
            val arr = JSONArray()
            slots.forEach { s ->
                val p = JSONObject()
                p.put("on", s.on); p.put("f", s.freq.toDouble()); p.put("g", s.g.toDouble()); p.put("q", s.q.toDouble())
                arr.put(p)
            }
            o.put("peq", arr)
            engine.setDspParamsJson(o.toString())
        } catch (_: Throwable) { }
    }

    companion object {
        fun load(engine: EqualizerEngine): DspParams {
            val p = DspParams()
            try {
                val o = JSONObject(engine.getDspParamsJson())
                p.preamp = o.optDouble("preamp", 0.0).toFloat().coerceIn(-30f, 30f)
                p.bass = o.optDouble("bass", 0.0).toFloat().coerceIn(-30f, 30f)
                p.treble = o.optDouble("treble", 0.0).toFloat().coerceIn(-30f, 30f)
                p.width = o.optDouble("width", 1.0).toFloat().coerceIn(0f, 4f)
                p.balance = o.optDouble("balance", 0.0).toFloat().coerceIn(-1f, 1f)
                p.mono = o.optBoolean("mono", false)
                p.swap = o.optBoolean("swap", false)
                p.compOn = o.optBoolean("compOn", false)
                p.compThresh = o.optDouble("compThresh", -18.0).toFloat().coerceIn(-60f, 0f)
                p.limiterOn = o.optBoolean("limiterOn", true)
                p.limThresh = o.optDouble("limThresh", -1.0).toFloat().coerceIn(-60f, 0f)
                val arr = o.optJSONArray("peq")
                if (arr != null) {
                    val list = ArrayList<PeqSlot>()
                    for (i in 0 until 8) {
                        val s = arr.optJSONObject(i) ?: break
                        list.add(PeqSlot(
                            s.optBoolean("on", false),
                            s.optDouble("f", 1000.0).toFloat().coerceIn(10f, 20000f),
                            s.optDouble("g", 0.0).toFloat().coerceIn(-30f, 30f),
                            s.optDouble("q", 1.0).toFloat().coerceIn(0.1f, 10f)))
                    }
                    while (list.size < 8) list.add(PeqSlot())
                    p.slots = list
                }
            } catch (_: Throwable) { }
            return p
        }
    }
}
