package com.quickstart.md3.data

import kotlin.math.abs
import kotlin.math.round

/**
 * Lightweight recursive-descent arithmetic evaluator for quick calculations in the search bar.
 */
object QuickMathEvaluator {

    fun evaluate(rawQuery: String): String? {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty() || trimmed.length > 64) return null

        var hasOperator = false
        var hasDigit = false
        for (c in trimmed) {
            when {
                c in '0'..'9' -> hasDigit = true
                c == '+' || c == '-' || c == '*' || c == '/' || c == '×' || c == '÷' -> hasOperator = true
                c == '.' || c == '(' || c == ')' || c == ' ' -> Unit
                else -> return null
            }
        }
        if (!hasOperator || !hasDigit) return null

        val normalized = trimmed.replace('×', '*').replace('÷', '/').replace(" ", "")
        return runCatching {
            val parser = Parser(normalized)
            val result = parser.parseExpression()
            if (!parser.isAtEnd() || !result.isFinite()) return null
            formatResult(result)
        }.getOrNull()
    }

    private fun formatResult(value: Double): String {
        val clean = if (abs(value) < 1e-12) 0.0 else value
        val roundedLong = clean.toLong()
        if (abs(clean - roundedLong.toDouble()) < 1e-9 && abs(clean) < 1e15) {
            return roundedLong.toString()
        }
        val scaled = round(clean * 1_000_000.0) / 1_000_000.0
        val asLong = scaled.toLong()
        if (abs(scaled - asLong.toDouble()) < 1e-9 && abs(scaled) < 1e15) {
            return asLong.toString()
        }
        return scaled.toBigDecimal().stripTrailingZeros().toPlainString()
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun isAtEnd(): Boolean = pos >= src.length

        fun parseExpression(): Double {
            var left = parseTerm()
            while (pos < src.length) {
                when (src[pos]) {
                    '+' -> {
                        pos++
                        left += parseTerm()
                    }
                    '-' -> {
                        pos++
                        left -= parseTerm()
                    }
                    else -> break
                }
            }
            return left
        }

        private fun parseTerm(): Double {
            var left = parseFactor()
            while (pos < src.length) {
                when (src[pos]) {
                    '*' -> {
                        pos++
                        left *= parseFactor()
                    }
                    '/' -> {
                        pos++
                        val divisor = parseFactor()
                        if (abs(divisor) < 1e-12) return Double.NaN
                        left /= divisor
                    }
                    else -> break
                }
            }
            return left
        }

        private fun parseFactor(): Double {
            if (pos >= src.length) throw IllegalArgumentException("Unexpected end")
            return when (src[pos]) {
                '+' -> {
                    pos++
                    parseFactor()
                }
                '-' -> {
                    pos++
                    -parseFactor()
                }
                '(' -> {
                    pos++
                    val v = parseExpression()
                    if (pos >= src.length || src[pos] != ')') {
                        throw IllegalArgumentException("Missing ')'")
                    }
                    pos++
                    v
                }
                else -> parseNumber()
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            var dotCount = 0
            while (pos < src.length) {
                val c = src[pos]
                if (c in '0'..'9') {
                    pos++
                } else if (c == '.') {
                    dotCount++
                    if (dotCount > 1) throw IllegalArgumentException("Invalid number")
                    pos++
                } else {
                    break
                }
            }
            if (pos == start) throw IllegalArgumentException("Expected number")
            return src.substring(start, pos).toDouble()
        }
    }
}
