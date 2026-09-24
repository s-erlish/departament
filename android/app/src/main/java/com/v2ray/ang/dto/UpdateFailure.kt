package com.v2ray.ang.dto

/**
 * Why the update check or the download stopped — as a value the screen can read, not as a message.
 *
 * The updater used to answer every dead end with one sentence, «Проверьте сеть и повторите», and
 * before that with the raw `e.message` of whatever threw. Both are the same defect: the user is
 * told to retry a thing that will fail again for a reason nobody named. G2 says an action that
 * fails says why and offers the retry in the same place, so the *why* has to survive the trip from
 * `UpdateCheckerManager` to `CheckUpdateActivity` — which means it cannot be an exception message.
 *
 * The exception carries no text on purpose. `CheckUpdateActivity.reasonFor` maps a [Reason] to a
 * string resource; nothing built by concatenation ever reaches a person.
 */
class UpdateFailure(val reason: Reason) : Exception(reason.name) {

    enum class Reason {
        /** `AppConfig.APP_API_URL` is blank: this build was made with no update channel at all. */
        NO_CHANNEL,

        /** The feed could not be reached — offline, blocked, or GitHub is down. */
        UNREACHABLE,

        /** The feed answered, and there is no release in it (a fresh repository, or drafts only). */
        NO_RELEASE,

        /** There is a newer release, but it carries no APK this device's ABI can install. */
        NO_ASSET,

        /** The download itself broke: the connection dropped, or the cache could not be written. */
        DOWNLOAD_FAILED,

        /**
         * The downloaded APK is not this application. Provenance failed at the last gate — this is
         * the state that stops a mis-pointed feed from ever reaching the installer.
         */
        FOREIGN_PACKAGE,

        /**
         * The downloaded APK is this application but not a newer build of it, so Android would
         * refuse to install it. Almost always a release whose `versionName` moved and whose
         * `versionCode` did not.
         */
        NOT_NEWER,

        /** The device would not open the package installer at all. */
        INSTALLER_UNAVAILABLE;

        /**
         * Whether this is an ANSWER rather than a BREAKAGE — which decides how loudly the log
         * records it.
         *
         * «Релизов ещё нет» is the correct reply from a repository that has published none, and
         * this project has published none: «пока что без ключей, не релизное приложение пока». It
         * used to reach «Журнал» as an ERROR with a stack trace under it, which is what an app
         * falling over looks like; the owner sent a screenshot of it believing something had
         * broken. The screen still says the same sentence either way — it is only the log's
         * severity that changes, because the log is where the false alarm lived.
         *
         * A build with no channel, a version this device has no APK for and a release that is not
         * newer are the same kind of thing: the check ran, and the answer is "nothing for you".
         */
        val expected: Boolean
            get() = this == NO_CHANNEL || this == NO_RELEASE || this == NO_ASSET || this == NOT_NEWER
    }
}
