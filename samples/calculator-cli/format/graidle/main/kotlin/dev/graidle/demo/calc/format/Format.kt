package dev.graidle.demo.calc.format

import java.math.BigDecimal
import java.math.RoundingMode

private const val MAX_DECIMALS = 10

fun formatNumber(value: Double): String {
    val rounded = BigDecimal.valueOf(value)
        .setScale(MAX_DECIMALS, RoundingMode.HALF_UP)
        .stripTrailingZeros()
    return if (rounded.signum() == 0) "0" else rounded.toPlainString()
}
