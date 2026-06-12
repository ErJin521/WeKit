@file:Suppress("NOTHING_TO_INLINE", "DEPRECATION", "unused")

package dev.ujhhgtg.wekit.hooks.api.core.models

enum class MessageType(val code: Int) {
    MOMENTS(0),

    @Deprecated("Use MessageType.isText()")
    TEXT(1),
    IMAGE(3),
    VOICE(34),
    FRIEND_VERIFY(37),
    CONTACT_RECOMMEND(40),
    CARD(42),
    VIDEO(43),

    @Deprecated("Use MessageType.isSticker()")
    STICKER(47),

    @Deprecated("Use MessageType.isLocation()")
    LOCATION(48),
    APP(49),

    @Deprecated("Use MessageType.isVoip()")
    VOIP(50),
    STATUS(51),

    @Deprecated("Use MessageType.isVoip()")
    VOIP_NOTIFY(52),

    @Deprecated("Use MessageType.isVoip()")
    VOIP_INVITE(53),
    MICRO_VIDEO(62),
    SYSTEM_NOTICE(9999),

    SYSTEM(10000),

    @Deprecated("Use MessageType.isLocation()")
    SYSTEM_LOCATION(10002),

    @Deprecated("Use MessageType.isSticker()")
    SO_GOU_EMOJI(1048625),

    @Deprecated("Use MessageType.isLink()")
    LINK(16777265),
    RECALL(268445456),
    SERVICE(318767153),
    TRANSFER(419430449),

    @Deprecated("Use MessageType.isRedPacket()")
    RED_PACKET(436207665),

    @Deprecated("Use MessageType.isRedPacket()")
    SPECIAL_RED_PACKET(469762097),
    ACCOUNT_VIDEO(486539313),
    RED_PACKET_COVER(536936497),

    @Deprecated("Use MessageType.isVideoAccount()")
    VIDEO_ACCOUNT(754974769),

    @Deprecated("Use MessageType.isVideoAccount()")
    VIDEO_ACCOUNT_CARD(771751985),
    GROUP_NOTE(805306417),
    QUOTE(822083633),
    PAT(922746929),

    @Deprecated("Use MessageType.isVideoAccount()")
    VIDEO_ACCOUNT_LIVE(973078577),

    @Deprecated("Use MessageType.isLink()")
    PRODUCT(974127153),
    UNKNOWN(975175729),

    @Deprecated("Use MessageType.isLink()")
    MUSIC(1040187441),
    FILE(1090519089),
    ;

    inline val isText get() = code == TEXT.code || code == QUOTE.code
    inline val isLink get() = code == LINK.code || code == MUSIC.code || code == PRODUCT.code
    inline val isRedPacket
        get() =
            code == RED_PACKET.code || code == SPECIAL_RED_PACKET.code
    inline val isSystem get() = code == SYSTEM.code || code == SYSTEM_NOTICE.code
    inline val isSticker get() = code == STICKER.code || code == SO_GOU_EMOJI.code
    inline val isLocation get() = code == LOCATION.code || code == SYSTEM_LOCATION.code
    inline val isVideoAccount
        get() =
            code == VIDEO_ACCOUNT.code || code == VIDEO_ACCOUNT_CARD.code || code == VIDEO_ACCOUNT_LIVE.code
    inline val isVoip
        get() =
            code == VOIP.code || code == VOIP_NOTIFY.code || code == VOIP_INVITE.code

    companion object {

        fun fromCode(code: Int): MessageType? = entries.find { it.code == code }
    }
}
