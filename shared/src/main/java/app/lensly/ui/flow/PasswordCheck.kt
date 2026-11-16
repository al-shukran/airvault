package app.lensly.ui.flow

/** The result of checking the App Password. */
enum class PasswordCheck {
    /** The password is right. The check opened a session only to prove it, and that session is already closed. */
    Accepted,

    /** The password is wrong, or a failure delay is running. The owner cannot tell which. */
    Rejected,

    /** The vault could not be checked, for example because its stored state is damaged. */
    Unavailable,
}
