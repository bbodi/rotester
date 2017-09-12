package hu.nevermind.rotester

import hu.nevermind.rotester.FromServer.BroadcastMessage.Companion.reader
import hu.nevermind.rotester.FromServer.ChangeMap.Companion.reader
import hu.nevermind.rotester.FromServer.CharListPages.Companion.reader
import hu.nevermind.rotester.FromServer.CharSelectErrorResponse.Companion.reader
import hu.nevermind.rotester.FromServer.CharWindow.Companion.reader
import hu.nevermind.rotester.FromServer.CharacterList.Companion.reader
import hu.nevermind.rotester.FromServer.ConnectToMapServerResponse.Companion.reader
import hu.nevermind.rotester.FromServer.Emotion.Companion.reader
import hu.nevermind.rotester.FromServer.EquipCheckbox.Companion.reader
import hu.nevermind.rotester.FromServer.Hotkeys.Companion.reader
import hu.nevermind.rotester.FromServer.HpBarUpdate.Companion.reader
import hu.nevermind.rotester.FromServer.InitialStatus.Companion.reader
import hu.nevermind.rotester.FromServer.InitializeIdleOrSpawningUnit.Companion.forIdleUnit
import hu.nevermind.rotester.FromServer.InitializeIdleOrSpawningUnit.Companion.forSpawnUnit
import hu.nevermind.rotester.FromServer.InventoryList.Companion.reader
import hu.nevermind.rotester.FromServer.ItemDisappear.Companion.reader
import hu.nevermind.rotester.FromServer.LoginFailResponsePacket.Companion.reader
import hu.nevermind.rotester.FromServer.LoginSucceedResponsePacket.Companion.reader
import hu.nevermind.rotester.FromServer.MakeUnitDisappear.Companion.reader
import hu.nevermind.rotester.FromServer.MapAuthOk.Companion.reader
import hu.nevermind.rotester.FromServer.MapServerData.Companion.reader
import hu.nevermind.rotester.FromServer.NotifyChat.Companion.reader
import hu.nevermind.rotester.FromServer.NotifyPlayerChat.Companion.reader
import hu.nevermind.rotester.FromServer.NotifyPlayerWhisperChat.Companion.reader
import hu.nevermind.rotester.FromServer.NotifySelfChat.Companion.reader
import hu.nevermind.rotester.FromServer.NotifyTime.Companion.reader
import hu.nevermind.rotester.FromServer.NpcSpriteChange.Companion.reader
import hu.nevermind.rotester.FromServer.ObjectMove.Companion.reader
import hu.nevermind.rotester.FromServer.PartyInvitationState.Companion.reader
import hu.nevermind.rotester.FromServer.PincodeState.Companion.reader
import hu.nevermind.rotester.FromServer.ResponseToDisconnectionRequest.Companion.reader
import hu.nevermind.rotester.FromServer.ScriptClose.Companion.reader
import hu.nevermind.rotester.FromServer.SetMapProperty.Companion.reader
import hu.nevermind.rotester.FromServer.SpriteChange2.Companion.reader
import hu.nevermind.rotester.FromServer.StopMove.Companion.reader
import hu.nevermind.rotester.FromServer.UnitWalking.Companion.reader
import hu.nevermind.rotester.FromServer.UpdateName.Companion.reader
import hu.nevermind.rotester.FromServer.UpdateSkillTree.Companion.reader
import hu.nevermind.rotester.FromServer.UpdateStatus_121.Companion.reader
import hu.nevermind.rotester.FromServer.UpdateStatus_13a.Companion.reader
import hu.nevermind.rotester.FromServer.UpdateStatus_141.Companion.reader
import hu.nevermind.rotester.FromServer.UpdateStatus_b0.Companion.reader
import hu.nevermind.rotester.FromServer.UpdateStatus_b1.Companion.reader
import hu.nevermind.rotester.FromServer.UpdateStatus_be.Companion.reader
import hu.nevermind.rotester.FromServer.WalkOk.Companion.reader
import hu.nevermind.rotester.FromServer.WhisperResultPacket.Companion.reader
import java.nio.ByteBuffer
import kotlin.experimental.and


object FromServer {
    fun read(bb: ByteBuffer, reader: FromServer.PacketFieldReader.() -> Packet?): Packet? {
        val pb = FromServer.PacketFieldReader(bb)
        return pb.reader()
    }

    interface Packet {
    }


    data class LoginFailResponsePacket(val reason: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> LoginFailResponsePacket? = {
                FromServer.LoginFailResponsePacket(byte1())
            }
        }
    }

    data class LoginSucceedResponsePacket(val loginId: Int, val accountId: Int, val userLevel: Int, val notUsed: Int, val unk: String, val unk2: Int, val sex: Int, val charServerDatas: List<CharServerData>) : Packet {
        data class CharServerData(val ip: Int, val port: Int, val name: String, val userCount: Int, val type: Int, val new: Int) {}

        companion object {
            val reader: FromServer.PacketFieldReader.() -> LoginSucceedResponsePacket? = {
                val packetSize = byte2()
                if (this.bb.remaining() < packetSize) {
                    null
                } else {
                    fun reeadCharServerData(): LoginSucceedResponsePacket.CharServerData {
                        return LoginSucceedResponsePacket.CharServerData(
                                ip = byte4(),
                                port = byte2(),
                                name = string(20),
                                userCount = byte2(),
                                type = byte2(),
                                new = byte2()
                        )

                    }
                    LoginSucceedResponsePacket(
                            loginId = byte4(),
                            accountId = byte4(),
                            userLevel = byte4(),
                            notUsed = byte4(),
                            unk = string(24),
                            unk2 = byte2(),
                            sex = byte1(),
                            charServerDatas = (0..((packetSize - 47) / 32) - 1).map { reeadCharServerData() }

                    )
                }
            }
        }
    }


    data class CharWindow(val minChars: Int, val vip: Int, val billing: Int, val totalNumberOfSlots: Int, val validSlots: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> CharWindow? = {
                FromServer.CharWindow(
                        minChars = skip(2, comment = "29").byte1(),
                        vip = byte1(),
                        billing = byte1(),
                        totalNumberOfSlots = byte1(),
                        validSlots = byte1()
                )
            }
        }
    }


    data class CharacterList(val maxSlots: Int, val availableSlots: Int, val premiumSlots: Int, val charInfos: List<CharacterInfo>) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> CharacterList? = {
                val packetLen = byte2()
                val readCharInfo = {
                    val charInfos = arrayListOf<CharacterList.CharacterInfo>()
                    while (readBytes + 2 < packetLen) {
                        charInfos.add(
                                CharacterList.CharacterInfo(
                                        char_id = byte4(),
                                        base_exp = byte4(),
                                        zeny = byte4(),
                                        job_exp = byte4(),
                                        job_level = byte4(),
                                        opt1 = byte4(),
                                        opt2 = byte4(),
                                        option = byte4(),
                                        karma = byte4(),
                                        manner = byte4(),
                                        status_point = byte2(),
                                        hp = byte4(),
                                        max_hp = byte4(),
                                        sp = byte2(),
                                        max_sp = byte2(),
                                        walkSpeed = byte2(),
                                        class_ = byte2(),
                                        hair = byte2(),
                                        body = byte2(),
                                        weapon = byte2(),
                                        base_level = byte2(),
                                        skill_point = byte2(),
                                        head_bottom = byte2(),
                                        shield = byte2(),
                                        head_top = byte2(),
                                        head_mid = byte2(),
                                        hair_color = byte2(),
                                        clothes_color = byte2(),
                                        name = string(23 + 1),
                                        str = byte1(),
                                        agi = byte1(),
                                        vit = byte1(),
                                        int = byte1(),
                                        dex = byte1(),
                                        luk = byte1(),
                                        slot = byte2(),
                                        rename = byte2(),
                                        last_point_map = string(16),
                                        delete_date = byte4(),
                                        robe = byte4(),
                                        char_move_enabled = byte4(),
                                        rename2 = byte4(),
                                        sex = byte1()
                                )
                        )
                    }
                    charInfos
                }
                val ret = FromServer.CharacterList(
                        maxSlots = byte1(),
                        availableSlots = byte1(),
                        premiumSlots = byte1(),
                        charInfos = skip(20, "unknown").then { readCharInfo() }
                )
                ret
            }
        }

        data class CharacterInfo(
                val char_id: Int,
                val base_exp: Int,
                val zeny: Int,
                val job_exp: Int,
                val job_level: Int,
                val opt1: Int, // probably opt1
                val opt2: Int, // probably opt2
                val option: Int,
                val karma: Int,
                val manner: Int,
                val status_point: Int,
                val hp: Int,
                val max_hp: Int,
                val sp: Int,
                val max_sp: Int,
                val walkSpeed: Int,
                val class_: Int,
                val hair: Int,

                val body: Int,

                //When the weapon is sent and your option is riding, the client crashes on login!?
                val weapon: Int,

                val base_level: Int,
                val skill_point: Int,
                val head_bottom: Int,
                val shield: Int,
                val head_top: Int,
                val head_mid: Int,
                val hair_color: Int,
                val clothes_color: Int,
                val name: String,
                val str: Int,
                val agi: Int,
                val vit: Int,
                val int: Int,
                val dex: Int,
                val luk: Int,
                val slot: Int,
                val rename: Int,
                // #if (PACKETVER >= 20100720 && PACKETVER <= 20100727) || PACKETVER >= 20100803
                val last_point_map: String,
                // #endif
                //#if PACKETVER >= 20100803
                //#if PACKETVER_CHAR_DELETEDATE
                val delete_date: Int,
                //#endif
                // #endif
                //#if PACKETVER >= 20110111
                val robe: Int,
                // #endif
                //#if PACKETVER != 20111116 //2011-11-16 wants 136, ask gravity.
                //#if PACKETVER >= 20110928
                // change slot feature (0 = disabled, otherwise enabled)
                val char_move_enabled: Int,
                val rename2: Int,
                // #endif
                // #if PACKETVER >= 20111025
                // #endif
                // #if PACKETVER >= 20141016
                val sex: Int// sex - (0 = female, 1 = male, 99 = logindefined)
                //#endif
                //#endif

        )
    }


    data class CharListPages(val pages: Int, val charSlots: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> CharListPages? = {
                FromServer.CharListPages(
                        pages = byte4(),
                        charSlots = byte4()
                )
            }

        }
    }

    data class PincodeState(val seed: Int, val accountId: Int, val state: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> PincodeState? = {
                FromServer.PincodeState(
                        seed = byte4(),
                        accountId = byte4(),
                        state = byte2()
                )
            }
        }
    }

    data class MapServerData(val charId: Int, val mapName: String, val ip: Int, val port: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> MapServerData? = {
                FromServer.MapServerData(
                        charId = byte4(),
                        mapName = string(11 + 1 + 4),
                        ip = byte4(),
                        port = byte2()
                )
            }
        }
    }

    data class ConnectToMapServerResponse(val blockListId: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> ConnectToMapServerResponse? = {
                FromServer.ConnectToMapServerResponse(
                        blockListId = byte4()
                )
            }
        }
    }

    /*  * result :
 *  1 : Server closed
 *  2 : Someone has already logged in with this id
 *  8 : already online
 */
    data class CharSelectErrorResponse(val reason: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> CharSelectErrorResponse? = {
                FromServer.CharSelectErrorResponse(
                        reason = byte1()
                )
            }
        }
    }

    data class MapAuthOk(val tick: Int, val pos: Pos, val font: Int, val sex: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> MapAuthOk? = {
                FromServer.MapAuthOk(
                        tick = byte4(),
                        pos = mapPosition(),
                        font = skip(2).then { byte2() },
                        sex = byte1()
                )
            }
        }
    }

    data class NotifyPlayerChat(val msg: String) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> NotifyPlayerChat? = {
                FromServer.NotifyPlayerChat(
                        msg = string(byte2() - 4) // cmd + size.W
                )
            }
        }
    }

    data class NotifyPlayerWhisperChat(val from: String, val isAdmin: Boolean, val msg: String) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> NotifyPlayerWhisperChat? = {
                val payloadLength = byte2()
                val msgLen = payloadLength - 24 - 8
                FromServer.NotifyPlayerWhisperChat(
                        from = string(24),
                        isAdmin = byte4() != 0,
                        msg = string(msgLen)
                )
            }
        }
    }

    enum class WhisperResult {
        Success,
        TargetCharIsNotLoggedIn,
        IgnoredByPlayer,
        EveryIsIgnoredByPlayer
    }

    data class WhisperResultPacket(val result: WhisperResult, val charId: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> WhisperResultPacket? = {
                FromServer.WhisperResultPacket(
                        result = WhisperResult.values()[byte1()],
                        charId = byte4()
                )
            }
        }
    }

    data class ChangeMap(val mapName: String, val x: Int, val y: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> ChangeMap? = {
                FromServer.ChangeMap(
                        mapName = string(11 + 1 + 4),
                        x = byte2(),
                        y = byte2()
                )
            }
        }
    }

    data class UpdateStatus_b0(val type: Status) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> UpdateStatus_b0? = {
                val type = Status.valueOf(byte2())
                when (type) {
                // 00b0
                    Status.SP_WEIGHT -> {
                    }
                    Status.SP_MAXWEIGHT -> {
                    }
                    Status.SP_SPEED -> {
                    }
                    Status.SP_BASELEVEL -> {
                    }
                    Status.SP_JOBLEVEL -> {
                    }
                    Status.SP_KARMA -> { // Adding this back, I wonder if the client intercepts this - [Lance]
                    }
                    Status.SP_MANNER -> {
                    }
                    Status.SP_STATUSPOINT -> {
                    }
                    Status.SP_SKILLPOINT -> {
                    }
                    Status.SP_HIT -> {
                    }
                    Status.SP_FLEE1 -> {
                    }
                    Status.SP_FLEE2 -> {
                    }
                    Status.SP_MAXHP -> {
                    }
                    Status.SP_MAXSP -> {
                    }
                    Status.SP_HP -> {

                    }
                    Status.SP_SP -> {
                    }
                    Status.SP_ASPD -> {
                    }
                    Status.SP_ATK1 -> {
                    }
                    Status.SP_DEF1 -> {
                    }
                    Status.SP_MDEF1 -> {
                    }
                    Status.SP_ATK2 -> {
                    }
                    Status.SP_DEF2 -> {
                    }
                    Status.SP_MDEF2 -> {

                    }
                    Status.SP_CRITICAL -> {
                    }
                    Status.SP_MATK1 -> {
                    }
                    Status.SP_MATK2 -> {
                    }
                    else -> {
                        error("clif_updatestatus : unrecognized type $type")
                    }
                }
                FromServer.UpdateStatus_b0(
                        type = type
                )
            }
        }
    }

    data class UpdateStatus_b1(val type: Status, val value: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> UpdateStatus_b1? = {
                val type = Status.valueOf(byte2())
                val value = when (type) {
                    Status.SP_ZENY -> {
                        byte4()
                    }
                    Status.SP_BASEEXP -> {
                        byte4()
                    }
                    Status.SP_JOBEXP -> {
                        byte4()
                    }
                    Status.SP_NEXTBASEEXP -> {
                        byte4()
                    }
                    Status.SP_NEXTJOBEXP -> {
                        byte4()
                    }
                    else -> {
                        error("clif_updatestatus : unrecognized type $type")
                    }
                }
                FromServer.UpdateStatus_b1(
                        type = type,
                        value = value
                )
            }
        }
    }

    data class UpdateStatus_be(val type: Status) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> UpdateStatus_be? = {
                val type = Status.valueOf(byte2())
                when (type) {
                    Status.SP_USTR, Status.SP_UAGI, Status.SP_UVIT, Status.SP_UINT, Status.SP_UDEX, Status.SP_ULUK -> {
                    }
                    else -> {
                        error("clif_updatestatus : unrecognized type $type")
                    }
                }
                FromServer.UpdateStatus_be(
                        type = type
                )
            }
        }
    }

    data class UpdateStatus_141(val type: Status, val val1: Int, val val2: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> UpdateStatus_141? = {
                val type = Status.valueOf(byte4())
                val (val1, val2) = when (type) {
                    Status.SP_STR -> {
                        byte4() to byte4()
                    }
                    Status.SP_AGI -> {
                        byte4() to byte4()
                    }
                    Status.SP_VIT -> {
                        byte4() to byte4()
                    }
                    Status.SP_INT -> {
                        byte4() to byte4()
                    }
                    Status.SP_DEX -> {
                        byte4() to byte4()
                    }
                    Status.SP_LUK -> {
                        byte4() to byte4()
                    }
                    else -> {
                        error("clif_updatestatus : unrecognized type $type")
                    }
                }
                FromServer.UpdateStatus_141(
                        type = type,
                        val1 = val1,
                        val2 = val2
                )
            }
        }
    }

    data class UpdateStatus_13a(val attackRange: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> UpdateStatus_13a? = {
                FromServer.UpdateStatus_13a(
                        attackRange = byte2()
                )
            }
        }
    }

    data class UpdateStatus_121(val cartNum: Int, val maxCart: Int, val cartWeight: Int, val maxCartWeight: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> UpdateStatus_121? = {
                FromServer.UpdateStatus_121(
                        cartNum = byte2(),
                        maxCart = byte2(),
                        cartWeight = byte4(),
                        maxCartWeight = byte4()
                )
            }
        }
    }

    data class SpriteChange2(val id: Int, val type: Int, val val1: Int, val val2: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> SpriteChange2? = {
                FromServer.SpriteChange2(
                        id = byte4(),
                        type = byte1(),
                        val1 = byte2(),
                        val2 = byte2()
                )
            }
        }
    }

    data class NpcSpriteChange(val gid: Int, val type: Int, val value: Long) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> NpcSpriteChange? = {
                FromServer.NpcSpriteChange(
                        gid = byte4(),
                        type = ubyte1(),
                        value = ubyte4()
                )
            }
        }
    }

    data class UpdateName(val gid: Int, val name: String) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> UpdateName? = {
                FromServer.UpdateName(
                        gid = byte4(),
                        name = string(24)
                )
            }
        }
    }

    data class ItemDisappear(val itemId: Long) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> ItemDisappear? = {
                FromServer.ItemDisappear(
                        itemId = ubyte4()
                )
            }
        }
    }


    data class PartyInvitationState(val allowed: Boolean) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> PartyInvitationState? = {
                FromServer.PartyInvitationState(
                        allowed = byte1() == 0
                )
            }
        }
    }

    data class EquipCheckbox(val enabled: Boolean) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> EquipCheckbox? = {
                FromServer.EquipCheckbox(
                        enabled = byte1() == 1
                )
            }
        }
    }

    data class ScriptClose(val npcId: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> ScriptClose? = {
                FromServer.ScriptClose(
                        npcId = byte4()
                )
            }
        }
    }

    data class WalkOk(val tick: Int, val posChange: PosChange) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> WalkOk? = {
                FromServer.WalkOk(
                        tick = byte4(),
                        posChange = positionChange()
                )
            }
        }
    }

    enum class DisappearType {
        OutOfSight,
        Died,
        LoggedOut,
        Teleport,
        TrickDead
    }

    data class MakeUnitDisappear(val gid: Int, val type: DisappearType) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> MakeUnitDisappear? = {
                FromServer.MakeUnitDisappear(
                        gid = byte4(),
                        type = DisappearType.values()[byte1()]
                )
            }
        }
    }

    data class ObjectMove(val gid: Int, val pos: PosChange, val walkStartTime: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> ObjectMove? = {
                FromServer.ObjectMove(
                        gid = byte4(),
                        pos = positionChange(),
                        walkStartTime = byte4()
                )
            }
        }
    }

    data class StopMove(val gid: Int, val x: Int, val y: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> StopMove? = {
                FromServer.StopMove(
                        gid = byte4(),
                        x = byte2(),
                        y = byte2()
                )
            }
        }
    }


    data class NotifyChat(val gid: Int, val msg: String) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> NotifyChat? = {
                val len = byte2() - 8
                FromServer.NotifyChat(
                        gid = byte4(),
                        msg = string(len)
                )
            }
        }
    }

    /// 018b <result>.W
    /// result:
    ///     0 = disconnect (quit)
    ///     1 = cannot disconnect (wait 10 seconds)
    ///     ? = ignored
    data class ResponseToDisconnectionRequest(val type: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> ResponseToDisconnectionRequest? = {
                FromServer.ResponseToDisconnectionRequest(
                        type = byte2()
                )
            }
        }
    }

    data class NotifySelfChat(val msg: String) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> NotifySelfChat? = {
                val len = byte2() - 4
                FromServer.NotifySelfChat(
                        msg = string(len)
                )
            }
        }
    }

    data class BroadcastMessage(val msg: String) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> BroadcastMessage? = {
                val len = byte2() - 4
                FromServer.BroadcastMessage(
                        msg = string(len)
                )
            }
        }
    }


    data class HpBarUpdate(val gid: Int, val hp: Int, val maxHp: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> HpBarUpdate? = {
                FromServer.HpBarUpdate(
                        gid = byte4(),
                        hp = byte4(),
                        maxHp = byte4()
                )
            }
        }
    }

    data class Emotion(val gid: Int, val type: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> Emotion? = {
                FromServer.Emotion(
                        gid = byte4(),
                        type = byte1()
                )
            }
        }
    }

    data class SetMapProperty(val property: Int, val flags: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> SetMapProperty? = {
                FromServer.SetMapProperty(
                        property = byte2(),
                        flags = byte4()
                )
            }
        }
    }

    data class InitializeIdleOrSpawningUnit(
            val blType: Int,
            val payloadLength: Int,
            val blId: Int,
            val charId: Int,
            val speed: Int,
            val statusChangeOpt1: Int,
            val statusChangeOpt2: Int,
            val statusChangeOption: Int,
            val viewDataClass: Int,
            val viewDataHairStyle: Int,
            val viewDataWeapon: Int,
            val viewDataShield: Int,
            val viewDataHeadBottom: Int,
            val viewDataHeadTop: Int,
            val viewDataHeadMid: Int,
            val flagEmblemId: Int,
            val guildIdUpper2Bytes: Int,
            val guildIdLower2Bytes: Int,
            val viewDataHairColor: Int,
            val viewDataClothColor: Int,
            val viewDataHeadDir: Int,
            val viewDataRobe: Int,
            val guildId: Int,
            val emblemId: Int,
            val manner: Int,
            val statusChangeOpt3: Int,
            val karma: Int,
            val viewDataSex: Int,
            val pos: Pos,
            val deadSit: Int,

            val level: Int,
            val font: Int,
            val maxHp: Int,
            val hp: Int,
            val isBoss: Boolean,
            val viewDataBodyStyle: Int,
            val name: String
    ) : Packet {
        companion object {
            val forSpawnUnit = reader(true)
            val forIdleUnit = reader(false)
            private fun reader(spawned: Boolean): FromServer.PacketFieldReader.() -> InitializeIdleOrSpawningUnit? = {
                val payloadLength = byte2()
                val blType = byte1()
                // WBUFW(buf,2) = (uint16)((spawn ? 79 : 80)+strlen(name));
                val blId = byte4()
                val charId = byte4()
                val speed = byte2()
                val statusChangeOpt1 = byte2()
                val statusChangeOpt2 = byte2()
                val statusChangeOption = byte4()
                val viewDataClass = byte2()
                val viewDataHairStyle = byte2()
                val viewDataWeapon = byte2()
                val viewDataShield = byte2()
                val viewDataHeadBottom = byte2()
                val viewDataHeadTop = byte2()
                val viewDataHeadMid = byte2()
                val (flagEmblemId, guildIdUpper2Bytes, guildIdLower2Bytes) = if (blType == 0x080 /*BL_NPC*/ && viewDataClass == 722 /*FLAG_CLASS*/) {
                    Triple(byte2(), byte2(), byte2())
                } else Triple(0, 0, 0)
                val viewDataHairColor = byte2()
                val viewDataClothColor = byte2()
                val viewDataHeadDir = byte2()
                val viewDataRobe = byte2()
                val guildId = byte4()
                val emblemId = byte2()
                val manner = byte2()
                val statusChangeOpt3 = byte4()
                val karma = byte1()
                val viewDataSex = byte1()
                val pos = mapPosition()
                val unk1 = byte1() // xsize?
                val unk2 = byte1() // ysize?
                // WBUFB(buf,49) = (sd)? 5 : 0;
                // WBUFB(buf,50) = (sd)? 5 : 0;
                val deadSit = if (!spawned) {
                    byte1()
                } else 0
                val level = byte2()
                val font = byte2()
                val maxHp = byte4()
                val hp = byte4()
                val isBoss = byte1() == 1
                val viewDataBodyStyle = byte2()
                val name = string(payloadLength - readBytes - 2)
                val ret = InitializeIdleOrSpawningUnit(
                        blType, payloadLength, blId, charId, speed,
                        statusChangeOpt1, statusChangeOpt2, statusChangeOption,
                        viewDataClass, viewDataHairStyle, viewDataWeapon, viewDataShield, viewDataHeadBottom, viewDataHeadTop,
                        viewDataHeadMid,
                        flagEmblemId, guildIdUpper2Bytes, guildIdLower2Bytes,
                        viewDataHairColor, viewDataClothColor, viewDataHeadDir, viewDataRobe, guildId, emblemId,
                        manner, statusChangeOpt3, karma, viewDataSex, pos, deadSit, level, font, maxHp,
                        hp, isBoss, viewDataBodyStyle, name
                )
                setReadingByteCountsAfterHeaderIsAlreadyRead(payloadLength - 2)
                ret
            }
        }
    }

    data class UnitWalking(
            val blType: Int,
            val payloadLength: Int,
            val blId: Int,
            val charId: Int,
            val speed: Int,
            val statusChangeOpt1: Int,
            val statusChangeOpt2: Int,
            val statusChangeOption: Int,
            val viewDataClass: Int,
            val viewDataHairStyle: Int,
            val viewDataWeapon: Int,
            val viewDataShield: Int,
            val viewDataHeadBottom: Int,
            val viewDataHeadTop: Int,
            val viewDataHeadMid: Int,
            val tick: Int,
            val viewDataHairColor: Int,
            val viewDataClothColor: Int,
            val viewDataHeadDir: Int,
            val viewDataRobe: Int,
            val guildId: Int,
            val emblemId: Int,
            val manner: Int,
            val statusChangeOpt3: Int,
            val karma: Int,
            val viewDataSex: Int,
            val pos: PosChange,

            val level: Int,
            val font: Int,
            val maxHp: Int,
            val hp: Int,
            val isBoss: Boolean,
            val viewDataBodyStyle: Int,
            val name: String
    ) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> UnitWalking? = {
                // WBUFW(buf, 2) = (uint16)(86+strlen(name));
                val payloadLength = byte2()
                val blType = byte1()
                val blId = byte4()
                val charId = byte4()
                val speed = byte2()
                val statusChangeOpt1 = byte2()
                val statusChangeOpt2 = byte2()
                val statusChangeOption = byte4()
                val viewDataClass = byte2()
                val viewDataHairStyle = byte2()
                val viewDataWeapon = byte2()
                val viewDataShield = byte2()
                val viewDataHeadBottom = byte2()
                val tick = byte4()
                val viewDataHeadTop = byte2()
                val viewDataHeadMid = byte2()
                val viewDataHairColor = byte2()
                val viewDataClothColor = byte2()
                val viewDataHeadDir = byte2()
                val viewDataRobe = byte2()
                val guildId = byte4()
                val emblemId = byte2()
                val manner = byte2()
                val statusChangeOpt3 = byte4()
                val karma = byte1()
                val viewDataSex = byte1()
                val pos = positionChange()
                //WBUFB(buf,56) = (sd)? 5 : 0;
                //WBUFB(buf,57) = (sd)? 5 : 0;
                val unk1 = byte1() // xsize?
                val unk2 = byte1() // ysize?
                val level = byte2()
                val font = byte2()
                val maxHp = byte4()
                val hp = byte4()
                val isBoss = byte1() == 1
                val viewDataBodyStyle = byte2()
                val name = string(payloadLength - readBytes - 2)
                val ret = UnitWalking(
                        blType, payloadLength, blId, charId, speed,
                        statusChangeOpt1, statusChangeOpt2, statusChangeOption,
                        viewDataClass, viewDataHairStyle, viewDataWeapon, viewDataShield, viewDataHeadBottom, tick, viewDataHeadTop,
                        viewDataHeadMid,
                        viewDataHairColor, viewDataClothColor, viewDataHeadDir, viewDataRobe, guildId, emblemId,
                        manner, statusChangeOpt3, karma, viewDataSex, pos, level, font, maxHp,
                        hp, isBoss, viewDataBodyStyle, name
                )
                setReadingByteCountsAfterHeaderIsAlreadyRead(payloadLength - 2)
                ret
            }
        }
    }


    data class NotifyTime(val serverTime: Long) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> NotifyTime? = {
                FromServer.NotifyTime(
                        serverTime = ubyte4()
                )
            }
        }
    }

    enum class ItemType {
        HEALING,
        UNKNOWN, //1
        USABLE, //2, stackable
        ETC, //3, stackable
        ARMOR, //4
        WEAPON, //5
        CARD, //6, stackable
        PETEGG, //7, 0
        PETARMOR, //8, 0
        UNKNOWN2, //9, 0
        AMMO, //10, stackable
        DELAYCONSUME, //11, stackable
        SHADOWGEAR, //12
        CASH //18
    }

    data class InventoryList(val size: Int) : Packet {
        companion object {
            const val sizeOfOneItem = 57
            val reader: FromServer.PacketFieldReader.() -> InventoryList? = {
                val size = byte2()
                skip(size - 4)
                FromServer.InventoryList(
                        size = size
                )
                /*val index = byte2()
                val viewIdOrNameId = byte2()
                val type = ItemType.values()[byte1()]
                val stackable = type in listOf(ItemType.USABLE, ItemType.AMMO, ItemType.CARD, ItemType.DELAYCONSUME, ItemType.ETC,
                        ItemType.HEALING)
                if (stackable) {
                    val amount = byte2()
                    val wearState = ubyte4()
                    val expireTime = ubyte4()
                    val cards = bytes(8) // 4 byte2
                    val identify = byte1()
                    //WBUFB(buf,n+23) = it->identify; //0x1 IsIdentified
                    //WBUFB(buf,n+23) |= (it->favorite) ? 0x2 : 0; //0x4,0x2 PlaceETCTab
                } else {
                    skip(31)
                }
                */
            }
        }
    }


    data class UpdateSkillTree(val skillInfos: Array<SkillInfo>) : Packet {
        companion object {
            data class SkillInfo(val id: Int, val type: Int, val level: Int, val spCost: Int, val attackRange: Int,
                                 val name: String, val upgradable: Boolean) {

            }

            val reader: FromServer.PacketFieldReader.() -> UpdateSkillTree? = {
                val len = byte2()
                val packetCount = (len - 2 - 2) / 37 // cmd.W, len.W
                FromServer.UpdateSkillTree(
                        (0 until packetCount).map {
                            SkillInfo(
                                    id = byte2(),
                                    type = byte4(),
                                    level = byte2(),
                                    spCost = byte2(),
                                    attackRange = byte2(),
                                    name = string(24),
                                    upgradable = byte1() == 1
                            )
                        }.toTypedArray()
                )
            }
        }
    }

    data class Hotkeys(val rotate: Int, val hotkeys: Array<Hotkey>) : Packet {
        companion object {
            data class Hotkey(val id: Int, val type: Int, val level: Int)

            val reader: FromServer.PacketFieldReader.() -> Hotkeys? = {
                FromServer.Hotkeys(
                        rotate = byte1(),
                        hotkeys = (0 until 38).map {
                            Hotkey(
                                    type = byte1(),
                                    id = byte4(),
                                    level = byte2()
                            )
                        }.toTypedArray()
                )
            }
        }
    }

    data class InitialStatus(val statusPoint: Int,
                             val str: Int,
                             val needStr: Int,
                             val agi: Int,
                             val dex: Int,
                             val int: Int,
                             val luk: Int,
                             val vit: Int,
                             val needAgi: Int,
                             val needDex: Int,
                             val needVit: Int,
                             val needInt: Int,
                             val needLuk: Int,
                             val leftSideAtk: Int,
                             val rightSideAtk: Int,
                             val leftSideMagicAtk: Int,
                             val rightSideMagicAtk: Int,
                             val leftSideDef: Int,
                             val rightSideDef: Int,
                             val leftSideMagicDef: Int,
                             val rightSideMagicDef: Int,
                             val hit: Int,
                             val flee: Int,
                             val fleePercent: Int,
                             val criPercent: Int,
                             val extraAttackSpeed: Int,
                             val attackSpeed: Int) : Packet {
        companion object {
            val reader: FromServer.PacketFieldReader.() -> InitialStatus? = {
                FromServer.InitialStatus(
                        statusPoint = byte2(),
                        str = byte1(),
                        needStr = byte1(),
                        agi = byte1(),
                        needAgi = byte1(),
                        vit = byte1(),
                        needVit = byte1(),
                        int = byte1(),
                        needInt = byte1(),
                        dex = byte1(),
                        needDex = byte1(),
                        luk = byte1(),
                        needLuk = byte1(),

                        leftSideAtk = byte2(),
                        rightSideAtk = byte2(),
                        rightSideMagicAtk = byte2(),
                        leftSideMagicAtk = byte2(),
                        leftSideDef = byte2(),
                        rightSideDef = byte2(),
                        leftSideMagicDef = byte2(),
                        rightSideMagicDef = byte2(),

                        hit = byte2(),
                        flee = byte2(),
                        fleePercent = byte2(),
                        criPercent = byte2(),
                        attackSpeed = byte2(),
                        extraAttackSpeed = byte2()
                )
            }
        }
    }

    val PACKETS = listOf(
            Triple(0x83e, 26, LoginFailResponsePacket.reader),
            Triple(0x69, 0, LoginSucceedResponsePacket.reader), // 47+32*server_num
            Triple(0x82d, 29, CharWindow.reader),
            Triple(0x6b, 0, CharacterList.reader),
            Triple(0x9a0, 10, CharListPages.reader),
            Triple(0x8b9, 12, PincodeState.reader),
            Triple(0x71, 28, MapServerData.reader),
            Triple(0x81, 3, CharSelectErrorResponse.reader),
            Triple(0x283, 2 + 4, ConnectToMapServerResponse.reader),
            Triple(0xa18, 14, MapAuthOk.reader),
            Triple(0x8e, 0, NotifyPlayerChat.reader),
            Triple(0x97, 0, NotifyPlayerWhisperChat.reader),
            Triple(0x9df, 0, WhisperResultPacket.reader),
            Triple(0x91, 0, ChangeMap.reader),
            Triple(0xb0, 8, UpdateStatus_b0.reader),
            Triple(0xb1, 8, UpdateStatus_b1.reader),
            Triple(0xbe, 5, UpdateStatus_be.reader),
            Triple(0x13a, 4, UpdateStatus_13a.reader),
            Triple(0x141, 14, UpdateStatus_141.reader),
            Triple(0x121, 14, UpdateStatus_121.reader),
            Triple(0x1d7, 11, SpriteChange2.reader),
            Triple(0x1b0, 11, NpcSpriteChange.reader),
            Triple(0x10f, 0, UpdateSkillTree.reader),
            Triple(0xbd, 44, InitialStatus.reader),
            Triple(0x2c9, 3, PartyInvitationState.reader),
            Triple(0x95, 30, UpdateName.reader),
            Triple(0xa1, 6, ItemDisappear.reader),
            Triple(0x2da, 3, EquipCheckbox.reader),
            Triple(0xb6, 6, ScriptClose.reader),
            Triple(0x87, 12, WalkOk.reader),
            Triple(0xa00, 269, Hotkeys.reader),
            Triple(0x80, 7, MakeUnitDisappear.reader),
            Triple(0x86, 16, ObjectMove.reader),
            Triple(0x88, 10, StopMove.reader),
            Triple(0x8d, 0, NotifyChat.reader),
            Triple(0x8e, 0, NotifySelfChat.reader),
            Triple(0x18b, 4, ResponseToDisconnectionRequest.reader),
            Triple(0x80e, 14, HpBarUpdate.reader),
            Triple(0x9a, 0, BroadcastMessage.reader),
            Triple(0xc0, 7, Emotion.reader),
            Triple(0x99b, 8, SetMapProperty.reader),
            Triple(0x9ff, 0, InitializeIdleOrSpawningUnit.forIdleUnit),
            Triple(0x9fe, 0, InitializeIdleOrSpawningUnit.forSpawnUnit),
            Triple(0x9fd, 0, UnitWalking.reader),
            Triple(0xa0d, 0, InventoryList.reader),
            Triple(0x991, 0, InventoryList.reader),
            Triple(0x7f, 6, NotifyTime.reader)
    )

    class PacketFieldReader(val bb: ByteBuffer) {
        val startPos = bb.position()
        val readBytes: Int get() = bb.position() - startPos

        // helper method for skip, like skip(20).then {readSomething()}
        fun <T> then(callbackAfter: () -> T): T {
            return callbackAfter()
        }

        fun skip(count: Int, comment: String = ""): PacketFieldReader {
            bb.position(bb.position() + count)
            return this
        }

        fun byte1(): Int {
            return bb.get().toInt()
        }

        fun setReadingByteCountsAfterHeaderIsAlreadyRead(bytes: Int) {
            bb.position(startPos + bytes)
        }

        fun byte2(): Int {
            return bb.getShort().toInt()
        }

        fun byte4(): Int {
            return bb.getInt()
        }

        fun ubyte1(): Int {
            return java.lang.Byte.toUnsignedInt(bb.get())
        }

        fun ubyte2(): Int {
            return java.lang.Short.toUnsignedInt(bb.getShort())
        }

        fun ubyte4(): Long {
            return Integer.toUnsignedLong(byte4())
        }

        fun mapPosition(): Pos {
            // 00xx_xxxx
            // xx00_yyyy
            // yyyy_dddd
            val b1 = ubyte1()
            val b2 = ubyte1()
            val b3 = ubyte1()
            return Pos(
                    x = b1.shl(2).and(0B1111_1100).or(b2.ushr(6).and(0B11)),
                    y = b2.shl(4).and(0B1111_0000).or(b3.ushr(4).and(0B1111)),
                    dir = b3.and(0b1111)
            )
        }

        fun positionChange(): PosChange {
            val b0 = ubyte1()
            val b1 = ubyte1()
            val b2 = ubyte1()
            val b3 = ubyte1()
            val b4 = ubyte1()
            val b5 = ubyte1()
            return PosChange(
                    b0.and(0xff).shl(2).or(b1.ushr(6)),
                    b1.and(0x3f).shl(4).or(b2.ushr(4)),
                    b2.and(0x0f).shl(6).or(b3.ushr(2)),
                    b3.and(0x03).shl(8).or(b4.ushr(0)),
                    b5.and(0xf0).ushr(4),
                    b5.and(0x0f).ushr(0)
            )
        }

        fun string(maxBytesIncludingNullTerminator: Int): String {
            val byteArray = kotlin.ByteArray(maxBytesIncludingNullTerminator)
            bb.get(byteArray)
            return String(byteArray.takeWhile { it != 0.toByte() }.toByteArray(), Charsets.US_ASCII)
        }

        fun bytes(count: Int): ByteArray {
            val byteArray = kotlin.ByteArray(count)
            bb.get(byteArray)
            return byteArray
        }
    }
}

data class Pos(val x: Int, val y: Int, val dir: Int)

// WBUFPOS2
data class PosChange(val srcX: Int, val srcY: Int, val dstX: Int, val dstY: Int, val sx: Int, val sy: Int)
