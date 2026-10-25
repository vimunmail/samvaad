package com.example.xgestalkble.utils

/**
 * Assembles the flat 21-element ML feature vector from SAMVAAD v3.2 firmware
 * fields. The firmware already emits session-invariant, 3-level quantized
 * (−1/0/+1) accel levels plus integer gyro, so this is a straight layout — no
 * scaling or filtering. (Packet format is unchanged from v3.1; only the accel
 * level range changed from −2..+2 to −1..+1, which is why models trained on
 * v3.1 data must be retrained.)
 *
 * Layout (matches CSV column order after Label/timestamp/packetCounter and the
 * web companion's feature vector):
 *   [0-2]   Thumb  accel levels (x,y,z)   -1..+1
 *   [3-5]   Index  accel levels (x,y,z)   -1..+1
 *   [6-8]   Middle accel levels (x,y,z)   -1..+1
 *   [9-11]  Ring   accel levels (x,y,z)   -1..+1
 *   [12-14] Little accel levels (x,y,z)   -1..+1
 *   [15-17] Wrist  accel levels (x,y,z)   -1..+1
 *   [18-20] Wrist  gyro (x,y,z)           integer deg/s
 */
object DataNormalizer {

    const val NUM_FEATURES = 21

    /**
     * @param fingerAccel [5][3] quantized accel levels (thumb..little)
     * @param wristAccel  [3]    quantized wrist accel levels
     * @param wristGyro   [3]    integer wrist gyro deg/s
     */
    fun normalize(
        fingerAccel: Array<IntArray>,
        wristAccel: IntArray,
        wristGyro: IntArray
    ): FloatArray {
        val f = FloatArray(NUM_FEATURES)
        var idx = 0
        for (finger in 0 until 5) {
            for (a in 0 until 3) {
                f[idx++] = fingerAccel[finger][a].toFloat()
            }
        }
        for (a in 0 until 3) f[idx++] = wristAccel[a].toFloat()
        for (a in 0 until 3) f[idx++] = wristGyro[a].toFloat()
        return f
    }

    // ── Legacy stubs kept so nothing else needs to change ──
    var isCalibrated = true
    fun setCalibration(straight: FloatArray, bent: FloatArray) {}
    fun clearCalibration() {}
    fun resetBuffer() {}
}
