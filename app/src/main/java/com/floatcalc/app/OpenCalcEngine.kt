package com.floatcalc.app

import com.darkempire78.opencalculator.calculator.Calculator
import com.darkempire78.opencalculator.calculator.CalculatorError
import com.darkempire78.opencalculator.calculator.parser.Expression
import com.darkempire78.opencalculator.calculator.parser.NumberFormatter
import com.darkempire78.opencalculator.calculator.parser.NumberingSystem
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * FloatCalc adapter around the copied OpenCalc evaluator.
 *
 * The parser, evaluator, factorial implementation, percentage normalization,
 * and number formatter are compiled from OpenCalc. This adapter adds strict
 * final evaluation, preview-only normalization, and per-call error results
 * around the activity-level behavior the original OpenCalc app normally provides.
 */
class OpenCalcEngine(
    private val numberPrecision: Int = 10,
    private val numberingSystem: NumberingSystem = NumberingSystem.INTERNATIONAL,
    private val numberIntoScientificNotation: Boolean = false
) {
    private val decimalSymbols = DecimalFormatSymbols.getInstance()
    private val decimalSeparator = decimalSymbols.decimalSeparator.toString()
    private val groupingSeparator = decimalSymbols.groupingSeparator.toString()
    private val calculator = Calculator(numberPrecision)

    data class Evaluation(
        val value: BigDecimal = BigDecimal.ZERO,
        val formattedValue: String = "",
        val errorMessage: String? = null,
        val isInfinity: Boolean = false
    ) {
        val isSuccess: Boolean
            get() = errorMessage == null
    }

    fun evaluate(input: String, degreeMode: Boolean): Evaluation =
        evaluateInternal(input, degreeMode, autoCloseParentheses = false)

    /**
     * Preview evaluation is intentionally forgiving while the user is still
     * typing. It can close unfinished parentheses for the preview only; final
     * equals evaluation always uses the strict path above.
     */
    fun evaluatePreview(input: String, degreeMode: Boolean): Evaluation =
        evaluateInternal(input, degreeMode, autoCloseParentheses = true)

    private fun evaluateInternal(
        input: String,
        degreeMode: Boolean,
        autoCloseParentheses: Boolean
    ): Evaluation {
        if (input.isEmpty()) return Evaluation()

        // The calculator UI uses a typographic minus for the keypad, while
        // OpenCalc's parser expects the ASCII minus operator.
        val cleaned = Expression().getCleanExpressionResult(
            input.replace('−', '-'),
            decimalSeparator,
            groupingSeparator,
            autoCloseParentheses
        )
        if (cleaned.hasSyntaxError) {
            return Evaluation(errorMessage = "Syntax error")
        }

        val result = calculator.evaluateDetailed(cleaned.expression, degreeMode)
        val error = when (result.error) {
            null -> null
            CalculatorError.SYNTAX -> "Syntax error"
            CalculatorError.DOMAIN -> "Domain error"
            CalculatorError.REAL_NUMBER_REQUIRED -> "Real number required"
            CalculatorError.DIVISION_BY_ZERO -> "Can't divide by 0"
            CalculatorError.VALUE_TOO_LARGE -> {
                if (result.value < BigDecimal.ZERO) "-Infinity" else "Value too large"
            }
        }

        if (error != null) {
            return Evaluation(
                value = result.value,
                errorMessage = error,
                isInfinity = result.error == CalculatorError.VALUE_TOO_LARGE
            )
        }

        val rounded = roundResult(result.value)
        return Evaluation(
            value = rounded,
            formattedValue = formatResult(rounded)
        )
    }

    fun normalizeInputForDisplay(value: String): String =
        NumberFormatter.format(value, decimalSeparator, groupingSeparator, numberingSystem)

    fun decimalSeparator(): String = decimalSeparator

    private fun roundResult(result: BigDecimal): BigDecimal {
        var rounded = result.setScale(numberPrecision, RoundingMode.HALF_EVEN)
        if (numberIntoScientificNotation &&
            (rounded >= BigDecimal(9999) || rounded <= BigDecimal("0.1"))
        ) {
            // This is the same rounding path used by the original OpenCalc app.
            rounded = BigDecimal(String.format(Locale.US, "%.4g", result))
        }

        // Match OpenCalc's zero normalization after rounding.
        val tempResult = rounded.toString().replace("E-", "").replace("E", "")
        if (tempResult.all { it == '0' } || rounded.toString().startsWith("0E")) {
            rounded = BigDecimal.ZERO
        }
        return rounded
    }

    private fun formatResult(result: BigDecimal): String {
        var formatted = NumberFormatter.format(
            result.toString().replace(".", decimalSeparator),
            decimalSeparator,
            groupingSeparator,
            numberingSystem
        )

        if (!numberIntoScientificNotation ||
            !(result >= BigDecimal(9999) || result <= BigDecimal("0.1"))
        ) {
            val resultParts = result.toString().split('.')
            if (resultParts.size > 1) {
                val fraction = resultParts[1].trimEnd('0')
                val withoutZeros = if (fraction.isEmpty()) {
                    resultParts[0]
                } else {
                    "${resultParts[0]}.$fraction"
                }
                formatted = NumberFormatter.format(
                    withoutZeros.replace(".", decimalSeparator),
                    decimalSeparator,
                    groupingSeparator,
                    numberingSystem
                )
            }
        }
        return formatted
    }
}