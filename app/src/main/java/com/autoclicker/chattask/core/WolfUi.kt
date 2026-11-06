package com.autoclicker.chattask.core

/**
 * What is known about the WOLF interface. Every value here was read from WOLF 71.0 on a real
 * device, so update them from a fresh UI dump rather than by guessing.
 */
object WolfUi {
    const val PACKAGE = "com.palringo.android"

    const val INPUT = "chat_bar_input"
    const val SEND = "chat_bar_send"
    const val TITLE = "tag_title"
    const val BACK_ARROW = "tag_back_arrow"
    const val BOTTOM_NAV = "activity_main_bottom_navigation"
    const val NAV_ITEM = "bottom_navigation_small_container"
    const val CHANNELS_SCREEN = "groups_list_screen"
    const val CHANNEL_LIST = "chat_switching_list"
    const val CHANNEL_ROW = "palnav_contact_item"
    const val CHANNEL_ROW_NAME = "palnav_contact_item_name"

    /** Only the selected tab shows a title, so the Channels tab is found by its position. */
    const val CHANNELS_TAB_INDEX = 3

    /** Description of the toolbar button that channels have. */
    const val MEMBERS_BUTTON_LABEL = "اعضاء القناة"

    const val DIALOG_TITLE_ID = "com.palringo.android:id/pal8_dialog_title"
    private const val LOGOUT_TITLE = "تسجيل الخروج"

    fun isLogoutTitle(title: CharSequence?): Boolean =
        title != null && TextNormalizer.normalize(title) == TextNormalizer.normalize(LOGOUT_TITLE)

    /** "com.palringo.android:id/tag" and the bare test tag "tag" both become "tag". */
    fun entryName(viewId: CharSequence?): String? =
        viewId?.toString()?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }

    fun isSendButton(viewId: CharSequence?): Boolean = entryName(viewId) == SEND

    /** The room name in the header has to equal the saved identifier, not merely contain it. */
    fun isRoom(title: String?, room: String): Boolean {
        val wanted = TextNormalizer.normalize(room)
        return wanted.isNotEmpty() && TextNormalizer.normalize(title.orEmpty()) == wanted
    }
}

/** The chat header as read from the screen. */
data class WolfScreen(
    val hasInput: Boolean = false,
    val title: String? = null,
    val hasMembersButton: Boolean = false,
    val isChannelList: Boolean = false,
    val hasBottomNav: Boolean = false,
) {
    /** Anything less than all three signals counts as unconfirmed, so nothing is sent. */
    val isChannel: Boolean get() = hasInput && !title.isNullOrBlank() && hasMembersButton
}
