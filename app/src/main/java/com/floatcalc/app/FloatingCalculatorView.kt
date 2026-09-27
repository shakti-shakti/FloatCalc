package com.floatcalc.app

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

private const val BACKSPACE_REPEAT_START_DELAY_MS = 350L
private const val BACKSPACE_REPEAT_INTERVAL_MS = 70L

/**
 * UI-only floating calculator shell.
 *
 * The keypad deliberately mirrors the supplied calculator reference. Calculation
 * behavior will be connected to the OpenCalc engine in a later pass.
 */
class FloatingCalculatorView(
    context: Context,
    private val onClose: () -> Unit,
    private val onMinimize: () -> Unit,
    private val onMaximize: () -> Unit,
    private val onDrag: (Float, Float) -> Unit,
    private val onResize: (Float, Float) -> Unit,
    private val onContentSizeChanged: () -> Unit = {}
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private val panelBackground = Color.parseColor("#11182C")
    private val displayBackground = Color.parseColor("#202943")
    private val textPrimary = Color.parseColor("#F4F7FF")
    private val textSecondary = Color.parseColor("#B9C9F7")
    private val blue = Color.parseColor("#62B7FF")
    private val blueFill = Color.parseColor("#1777D8")
    private val grayFill = Color.parseColor("#596378")
    private val greenFill = Color.parseColor("#22D889")
    private val pinkFill = Color.parseColor("#DB29F4")
    private val pressRipple = Color.parseColor("#80FFFFFF")
    private val numberFill = Color.parseColor("#1E1E1E")
    private val numberText = Color.WHITE
    private val decimalFill = Color.parseColor("#FACC15")
    private val decimalText = Color.parseColor("#1E1E1E")
    private val operatorFill = Color.parseColor("#22D3EE")
    private val operatorText = Color.parseColor("#0F172A")
    private val scientificFill = Color.parseColor("#A855F7")
    private val scientificText = Color.WHITE
    private val bracketFill = Color.parseColor("#F97316")
    private val bracketText = Color.WHITE
    private val dangerFill = Color.parseColor("#EF4444")
    private val dangerText = Color.WHITE
    private val equalsFill = Color.parseColor("#D946EF")
    private val equalsText = Color.WHITE
    private val inputNumberText = Color.WHITE
    private val inputDecimalText = Color.parseColor("#FACC15")
    private val inputOperatorText = Color.parseColor("#22D3EE")
    private val inputScientificText = Color.parseColor("#A855F7")
    private val inputBracketText = Color.parseColor("#F97316")

    private var inverseEnabled = false
    private var degreeModeEnabled = true
    private var degreeButton: Button? = null
    private var modeIndicator: TextView? = null
    private var expressionDisplay: EditText? = null
    private var expressionScrollView: HorizontalScrollView? = null
    private var resultDisplay: TextView? = null
    private var inputCopyButton: Button? = null
    private val dynamicKeys = mutableMapOf<String, Button>()
    private val expression = StringBuilder()
    private val engine = OpenCalcEngine()
    private val decimalSeparator = engine.decimalSeparator()
    private var equalLastAction = false
    private val backspaceRepeatHandler = Handler(Looper.getMainLooper())
    private var backspaceRepeatRunnable: Runnable? = null
    private var backspaceRepeatTriggered = false
    private lateinit var contentContainer: LinearLayout
    private lateinit var calculatorBody: LinearLayout
    private lateinit var formulaBody: FrameLayout
    private var calculatorModePill: ImageView? = null
    private var formulaModePill: ImageView? = null
    private var formulaBackButton: TextView? = null
    private var formulaModeEnabled = false
    private var formulaDetailOpen = false
    private var modeTransitionRunning = false

    private data class FormulaCard(
        val title: String,
        val imageRes: Int,
        val description: String
    )

    private val formulaCards = listOf(
        FormulaCard(
            "Trig Values, AP & GP",
            R.drawable.formula_trig_values_progressions,
            "Standard trigonometric values, arithmetic progression and geometric progression."
        ),
        FormulaCard(
            "Trig Identities & Angles",
            R.drawable.formula_trig_identities,
            "Core identities, compound angles, double angles and small-angle approximations."
        ),
        FormulaCard(
            "Quadrants & Sign Rules",
            R.drawable.formula_quadrants,
            "Quadrant signs and the related trigonometric function changes."
        ),
        FormulaCard(
            "Logarithms & Common Values",
            R.drawable.formula_logarithms,
            "Logarithm rules with frequently used common and natural log values."
        ),
        FormulaCard(
            "Maxima & Minima",
            R.drawable.formula_maxima_minima,
            "Graphical intuition and the derivative test for extrema."
        ),
        FormulaCard(
            "Science Conversions I",
            R.drawable.formula_science_conversions_1,
            "Volume, pressure, SI prefixes, light year, energy and the gas constant."
        ),
        FormulaCard(
            "Science Conversions II",
            R.drawable.formula_science_conversions_2,
            "Wave relation, force, absolute temperature, mass and temperature conversion."
        ),
        FormulaCard(
            "Physical & Chemical Constants",
            R.drawable.formula_physical_constants,
            "A reference table of important physical and chemical constants."
        ),
        FormulaCard(
            "Common Molar Masses",
            R.drawable.formula_molar_masses,
            "Common elements with atomic numbers, mass numbers and molar masses."
        )
    )

    private data class CalculatorKey(
        val id: String,
        val label: String
    )

    init {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = NeonRoundedDrawable(
            dp(28).toFloat(),
            intArrayOf(
                Color.parseColor("#263A63"),
                panelBackground,
                Color.parseColor("#1B1638")
            ),
            intArrayOf(
                Color.parseColor("#51C5FF"),
                Color.parseColor("#778FFF"),
                Color.parseColor("#E05BFF")
            ),
            dp(2).toFloat()
        )
        elevation = dp(24).toFloat()
        clipChildren = false
        clipToPadding = false
        buildUi()
    }

    private fun buildUi() {
        contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        addView(
            contentContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, 0, dp(4))
        }
        formulaBackButton = TextView(context).apply {
            text = "‹"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#D8E6FF"))
            contentDescription = "Back to formula cards"
            background = gradientRounded(
                intArrayOf(Color.parseColor("#405E9C"), Color.parseColor("#202F57")),
                Color.parseColor("#89B8FF"),
                12
            )
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(38)).apply {
                rightMargin = dp(5)
            }
        }.also {
            addPressFeedback(it)
            it.setOnClickListener { showFormulaGrid() }
        }
        header.addView(formulaBackButton)

        val headerIcon = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = gradientRounded(
                intArrayOf(Color.parseColor("#526C9F"), Color.parseColor("#1C2B4B")),
                Color.parseColor("#9BC6FF"),
                14
            )
            repeat(3) {
                val dotRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                repeat(3) {
                    dotRow.addView(View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.parseColor("#8DCCFF"))
                        }
                    }, LinearLayout.LayoutParams(dp(4), dp(4)).apply {
                        leftMargin = dp(2)
                        rightMargin = dp(2)
                        topMargin = dp(1)
                        bottomMargin = dp(1)
                    })
                }
                addView(dotRow, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(8)
                ))
            }
        }
        header.addView(headerIcon, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
            rightMargin = dp(10)
        })
        val modeSwitch = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
        }
        calculatorModePill = modePill(
            R.drawable.ic_mode_calculator,
            "Calculator mode",
            selected = true
        ) {
            showCalculatorMode()
        }
        formulaModePill = modePill(
            R.drawable.ic_mode_formula,
            "Formula cards",
            selected = false
        ) {
            showFormulaMode()
        }
        modeSwitch.addView(calculatorModePill, LinearLayout.LayoutParams(dp(38), dp(34)).apply {
            rightMargin = dp(3)
        })
        modeSwitch.addView(formulaModePill, LinearLayout.LayoutParams(dp(38), dp(34)).apply {
            leftMargin = dp(3)
        })
        header.addView(modeSwitch)
        installDragTouch(modeSwitch)
        header.addView(headerButton("−", Color.parseColor("#9BB5E8")) { onMinimize() })
        header.addView(headerButton("□", Color.parseColor("#C8DDFF")) { onMaximize() })
        header.addView(headerButton("×", Color.parseColor("#FF9CAF"), true) { onClose() })
        val headerGrip = TextView(context).apply {
            text = "⠿"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(40)).apply {
                leftMargin = dp(3)
            }
        }
        header.addView(headerGrip)
        installDragTouch(headerGrip)
        contentContainer.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ))

        calculatorBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentContainer.addView(
            calculatorBody,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val display = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = gradientRounded(
                intArrayOf(
                    Color.parseColor("#3A5281"),
                    displayBackground,
                    Color.parseColor("#172039")
                ),
                Color.parseColor("#9EC5FF"),
                24,
                1
            )
            elevation = dp(7).toFloat()
        }
        val displayTop = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        displayTop.addView(TextView(context).apply {
            text = "DEG"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f)
        }.also { modeIndicator = it })
        displayTop.addView(Button(context).apply {
            text = "Copy"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = gradientRounded(
                intArrayOf(Color.parseColor("#4F8CFF"), Color.parseColor("#2546A8")),
                Color.parseColor("#A8C9FF"),
                10,
                1
            )
            minWidth = 0
            minHeight = 0
            stateListAnimator = null
            setPadding(dp(14), 0, dp(14), 0)
            visibility = View.GONE
            contentDescription = "Copy selected equation"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)
            ).apply {
                leftMargin = dp(6)
            }
            addPressFeedback(this)
            setOnClickListener {
                copyText(expressionDisplay?.text?.toString().orEmpty())
                visibility = View.GONE
            }
        }.also { inputCopyButton = it })
        display.addView(displayTop)
        val expressionScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
            )
        }
        expressionScroll.addView(EditText(context).apply {
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(inputNumberText)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            showSoftInputOnFocus = false
            isFocusableInTouchMode = true
            isCursorVisible = true
            setSelectAllOnFocus(false)
            setTextIsSelectable(true)
            background = null
            setPadding(0, 0, 0, 0)
            val doubleTapDetector = GestureDetector(context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(event: MotionEvent): Boolean {
                        post { selectAllInputAndShowCopy(this@apply) }
                        return true
                    }
                }
            )
            setOnTouchListener { _, event ->
                doubleTapDetector.onTouchEvent(event)
                false
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(58)
            )
        }.also {
            expressionDisplay = it
            expressionScroll.post {
                it.minWidth = (expressionScroll.width - expressionScroll.paddingLeft -
                    expressionScroll.paddingRight).coerceAtLeast(0)
            }
        })
        display.addView(expressionScroll)
        expressionScrollView = expressionScroll
        display.addView(TextView(context).apply {
            text = ""
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
            gravity = Gravity.END
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)
            )
            isClickable = true
            setOnClickListener { copyText(text?.toString().orEmpty()) }
            contentDescription = "Copy result"
        }.also { resultDisplay = it })
        calculatorBody.addView(display, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(178)
        ).apply {
            topMargin = dp(2)
            bottomMargin = dp(10)
        })

        val displayHandle = View(context).apply {
            background = gradientRounded(
                intArrayOf(Color.parseColor("#9BE5FF"), Color.parseColor("#2DAEFF")),
                Color.TRANSPARENT,
                6
            )
        }
        calculatorBody.addView(displayHandle, LinearLayout.LayoutParams(dp(48), dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(10)
        })

        val rows = listOf(
            listOf(CalculatorKey("sqrt", "√"), CalculatorKey("pi", "π"), CalculatorKey("power", "^"), CalculatorKey("factorial", "!")),
            listOf(CalculatorKey("degree", "RAD"), CalculatorKey("sin", "sin"), CalculatorKey("cos", "cos"), CalculatorKey("tan", "tan")),
            listOf(CalculatorKey("inverse", "INV"), CalculatorKey("e", "e"), CalculatorKey("ln", "ln"), CalculatorKey("log", "log"), CalculatorKey("log2", "log₂")),
            listOf(CalculatorKey("clear", "AC"), CalculatorKey("parentheses", "( )"), CalculatorKey("percent", "%"), CalculatorKey("modulo", "#"), CalculatorKey("divide", "÷")),
            listOf(CalculatorKey("seven", "7"), CalculatorKey("eight", "8"), CalculatorKey("nine", "9"), CalculatorKey("multiply", "×")),
            listOf(CalculatorKey("four", "4"), CalculatorKey("five", "5"), CalculatorKey("six", "6"), CalculatorKey("subtract", "−")),
            listOf(CalculatorKey("one", "1"), CalculatorKey("two", "2"), CalculatorKey("three", "3"), CalculatorKey("add", "+")),
            listOf(CalculatorKey("zero", "0"), CalculatorKey("decimal", "."), CalculatorKey("backspace", "⌫"), CalculatorKey("equals", "="))
        )
        rows.forEachIndexed { rowIndex, labels ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            labels.forEach { key ->
                val button: View = if (key.id == "parentheses") {
                    bracketButtonGroup()
                } else {
                    calculatorButton(key, rowIndex)
                }
                row.addView(button, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                    leftMargin = dp(4)
                    rightMargin = dp(4)
                    topMargin = dp(4)
                    bottomMargin = if (rowIndex == rows.lastIndex) dp(2) else dp(4)
                })
            }
            calculatorBody.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(62)
            ))
        }

        formulaBody = FrameLayout(context).apply {
            visibility = View.GONE
            clipChildren = false
            clipToPadding = false
        }
        formulaBody.addView(
            buildFormulaGrid(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        contentContainer.addView(
            formulaBody,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val resizeHandle = ResizeHandleView(context).apply {
            var lastRawX = 0f
            var lastRawY = 0f
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        animate().scaleX(0.9f).scaleY(0.9f).setDuration(55).start()
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        onResize(event.rawX - lastRawX, event.rawY - lastRawY)
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    }
                }
                true
            }
        }
        addView(resizeHandle, LayoutParams(dp(30), dp(30), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(4)
            bottomMargin = dp(2)
        })

        // The full OpenCalc evaluator is connected below the reference panel.
        isClickable = true
    }

    private fun modePill(
        iconRes: Int,
        description: String,
        selected: Boolean,
        action: () -> Unit
    ): ImageView = ImageView(context).apply {
        setImageResource(iconRes)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(7), dp(5), dp(7), dp(5))
        background = modePillBackground(selected)
        isClickable = true
        addPressFeedback(this)
        setOnClickListener { action() }
    }

    private fun modePillBackground(selected: Boolean): Drawable =
        if (selected) {
            gradientRounded(
                intArrayOf(Color.parseColor("#4F9EFF"), Color.parseColor("#244AAB")),
                Color.parseColor("#B8D9FF"),
                12,
                1
            )
        } else {
            gradientRounded(
                intArrayOf(Color.parseColor("#35466E"), Color.parseColor("#1D2847")),
                Color.parseColor("#647BAE"),
                12,
                1
            )
        }

    private fun updateModePills() {
        calculatorModePill?.background = modePillBackground(!formulaModeEnabled)
        formulaModePill?.background = modePillBackground(formulaModeEnabled)
    }

    private fun showCalculatorMode() {
        if (!formulaModeEnabled || modeTransitionRunning) return
        formulaDetailOpen = false
        formulaBackButton?.visibility = View.GONE
        formulaModeEnabled = false
        updateModePills()
        animateModeTransition(calculatorBody, formulaBody)
    }

    private fun showFormulaMode() {
        if (formulaModeEnabled || modeTransitionRunning) return
        formulaModeEnabled = true
        formulaDetailOpen = false
        formulaBackButton?.visibility = View.GONE
        updateModePills()
        animateModeTransition(formulaBody, calculatorBody)
    }

    private fun animateModeTransition(incoming: View, outgoing: View) {
        modeTransitionRunning = true
        outgoing.pivotX = outgoing.width / 2f
        outgoing.pivotY = outgoing.height / 2f
        incoming.pivotX = incoming.width / 2f
        incoming.pivotY = incoming.height / 2f
        outgoing.animate()
            .rotationY(90f)
            .alpha(0f)
            .setDuration(180)
            .withEndAction {
                outgoing.visibility = View.GONE
                outgoing.rotationY = 0f
                incoming.visibility = View.VISIBLE
                incoming.alpha = 0f
                incoming.rotationY = -90f
                incoming.animate()
                    .rotationY(0f)
                    .alpha(1f)
                    .setDuration(180)
                    .withEndAction {
                        modeTransitionRunning = false
                        notifyContentSizeChanged()
                    }
                    .start()
            }
            .start()
    }

    private fun showFormulaGrid() {
        if (!formulaModeEnabled || modeTransitionRunning) return
        formulaDetailOpen = false
        formulaBackButton?.visibility = View.GONE
        swapFormulaContent(buildFormulaGrid())
    }

    private fun openFormulaCard(card: FormulaCard) {
        if (!formulaModeEnabled || modeTransitionRunning) return
        formulaDetailOpen = true
        formulaBackButton?.visibility = View.VISIBLE
        swapFormulaContent(buildFormulaDetail(card))
    }

    private fun swapFormulaContent(next: View) {
        val old = formulaBody.getChildAt(0)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        formulaBody.addView(next, params)
        next.pivotX = next.width / 2f
        next.pivotY = next.height / 2f
        next.alpha = 0f
        next.rotationY = -90f
        if (old == null) {
            next.alpha = 1f
            next.rotationY = 0f
            notifyContentSizeChanged()
            return
        }
        old.pivotX = old.width / 2f
        old.pivotY = old.height / 2f
        old.animate()
            .rotationY(90f)
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                formulaBody.removeView(old)
                next.visibility = View.VISIBLE
                next.animate()
                    .rotationY(0f)
                    .alpha(1f)
                    .setDuration(150)
                    .withEndAction { notifyContentSizeChanged() }
                    .start()
            }
            .start()
    }

    private fun buildFormulaGrid(): View {
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        val stack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        stack.addView(TextView(context).apply {
            text = "FORMULA VAULT"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#D8E7FF"))
            background = gradientRounded(
                intArrayOf(Color.parseColor("#3C5B9C"), Color.parseColor("#241E5A")),
                Color.parseColor("#9DC9FF"),
                14,
                1
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            ).apply {
                bottomMargin = dp(7)
            }
        })

        formulaCards.chunked(2).forEach { rowCards ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            rowCards.forEach { card ->
                row.addView(
                    formulaCardView(card),
                    LinearLayout.LayoutParams(0, dp(136), 1f).apply {
                        leftMargin = dp(3)
                        rightMargin = dp(3)
                        bottomMargin = dp(7)
                    }
                )
            }
            if (rowCards.size == 1) {
                row.addView(
                    View(context),
                    LinearLayout.LayoutParams(0, dp(136), 1f).apply {
                        leftMargin = dp(3)
                        rightMargin = dp(3)
                    }
                )
            }
            stack.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(143)
                )
            )
        }
        scroll.addView(
            stack,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return scroll
    }

    private fun formulaCardView(card: FormulaCard): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = gradientRounded(
                intArrayOf(Color.parseColor("#243967"), Color.parseColor("#171A37")),
                Color.parseColor("#638DDE"),
                16,
                1
            )
            elevation = dp(5).toFloat()
            isClickable = true
            contentDescription = "Open ${card.title}"
            addPressFeedback(this)
            setOnClickListener { openFormulaCard(card) }
            addView(ImageView(context).apply {
                setImageBitmap(decodeFormulaThumbnail(card.imageRes))
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = card.title
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(96)
                )
            })
            addView(TextView(context).apply {
                text = card.title
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 2
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(28)
                )
            })
        }

    private fun decodeFormulaThumbnail(imageRes: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = 4
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeResource(resources, imageRes, options)
    }

    private fun buildFormulaDetail(card: FormulaCard): View {
        val drawable = resources.getDrawable(card.imageRes, null)
        val availableWidth = (if (measuredWidth > 0) measuredWidth else width)
            .coerceAtLeast(dp(220)) - dp(8)
        val imageHeight = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            (availableWidth.coerceAtLeast(dp(220)) * drawable.intrinsicHeight.toFloat() /
                drawable.intrinsicWidth.toFloat()).roundToInt()
        } else {
            dp(220)
        }
        return FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#0B1020"))
            addView(ZoomableFormulaImageView(context).apply {
            setImageResource(card.imageRes)
            contentDescription = "${card.title}. Pinch to zoom and drag to inspect."
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                imageHeight.coerceAtLeast(dp(170))
            )
        })
        }
    }

    private fun notifyContentSizeChanged() {
        post {
            requestLayout()
            onContentSizeChanged()
        }
    }

    private class ZoomableFormulaImageView(context: Context) : ImageView(context) {
        private val renderMatrix = Matrix()
        private var zoom = 1f
        private var panX = 0f
        private var panY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private val doubleTapDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onDoubleTap(event: MotionEvent): Boolean {
                    val nextZoom = if (zoom < 1.9f) 2.5f else 1f
                    setZoomAt(nextZoom, event.x, event.y)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    return true
                }
            }
        )
        private val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    setZoomAt(
                        (zoom * detector.scaleFactor).coerceIn(1f, 4f),
                        detector.focusX,
                        detector.focusY
                    )
                    applyImageMatrix()
                    return true
                }
            }
        )

        init {
            scaleType = ScaleType.MATRIX
            isClickable = true
            setBackgroundColor(Color.parseColor("#0B1020"))
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            applyImageMatrix()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            doubleTapDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && event.pointerCount == 1 && zoom > 1f) {
                        panX += event.x - lastX
                        panY += event.y - lastY
                        applyImageMatrix()
                    }
                    lastX = event.x
                    lastY = event.y
                }
            }
            return true
        }

        private fun setZoomAt(targetZoom: Float, focusX: Float, focusY: Float) {
            val oldZoom = zoom
            val newZoom = targetZoom.coerceIn(1f, 4f)
            if (oldZoom <= 0f) {
                zoom = newZoom
                return
            }
            val ratio = newZoom / oldZoom
            panX += (focusX - width / 2f) * (1f - ratio)
            panY += (focusY - height / 2f) * (1f - ratio)
            zoom = newZoom
            applyImageMatrix()
        }

        private fun applyImageMatrix() {
            val image = drawable ?: return
            if (width <= 0 || height <= 0 || image.intrinsicWidth <= 0 ||
                image.intrinsicHeight <= 0
            ) {
                return
            }
            val baseScale = minOf(
                width.toFloat() / image.intrinsicWidth.toFloat(),
                height.toFloat() / image.intrinsicHeight.toFloat()
            )
            val scale = baseScale * zoom
            val displayedWidth = image.intrinsicWidth * scale
            val displayedHeight = image.intrinsicHeight * scale
            val maxPanX = ((displayedWidth - width) / 2f).coerceAtLeast(0f)
            val maxPanY = ((displayedHeight - height) / 2f).coerceAtLeast(0f)
            panX = panX.coerceIn(-maxPanX, maxPanX)
            panY = panY.coerceIn(-maxPanY, maxPanY)
            renderMatrix.reset()
            renderMatrix.setScale(scale, scale)
            renderMatrix.postTranslate(
                (width - displayedWidth) / 2f + panX,
                (height - displayedHeight) / 2f + panY
            )
            imageMatrix = renderMatrix
        }
    }

    private fun bracketButtonGroup(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
        clipToPadding = false
        addView(
            calculatorButton(CalculatorKey("openParenthesis", "("), 0).apply {
                contentDescription = "Open parenthesis"
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                rightMargin = dp(2)
            }
        )
        addView(
            calculatorButton(CalculatorKey("closeParenthesis", ")"), 0).apply {
                contentDescription = "Close parenthesis"
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = dp(2)
            }
        )
    }

    private fun headerButton(
        label: String,
        color: Int,
        danger: Boolean = false,
        action: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        textSize = 21f
        gravity = Gravity.CENTER
        setTextColor(color)
        background = gradientRounded(
            if (danger) {
                intArrayOf(Color.parseColor("#D86290"), Color.parseColor("#6B365C"))
            } else {
                intArrayOf(Color.parseColor("#607BAE"), Color.parseColor("#283858"))
            },
            if (danger) Color.parseColor("#FF9DBA") else Color.parseColor("#96B8F4"),
            12
        )
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(38)).apply {
            leftMargin = dp(5)
        }
        addPressFeedback(this)
        setOnClickListener { action() }
    }

    private fun calculatorButton(key: CalculatorKey, row: Int): Button = Button(context).apply {
        text = if (key.id == "backspace") "" else key.label
        textSize = when {
            key.label.length > 2 -> 14f
            key.label == "√" || key.label == "π" -> 22f
            else -> 20f
        }
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(buttonTextColor(key))
        isAllCaps = false
        minWidth = 0
        minHeight = 0
        includeFontPadding = false
        stateListAnimator = null
        background = buttonBackground(key)
        elevation = dp(if (key.label == "=" || key.label == "AC") 7 else 4).toFloat()
        if (key.id == "backspace") {
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.calculator_backspace, 0, 0, 0)
            compoundDrawables.forEach { it?.setTint(buttonTextColor(key)) }
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            compoundDrawablePadding = 0
        }
        addPressFeedback(
            this,
            onDown = if (key.id == "backspace") {
                { startBackspaceRepeat() }
            } else {
                null
            },
            onUp = if (key.id == "backspace") {
                { stopBackspaceRepeat() }
            } else {
                null
            }
        )
        dynamicKeys[key.id] = this
        if (key.id == "degree") degreeButton = this
        setOnClickListener { handleKeyPress(key) }
    }

    private fun buttonBackground(key: CalculatorKey): Drawable = RippleDrawable(
        ColorStateList.valueOf(pressRipple),
        NeonRoundedDrawable(
            dp(28).toFloat(),
            buttonFillColors(key),
            buttonBorderColors(key),
            dp(2).toFloat(),
            buttonGlowColor(key),
            dp(5).toFloat()
        ),
        null
    )

    private fun buttonTextColor(key: CalculatorKey): Int = when {
        key.id == "decimal" -> decimalText
        key.id == "equals" -> equalsText
        key.id == "clear" || key.id == "backspace" -> dangerText
        key.id in setOf("parentheses", "openParenthesis", "closeParenthesis", "percent") -> bracketText
        key.id in setOf("divide", "multiply", "subtract", "add", "modulo") -> operatorText
        key.id in setOf(
            "sqrt", "pi", "power", "factorial", "degree", "sin", "cos", "tan",
            "inverse", "e", "ln", "log", "log2"
        ) -> scientificText
        else -> numberText
    }

    private fun buttonFillColors(key: CalculatorKey): IntArray = when {
        key.id == "decimal" -> intArrayOf(
            Color.parseColor("#FFF59D"),
            decimalFill,
            Color.parseColor("#EAB308")
        )
        key.id == "equals" -> intArrayOf(
            Color.parseColor("#F5A3F8"),
            equalsFill,
            Color.parseColor("#A21CAF")
        )
        key.id == "clear" || key.id == "backspace" -> intArrayOf(
            Color.parseColor("#FCA5A5"),
            dangerFill,
            Color.parseColor("#B91C1C")
        )
        key.id in setOf("parentheses", "openParenthesis", "closeParenthesis", "percent") -> intArrayOf(
            Color.parseColor("#FDBA74"),
            bracketFill,
            Color.parseColor("#C2410C")
        )
        key.id in setOf("divide", "multiply", "subtract", "add", "modulo") -> intArrayOf(
            Color.parseColor("#67E8F9"),
            operatorFill,
            Color.parseColor("#0891B2")
        )
        key.id in setOf(
            "sqrt", "pi", "power", "factorial", "degree", "sin", "cos", "tan",
            "inverse", "e", "ln", "log", "log2"
        ) -> intArrayOf(
            Color.parseColor("#D8B4FE"),
            scientificFill,
            Color.parseColor("#7E22CE")
        )
        else -> intArrayOf(
            Color.parseColor("#6B7280"),
            Color.parseColor("#3F4650"),
            numberFill
        )
    }

    private fun buttonBorderColors(key: CalculatorKey): IntArray = when {
        key.id == "decimal" -> intArrayOf(Color.WHITE, Color.parseColor("#FFF7AE"))
        key.id == "equals" -> intArrayOf(Color.WHITE, Color.parseColor("#F5D0FE"))
        key.id == "clear" || key.id == "backspace" ->
            intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#FCA5A5"))
        key.id in setOf("parentheses", "openParenthesis", "closeParenthesis", "percent") ->
            intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#FED7AA"))
        key.id in setOf("divide", "multiply", "subtract", "add", "modulo") ->
            intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#A5F3FC"))
        key.id in setOf(
            "sqrt", "pi", "power", "factorial", "degree", "sin", "cos", "tan",
            "inverse", "e", "ln", "log", "log2"
        ) -> intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#E9D5FF"))
        else -> intArrayOf(Color.parseColor("#E5E7EB"), Color.parseColor("#94A3B8"))
    }

    private fun buttonGlowColor(key: CalculatorKey): Int = when {
        key.id == "decimal" -> Color.parseColor("#FACC15")
        key.id == "equals" -> Color.parseColor("#D946EF")
        key.id == "clear" || key.id == "backspace" -> Color.parseColor("#EF4444")
        key.id in setOf("parentheses", "openParenthesis", "closeParenthesis", "percent") ->
            Color.parseColor("#F97316")
        key.id in setOf("divide", "multiply", "subtract", "add", "modulo") ->
            Color.parseColor("#22D3EE")
        key.id in setOf(
            "sqrt", "pi", "power", "factorial", "degree", "sin", "cos", "tan",
            "inverse", "e", "ln", "log", "log2"
        ) -> Color.parseColor("#A855F7")
        else -> Color.parseColor("#9CA3AF")
    }

    /**
     * Gives each key the short travel and release animation of a physical
     * keyboard key. Returning false preserves Android's normal click dispatch.
     */
    private fun addPressFeedback(
        view: View,
        onDown: (() -> Unit)? = null,
        onUp: (() -> Unit)? = null
    ) {
        view.isHapticFeedbackEnabled = true
        view.setOnTouchListener { pressedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onDown?.invoke()
                    pressedView.animate()
                        .scaleX(0.94f)
                        .scaleY(0.94f)
                        .translationY(dp(2).toFloat())
                        .setDuration(55)
                        .start()
                    // VIRTUAL_KEY is available on the app's API 21 minimum and
                    // gives the same short key-tap feedback used by OpenCalc.
                    pressedView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onUp?.invoke()
                    pressedView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .setDuration(100)
                        .start()
                }
            }
            false
        }
    }

    private fun startBackspaceRepeat() {
        stopBackspaceRepeat()
        backspaceRepeatTriggered = false
        val repeat = object : Runnable {
            override fun run() {
                deleteLastInput()
                backspaceRepeatTriggered = true
                backspaceRepeatHandler.postDelayed(this, BACKSPACE_REPEAT_INTERVAL_MS)
            }
        }
        backspaceRepeatRunnable = repeat
        backspaceRepeatHandler.postDelayed(repeat, BACKSPACE_REPEAT_START_DELAY_MS)
    }

    private fun stopBackspaceRepeat() {
        backspaceRepeatRunnable?.let { backspaceRepeatHandler.removeCallbacks(it) }
        backspaceRepeatRunnable = null
    }

    /**
     * Matches the VideoAnnotation drag gesture: a short tap does nothing here,
     * while a movement past the threshold moves the complete floating panel.
     */
    private fun installDragTouch(view: View) {
        var downRawX = 0f
        var downRawY = 0f
        var lastRawX = 0f
        var lastRawY = 0f
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDx = event.rawX - downRawX
                    val totalDy = event.rawY - downRawY
                    if (!moved && totalDx * totalDx + totalDy * totalDy > dp(6) * dp(6)) {
                        moved = true
                    }
                    if (moved) {
                        onDrag(event.rawX - lastRawX, event.rawY - lastRawY)
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    moved = false
                    true
                }
                else -> false
            }
        }
    }

    private fun handleKeyPress(key: CalculatorKey) {
        when (key.id) {
            "inverse" -> toggleInverseLabels()
            "degree" -> toggleDegreeMode()
            "clear" -> {
                expression.setLength(0)
                equalLastAction = false
                updateDisplay(showError = false, cursorPosition = 0)
            }
            "backspace" -> {
                if (backspaceRepeatTriggered) {
                    backspaceRepeatTriggered = false
                } else {
                    deleteLastInput()
                }
            }
            "equals" -> {
                evaluateEquals()
            }
            "sqrt" -> appendInput(if (inverseEnabled) "^2" else "√")
            "sin" -> appendInput(if (inverseEnabled) "sin⁻¹(" else "sin(")
            "cos" -> appendInput(if (inverseEnabled) "cos⁻¹(" else "cos(")
            "tan" -> appendInput(if (inverseEnabled) "tan⁻¹(" else "tan(")
            "ln" -> appendInput(if (inverseEnabled) "exp(" else "ln(")
            "log" -> appendInput(if (inverseEnabled) "10^" else "log(")
            "log2" -> appendInput(if (inverseEnabled) "2^" else "log₂(")
            "parentheses" -> appendParenthesis()
            "openParenthesis" -> appendOpenParenthesis()
            "closeParenthesis" -> appendCloseParenthesis()
            "e" -> appendInput("e")
            "pi" -> appendInput("π")
            "factorial" -> appendInput("!")
            "power" -> appendInput("^")
            "percent" -> appendInput("%")
            "modulo" -> appendInput("#")
            "divide" -> appendInput("÷")
            "multiply" -> appendInput("×")
            "subtract" -> appendInput("−")
            "add" -> appendInput("+")
            "decimal" -> appendDecimal()
            else -> appendInput(key.label)
        }
    }

    private fun appendDecimal() {
        val current = expression.toString()
        val cursor = currentCursorPosition()
        val left = current.substring(0, cursor)
        val start = left.indexOfLast { it in "+−×÷^#()" } + 1
        val end = current.indexOfFirstFrom(cursor) { it in "+−×÷^#()" }
            .takeIf { it >= 0 } ?: current.length
        if (current.substring(start, end).contains(decimalSeparator)) return
        appendInput(decimalSeparator)
    }

    private fun appendParenthesis() {
        val cursor = currentCursorPosition()
        val current = expression.toString()
        if (equalLastAction) {
            equalLastAction = false
        }
        val previous = current.getOrNull(cursor - 1)
        val next = current.getOrNull(cursor)
        val openBefore = current.substring(0, cursor).count { it == '(' }
        val closeBefore = current.substring(0, cursor).count { it == ')' }
        val value = if (
            cursor == 0 ||
            previous == null ||
            previous in " +−×÷^("
        ) {
            "("
        } else if (openBefore > closeBefore && (next == null || next !in "+−×÷^")) {
            ")"
        } else {
            "("
        }
        expression.insert(cursor, value)
        updateDisplay(showError = false, cursorPosition = cursor + value.length)
    }

    private fun appendOpenParenthesis() {
        appendInput("(")
    }

    private fun appendCloseParenthesis() {
        val cursor = currentCursorPosition()
        val current = expression.toString()
        if (current.isEmpty() || equalLastAction) {
            updateDisplay(showError = false)
            return
        }

        val previous = current.getOrNull(cursor - 1)
        val openBefore = current.substring(0, cursor).count { it == '(' }
        val closeBefore = current.substring(0, cursor).count { it == ')' }
        if (openBefore <= closeBefore || previous == null || previous in " +−×÷^(") {
            updateDisplay(showError = false)
            return
        }

        expression.insert(cursor, ')')
        updateDisplay(showError = false, cursorPosition = cursor + 1)
    }

    private fun appendInput(value: String) {
        if (value in setOf("+", "−", "×", "÷", "^", "%", "!", "#")) {
            appendSymbol(value)
            return
        }
        var cursor = currentCursorPosition()
        if (equalLastAction) {
            if (value.length == 1 && (value[0].isDigit() || value == decimalSeparator)) {
                expression.setLength(0)
                cursor = 0
            } else {
                cursor = expression.length
            }
            equalLastAction = false
        }
        expression.insert(cursor, value)
        updateDisplay(showError = false, cursorPosition = cursor + value.length)
    }

    private fun appendSymbol(symbol: String) {
        equalLastAction = false
        val current = expression.toString()
        val cursor = currentCursorPosition()
        if (current.isEmpty()) {
            if (symbol == "−") {
                expression.append(symbol)
                updateDisplay(showError = false, cursorPosition = 1)
            } else {
                updateDisplay(showError = false, cursorPosition = 0)
            }
            return
        }

        val previous = current.getOrNull(cursor - 1)?.toString().orEmpty()
        val next = current.getOrNull(cursor)?.toString().orEmpty()
        val previousBeforeSymbol = current.getOrNull(cursor - 2)?.toString().orEmpty()

        // OpenCalc ignores a repeated symbol and does not allow two binary
        // symbols in a row, except for a unary minus after ×, ÷, ^, or #.
        if (symbol == previous ||
            symbol == next ||
            previous == "√" ||
            previous == decimalSeparator ||
            (previous == "(" && symbol != "−") ||
            (previousBeforeSymbol in setOf("+", "−", "×", "÷") &&
                previous in setOf("+", "−", "×", "÷"))
        ) {
            updateDisplay(showError = false)
            return
        }

        val replaceableOperators = setOf("+", "−", "×", "÷", "^", "#")
        if (previous in replaceableOperators) {
            if (symbol == "−" && previous !in setOf("+", "−")) {
                expression.insert(cursor, symbol)
                updateDisplay(showError = false, cursorPosition = cursor + 1)
            } else {
                expression.setCharAt(cursor - 1, symbol.single())
                updateDisplay(showError = false, cursorPosition = cursor)
            }
        } else if (next in setOf("+", "−", "×", "÷", "^", "%", "!") && symbol != "%") {
            if (cursor > 0 && previous != "(") {
                expression.setCharAt(cursor, symbol.single())
                updateDisplay(showError = false, cursorPosition = cursor + 1)
            } else if (symbol == "+") {
                expression.deleteCharAt(cursor)
                updateDisplay(showError = false, cursorPosition = cursor)
            }
        } else {
            expression.insert(cursor, symbol)
            updateDisplay(showError = false, cursorPosition = cursor + symbol.length)
        }
    }

    private fun deleteLastInput() {
        if (expression.isEmpty()) return

        val functions = listOf(
            "cos⁻¹(", "sin⁻¹(", "tan⁻¹(", "cos(", "sin(", "tan(",
            "ln(", "log(", "log₂(", "exp("
        )
        val current = expression.toString()
        var cursor = currentCursorPosition()
        if (equalLastAction) cursor = current.length
        if (cursor == 0) return

        var deleteEnd = cursor
        while (deleteEnd > 0 && current[deleteEnd - 1] == ',') deleteEnd--
        if (deleteEnd == 0) return

        val left = current.substring(0, deleteEnd)
        val function = functions.firstOrNull { left.endsWith(it) }
        val deleteStart = if (function != null) {
            deleteEnd - function.length
        } else {
            deleteEnd - 1
        }
        expression.delete(deleteStart, cursor)
        equalLastAction = false
        updateDisplay(showError = false, cursorPosition = deleteStart)
    }

    private fun currentCursorPosition(): Int {
        val cursor = expressionDisplay?.selectionStart ?: expression.length
        return cursor.coerceIn(0, expression.length)
    }

    private fun selectAllInputAndShowCopy(display: EditText) {
        if (display.text.isNullOrEmpty()) return

        display.requestFocus()
        display.selectAll()
        inputCopyButton?.visibility = View.VISIBLE
    }

    private fun copyText(value: String) {
        if (value.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Calculator", value))
    }

    private fun String.indexOfFirstFrom(
        startIndex: Int,
        predicate: (Char) -> Boolean
    ): Int {
        for (index in startIndex until length) {
            if (predicate(this[index])) return index
        }
        return -1
    }

    private fun evaluateEquals() {
        if (expression.isEmpty()) return
        val evaluation = engine.evaluate(expression.toString(), degreeModeEnabled)
        if (evaluation.isSuccess) {
            expression.setLength(0)
            expression.append(evaluation.formattedValue)
            equalLastAction = true
            setExpressionDisplay(cursorPosition = expression.length)
            resultDisplay?.text = ""
            expressionScrollView?.post {
                expressionScrollView?.fullScroll(View.FOCUS_RIGHT)
            }
        } else {
            resultDisplay?.text = evaluation.errorMessage.orEmpty()
        }
    }

    private fun updateDisplay(showError: Boolean, cursorPosition: Int? = null) {
        setExpressionDisplay(cursorPosition)
        expressionScrollView?.post {
            expressionScrollView?.fullScroll(View.FOCUS_RIGHT)
        }
        if (showError) {
            val evaluation = engine.evaluate(expression.toString(), degreeModeEnabled)
            resultDisplay?.text = evaluation.errorMessage.orEmpty()
        } else {
            updateResultPreview()
        }
    }

    private fun setExpressionDisplay(cursorPosition: Int? = null) {
        val display = expressionDisplay ?: return
        inputCopyButton?.visibility = View.GONE
        val oldCursor = display.selectionStart
        val value = expression.toString()
        val styled = SpannableString(value)
        value.forEachIndexed { index, character ->
            val color = when {
                character.isDigit() -> inputNumberText
                character.toString() == decimalSeparator -> inputDecimalText
                character in setOf('+', '−', '×', '÷', '#') -> inputOperatorText
                character in setOf('(', ')', '%') -> inputBracketText
                character.isLetter() ||
                    character in setOf('√', 'π', '^', '!', '⁻') -> inputScientificText
                else -> inputNumberText
            }
            styled.setSpan(
                ForegroundColorSpan(color),
                index,
                index + 1,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        display.setText(styled, TextView.BufferType.SPANNABLE)
        val desiredCursor = (cursorPosition ?: oldCursor.takeIf { it >= 0 } ?: value.length)
            .coerceIn(0, value.length)
        display.setSelection(desiredCursor)
    }

    private fun updateResultPreview() {
        val current = expression.toString()
        if (current.isEmpty()) {
            resultDisplay?.text = ""
            return
        }
        val evaluation = engine.evaluatePreview(current, degreeModeEnabled)
        resultDisplay?.text = when {
            evaluation.isSuccess && evaluation.formattedValue != current ->
                evaluation.formattedValue
            evaluation.isInfinity -> evaluation.errorMessage.orEmpty()
            else -> ""
        }
    }

    private fun gradientRounded(
        colors: IntArray,
        stroke: Int,
        radius: Int,
        strokeWidth: Int = 1
    ): GradientDrawable {
        val drawable = if (colors.size == 1) {
            GradientDrawable().apply { setColor(colors[0]) }
        } else {
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                colors
            )
        }
        return drawable.apply {
            cornerRadius = dp(radius).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(strokeWidth), stroke)
        }
    }

    private fun toggleInverseLabels() {
        inverseEnabled = !inverseEnabled
        setDynamicLabel("sqrt", if (inverseEnabled) "x²" else "√")
        setDynamicLabel("sin", if (inverseEnabled) "sin⁻¹" else "sin")
        setDynamicLabel("cos", if (inverseEnabled) "cos⁻¹" else "cos")
        setDynamicLabel("tan", if (inverseEnabled) "tan⁻¹" else "tan")
        setDynamicLabel("ln", if (inverseEnabled) "e^" else "ln")
        setDynamicLabel("log", if (inverseEnabled) "10^" else "log")
        setDynamicLabel("log2", if (inverseEnabled) "2^" else "log₂")
    }

    private fun setDynamicLabel(id: String, label: String) {
        dynamicKeys[id]?.apply {
            text = label
            textSize = when {
                label.length >= 5 -> 14f
                label.length >= 3 -> 16f
                label == "√" || label == "π" -> 22f
                else -> 20f
            }
        }
    }

    private fun toggleDegreeMode() {
        degreeModeEnabled = !degreeModeEnabled
        degreeButton?.text = if (degreeModeEnabled) "RAD" else "DEG"
        modeIndicator?.text = if (degreeModeEnabled) "DEG" else "RAD"
        updateResultPreview()
    }

    private class NeonRoundedDrawable(
        private val radius: Float,
        private val fillColors: IntArray,
        private val borderColors: IntArray,
        private val borderWidth: Float,
        private val glowColor: Int = Color.TRANSPARENT,
        private val glowWidth: Float = 0f
    ) : android.graphics.drawable.Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = glowWidth
            color = glowColor
            alpha = 135
        }
        private val boundsRect = RectF()

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            val inset = borderWidth / 2f
            boundsRect.set(
                bounds.left + inset,
                bounds.top + inset,
                bounds.right - inset,
                bounds.bottom - inset
            )
            val left = bounds.left.toFloat()
            val right = bounds.right.toFloat()
            val top = bounds.top.toFloat()
            val bottom = bounds.bottom.toFloat()
            fillPaint.shader = LinearGradient(
                left, top, right, bottom, fillColors, null, Shader.TileMode.CLAMP
            )
            borderPaint.shader = LinearGradient(
                left, top, right, bottom, borderColors, null, Shader.TileMode.CLAMP
            )
        }

        override fun draw(canvas: Canvas) {
            if (glowColor != Color.TRANSPARENT && glowWidth > 0f) {
                canvas.drawRoundRect(boundsRect, radius, radius, glowPaint)
            }
            canvas.drawRoundRect(boundsRect, radius, radius, fillPaint)
            canvas.drawRoundRect(boundsRect, radius, radius, borderPaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            borderPaint.alpha = alpha
            glowPaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            borderPaint.colorFilter = colorFilter
            glowPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Drawable opacity is retained for API 21 compatibility")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * The reference uses two slim diagonal strokes instead of a text glyph.
     * Drawing them avoids font-dependent differences across Android devices.
     */
    private class ResizeHandleView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D6E0FF")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.SQUARE
            alpha = 235
        }

        private fun dp(value: Float): Float = value * density

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.strokeWidth = dp(2f)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawLine(dp(6f), h - dp(7f), w - dp(7f), dp(6f), paint)
            canvas.drawLine(dp(13f), h - dp(5f), w - dp(5f), dp(13f), paint)
        }
    }
}