package nl.r1a.grassmap.control

/**
 * The control channel's one diagnostic sink. The app binds it to logcat; a test
 * binds it to a list, so this module has no Android logging dependency.
 *
 * A refusal is logged even though it is a normal answer: the peer only sees a
 * code, while the device log must still name the command and the reason.
 */
interface ControlLog {
    fun warn(message: String)

    fun error(
        message: String,
        throwable: Throwable,
    )

    companion object {
        val None: ControlLog =
            object : ControlLog {
                override fun warn(message: String) = Unit

                override fun error(
                    message: String,
                    throwable: Throwable,
                ) = Unit
            }
    }
}
