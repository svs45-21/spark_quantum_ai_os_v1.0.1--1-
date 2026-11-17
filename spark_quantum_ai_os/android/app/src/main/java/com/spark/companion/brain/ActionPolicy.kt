package com.spark.companion.brain

/**
 * Decides which actions need the user's explicit go-ahead before Spark does
 * them. Pure logic, no Android types, so it is unit-tested on the JVM.
 */
object ActionPolicy {

    /** Tools that always need a yes, whatever their arguments. */
    private val ALWAYS_CONFIRM = setOf("make_call", "send_sms")

    /**
     * Words on a button or field that mean tapping it commits to something:
     * sending, paying, deleting, granting access, signing out.
     */
    private val RISKY_LABEL = Regex(
        "\\b(" +
            "send|sent|post|publish|share|reply|submit|tweet|" +
            "pay|paid|buy|purchase|order|checkout|check out|donate|subscribe|transfer|withdraw|" +
            "confirm|proceed|place|book|" +
            "delete|remove|erase|clear|discard|uninstall|format|reset|wipe|" +
            "allow|grant|accept|agree|authori[sz]e|permit|turn on|enable|install|" +
            "sign out|log out|logout|block|report|unfriend|unfollow|cancel subscription|" +
            "call|dial" +
            ")\\b",
        RegexOption.IGNORE_CASE
    )

    /** Apps where every tap or keystroke is confirmed: money and credentials. */
    private val SENSITIVE_PACKAGE = Regex(
        "(bank|paisa|phonepe|paytm|bhim|upi|wallet|pay\\b|payments?|paypal|venmo|cashapp|" +
            "razorpay|mobikwik|freecharge|cred\\.|zerodha|groww|upstox|binance|coinbase|" +
            "authenticator|password|keepass|bitwarden|lastpass|1password|" +
            "com\\.android\\.vending|packageinstaller|permissioncontroller)",
        RegexOption.IGNORE_CASE
    )

    fun alwaysConfirm(tool: String): Boolean = tool in ALWAYS_CONFIRM

    fun isRiskyLabel(label: String?): Boolean =
        !label.isNullOrBlank() && RISKY_LABEL.containsMatchIn(label)

    fun isSensitivePackage(pkg: String?): Boolean =
        !pkg.isNullOrBlank() && SENSITIVE_PACKAGE.containsMatchIn(pkg)

    /**
     * Whether a screen interaction needs confirmation.
     * @param label visible text of the thing being tapped or typed into
     * @param pkg package of the app on screen
     * @param submits true when the action also presses enter / submits
     */
    fun screenActionNeedsConfirm(label: String?, pkg: String?, submits: Boolean = false): Boolean =
        submits || isSensitivePackage(pkg) || isRiskyLabel(label)

    private val YES = Regex(
        "^(yes|yeah|yep|yup|sure|ok|okay|go ahead|do it|confirm|confirmed|proceed|allow|send it|" +
            "please do|go for it|absolutely|affirmative|haan|han|ha|theek hai|kar do)\\b",
        RegexOption.IGNORE_CASE
    )
    private val NO = Regex(
        "^(no|nope|nah|don't|do not|dont|stop|cancel|deny|never|wait|hold on|negative|nahi|nahin|mat karo|ruk)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Interprets a spoken answer to "shall I go ahead?". Null when unclear. */
    fun parseYesNo(heard: String?): Boolean? {
        val text = heard?.trim()?.trimStart(',', '.', ' ')?.lowercase() ?: return null
        if (text.isEmpty()) return null
        // "no" wins if both appear at the start ("no, yes..." is still a no).
        if (NO.containsMatchIn(text)) return false
        if (YES.containsMatchIn(text)) return true
        return null
    }
}
