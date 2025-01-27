package at.hannibal2.skyhanni.features.misc

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.config.ConfigManager
import at.hannibal2.skyhanni.data.IslandType
import at.hannibal2.skyhanni.data.ProfileStorageData
import at.hannibal2.skyhanni.data.SkillExperience
import at.hannibal2.skyhanni.events.*
import at.hannibal2.skyhanni.features.bazaar.BazaarApi
import at.hannibal2.skyhanni.features.misc.GhostCounter.Option.*
import at.hannibal2.skyhanni.utils.CombatUtils._isKilling
import at.hannibal2.skyhanni.utils.CombatUtils.calculateETA
import at.hannibal2.skyhanni.utils.CombatUtils.calculateXP
import at.hannibal2.skyhanni.utils.CombatUtils.interp
import at.hannibal2.skyhanni.utils.CombatUtils.isKilling
import at.hannibal2.skyhanni.utils.CombatUtils.killGainHour
import at.hannibal2.skyhanni.utils.CombatUtils.killGainHourLast
import at.hannibal2.skyhanni.utils.CombatUtils.lastKillUpdate
import at.hannibal2.skyhanni.utils.CombatUtils.lastUpdate
import at.hannibal2.skyhanni.utils.CombatUtils.xpGainHour
import at.hannibal2.skyhanni.utils.CombatUtils.xpGainHourLast
import at.hannibal2.skyhanni.utils.ItemUtils.getLore
import at.hannibal2.skyhanni.utils.LorenzUtils
import at.hannibal2.skyhanni.utils.LorenzUtils.addAsSingletonList
import at.hannibal2.skyhanni.utils.LorenzUtils.chat
import at.hannibal2.skyhanni.utils.LorenzUtils.clickableChat
import at.hannibal2.skyhanni.utils.NumberUtil
import at.hannibal2.skyhanni.utils.NumberUtil.addSeparators
import at.hannibal2.skyhanni.utils.NumberUtil.formatNumber
import at.hannibal2.skyhanni.utils.NumberUtil.roundToPrecision
import at.hannibal2.skyhanni.utils.RenderUtils.renderStringsAndItems
import at.hannibal2.skyhanni.utils.StringUtils.matchMatcher
import at.hannibal2.skyhanni.utils.StringUtils.removeColor
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.github.moulberry.notenoughupdates.util.Utils
import io.github.moulberry.notenoughupdates.util.XPInformation
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.FileReader
import java.nio.charset.StandardCharsets
import java.text.NumberFormat
import java.util.*
import java.util.regex.Pattern
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object GhostCounter {

    val config get() = SkyHanniMod.feature.ghostCounter
    val hidden get() = ProfileStorageData.profileSpecific?.ghostCounter
    private var display = emptyList<List<Any>>()
    private var ghostCounterV3File = File("." + File.separator + "config" + File.separator + "ChatTriggers" + File.separator + "modules" + File.separator + "GhostCounterV3" + File.separator + ".persistantData.json")
    private val skillXPPattern = "[+](?<gained>[0-9,.]+) \\((?<current>[0-9,.]+)(?:\\/(?<total>[0-9,.]+))?\\)".toPattern()
    private val combatSectionPattern = ".*[+](?<gained>[0-9,.]+) (?<skillName>[A-Za-z]+) \\((?<progress>(?:(?:(?:(?<current>[0-9.,]+)\\/(?<total>[0-9.,]+))|(?:(?<percent>[0-9.]+)%))))\\).*".toPattern()
    private val killComboExpiredPattern = "§cYour Kill Combo has expired! You reached a (?<combo>.*) Kill Combo!".toPattern()
    private val ghostXPPattern = "(?<current>\\d+(?:\\.\\d+)?(?:,\\d+)?[kK]?)\\/(?<total>\\d+(?:\\.\\d+)?(?:,\\d+)?[kKmM]?)".toPattern()
    private val bestiaryPattern = "BESTIARY Ghost .*➜(?<newLevel>.*)".toPattern()
    private val format = NumberFormat.getInstance()
    private var percent: Float = 0.0f
    private var totalSkillXp = 0
    private var currentSkillXp = 0.0f
    private var skillText = ""
    private var lastParsedSkillSection = ""
    private var lastSkillProgressString: String? = null
    private const val exportPrefix = "gc/"
    private var lastXp: String = "0"
    private var gain: Int = 0
    private var num: Double = 0.0
    private var inMist = false
    private var notifyCTModule = true
    var bestiaryCurrentKill = 0
    private var killETA = ""
    private var session = mutableMapOf(
            KILLS to 0.0,
            SORROWCOUNT to 0.0,
            VOLTACOUNT to 0.0,
            PLASMACOUNT to 0.0,
            GHOSTLYBOOTS to 0.0,
            BAGOFCASH to 0.0,
            TOTALDROPS to 0.0,
            SCAVENGERCOINS to 0.0,
            MAXKILLCOMBO to 0.0,
            SKILLXPGAINED to 0.0
    )
    val bestiaryData = mutableMapOf<Int, Int>().apply {
        val commonValue = 100_000
        for (i in 1..46) {
            this[i] = when (i) {
                1 -> 10
                2 -> 15
                3 -> 75
                4 -> 150
                5 -> 250
                6 -> 500
                7 -> 1_500
                8 -> 2_500
                9 -> 5_000
                10 -> 15_000
                11 -> 25_000
                12 -> 50_000
                else -> commonValue
            }
        }
    }

    @SubscribeEvent
    fun onRenderOverlay(event: GuiRenderEvent.GameOverlayRenderEvent) {
        if (!isEnabled()) return
        if (config.onlyOnMist && !inMist) return
        config.position.renderStringsAndItems(display,
                extraSpace = config.extraSpace,
                posLabel = "Ghost Counter")
    }

    private fun formatDisplay(map: List<List<Any>>): List<List<Any>> {
        val newList = mutableListOf<List<Any>>()
        for (index in config.ghostDisplayText) {
            newList.add(map[index])
        }
        return newList
    }

    fun update() {
        display = formatDisplay(drawDisplay())
    }

    private fun prettyTime(millis: Long): Map<String, String> {
        val seconds = millis / 1000 % 60
        val minutes = millis / 1000 / 60 % 60
        val hours = millis / 1000 / 60 / 60 % 24
        val days = millis / 1000 / 60 / 60 / 24
        return buildMap {
            if (millis < 0) {
                clear()
            } else if (minutes == 0L && hours == 0L && days == 0L) {
                put("seconds", seconds.toString())
            } else if (hours == 0L && days == 0L) {
                put("seconds", seconds.toString())
                put("minutes", minutes.toString())
            } else if (days == 0L) {
                put("seconds", seconds.toString())
                put("minutes", minutes.toString())
                put("hours", hours.toString())
            } else {
                put("seconds", seconds.toString())
                put("minutes", minutes.toString())
                put("hours", hours.toString())
                put("days", days.toString())
            }
        }
    }

    private fun drawDisplay() = buildList<List<Any>> {
        val value: Int = when (SORROWCOUNT.get()) {
            0.0 -> 0
            else -> "${((((KILLS.get() / SORROWCOUNT.get()) + Math.ulp(1.0)) * 100) / 100).roundToInt()}".toInt()
        }
        val mgc = when (TOTALDROPS.get()) {
            0.0 -> "0"
            else -> "${((((hidden?.totalMF!! / TOTALDROPS.get()) + Math.ulp(1.0)) * 100) / 100).roundToPrecision(2)}"
        }

        val xpHourFormatting = config.textFormatting.xpHourFormatting
        val xp: String
        val xpInterp: Float
        if (xpGainHourLast == xpGainHour && xpGainHour <= 0) {
            xp = xpHourFormatting.noData
        } else {
            xpInterp = interp(xpGainHour, xpGainHourLast, lastUpdate)
            val part = "([0-9]{3,}[^,]+)".toRegex().find(format.format(xpInterp))?.groupValues?.get(1) ?: "N/A"
            xp = "$part ${if (isKilling) "" else xpHourFormatting.paused}"
        }

        val killHourFormatting = config.textFormatting.killHourFormatting
        val killHour: String
        var killInterp: Long = 0
        if (killGainHourLast == killGainHour && killGainHour <= 0) {
            killHour = killHourFormatting.noData
        } else {
            killInterp = interp(killGainHour.toFloat(), killGainHourLast.toFloat(), lastKillUpdate).toLong()
            killHour = "${format.format(killInterp)} ${if (_isKilling) "" else killHourFormatting.paused}"
        }

        val bestiaryFormatting = config.textFormatting.bestiaryFormatting
        val currentKill = hidden?.bestiaryCurrentKill?.toInt() ?: 0
        val killNeeded = hidden?.bestiaryKillNeeded?.toInt() ?: 0
        val nextLevel = hidden?.bestiaryNextLevel?.toInt() ?: 0
        val bestiary = if (config.showMax) {
            when (nextLevel) {
                -1 -> bestiaryFormatting.maxed
                in 1..46 -> {
                    val sum = bestiaryData.filterKeys { it <= nextLevel - 1 }.values.sum()
                    val cKill = sum + currentKill
                    bestiaryCurrentKill = cKill
                    bestiaryFormatting.showMax_progress
                }

                else -> bestiaryFormatting.openMenu
            }
        } else {
            when (nextLevel) {
                -1 -> bestiaryFormatting.maxed
                in 1..46 -> bestiaryFormatting.progress
                else -> bestiaryFormatting.openMenu
            }
        }

        val etaFormatting = config.textFormatting.etaFormatting
        val remaining: Int = when (config.showMax) {
            true -> 3_000_000 - bestiaryCurrentKill
            false -> killNeeded - currentKill
        }

        val eta = if (remaining < 0) {
            etaFormatting.maxed
        } else {
            if (killGainHour < 1) {
                etaFormatting.noData
            } else {
                val timeMap = prettyTime(remaining.toLong() * 1000 * 60 * 60 / killInterp)
                val time = buildString {
                    if (timeMap.isNotEmpty()) {
                        val formatMap = mapOf(
                                "%days%" to "days",
                                "%hours%" to "hours",
                                "%minutes%" to "minutes",
                                "%seconds%" to "seconds"
                        )
                        for ((format, key) in formatMap) {
                            if (etaFormatting.time.contains(format)) {
                                timeMap[key]?.let { value ->
                                    append("$value${format[1]}")
                                }
                            }
                        }
                    } else {
                        append("§cEnded!")
                    }
                }
                killETA = time
                etaFormatting.progress + if (_isKilling) "" else etaFormatting.paused
            }
        }

        addAsSingletonList(Utils.chromaStringByColourCode(config.textFormatting.titleFormat.replace("&", "§")))
        addAsSingletonList(config.textFormatting.ghostKilledFormat.formatText(KILLS.getInt(), KILLS.getInt(true)))
        addAsSingletonList(config.textFormatting.sorrowsFormat.formatText(SORROWCOUNT.getInt(), SORROWCOUNT.getInt(true)))
        addAsSingletonList(config.textFormatting.ghostSinceSorrowFormat.formatText(GHOSTSINCESORROW.getInt()))
        addAsSingletonList(config.textFormatting.ghostKillPerSorrowFormat.formatText(value))
        addAsSingletonList(config.textFormatting.voltasFormat.formatText(VOLTACOUNT.getInt(), VOLTACOUNT.getInt(true)))
        addAsSingletonList(config.textFormatting.plasmasFormat.formatText(PLASMACOUNT.getInt(), PLASMACOUNT.getInt(true)))
        addAsSingletonList(config.textFormatting.ghostlyBootsFormat.formatText(GHOSTLYBOOTS.getInt(), GHOSTLYBOOTS.getInt(true)))
        addAsSingletonList(config.textFormatting.bagOfCashFormat.formatText(BAGOFCASH.getInt(), BAGOFCASH.getInt(true)))
        addAsSingletonList(config.textFormatting.avgMagicFindFormat.formatText(mgc))
        addAsSingletonList(config.textFormatting.scavengerCoinsFormat.formatText(SCAVENGERCOINS.getInt(), SCAVENGERCOINS.getInt(true)))
        addAsSingletonList(config.textFormatting.killComboFormat.formatText(KILLCOMBO.getInt(), MAXKILLCOMBO.getInt(true)))
        addAsSingletonList(config.textFormatting.highestKillComboFormat.formatText(MAXKILLCOMBO.getInt(), MAXKILLCOMBO.getInt(true)))
        addAsSingletonList(config.textFormatting.skillXPGainFormat.formatText(SKILLXPGAINED.get(), SKILLXPGAINED.get(true)))
        addAsSingletonList(bestiaryFormatting.base.formatText(bestiary).formatBestiary(currentKill, killNeeded))
        addAsSingletonList(xpHourFormatting.base.formatText(xp))
        addAsSingletonList(killHourFormatting.base.formatText(killHour))
        addAsSingletonList(etaFormatting.base.formatText(eta).formatText(killETA))

        val rate = 0.12 * (1 + (mgc.toDouble() / 100))
        val price = (BazaarApi.getBazaarDataByInternalName("SORROW")?.sellPrice ?: 0).toLong()
        val final: String = (killInterp * price * (rate / 100)).toLong().addSeparators()
        addAsSingletonList(config.textFormatting.moneyHourFormat.formatText(final))
    }

    @SubscribeEvent
    fun onTick(event: LorenzTickEvent) {
        if (!isEnabled()) return
        if (event.isMod(20)) {
            skillXPPattern.matchMatcher(skillText) {
                val gained = group("gained").formatNumber().toDouble()
                val current = group("current")
                if (current != lastXp) {
                    val res = if (current.contains(".")) {
                        "(?:[0-9,]+){3,}".toRegex().find(current)?.groupValues?.get(1) ?: "0"
                    } else {
                        current.replace("\\D".toRegex(), "")
                    }
                    gain = (res.toLong() - lastXp.toLong()).toDouble().roundToInt()
                    num = (gain.toDouble() / gained)
                    if (gained in 150.0..450.0) {
                        if (lastXp != "0") {
                            if (num >= 0) {
                                KILLS.add(num)
                                KILLS.add(num, true)
                                GHOSTSINCESORROW.add(num)
                                KILLCOMBO.add(num)
                                SKILLXPGAINED.add(gained * num.roundToLong())
                                SKILLXPGAINED.add(gained * num.roundToLong(), true)
                                hidden?.bestiaryCurrentKill = hidden?.bestiaryCurrentKill?.plus(num) ?: num
                            }
                        }
                    }
                    lastXp = res
                }
            }
            if (notifyCTModule && ProfileStorageData.profileSpecific?.ghostCounter?.ctDataImported != true) {
                notifyCTModule = false
                if (isUsingCTGhostCounter()) {
                    clickableChat("§6[SkyHanni] GhostCounterV3 ChatTriggers module has been detected, do you want to import saved data ? Click here to import data", "shimportghostcounterdata")
                }
            }
            inMist = LorenzUtils.skyBlockArea == "The Mist"
            update()
        }
        if (event.isMod(40)) {
            calculateXP()
            calculateETA()
        }
    }

    @SubscribeEvent
    fun onActionBar(event: LorenzActionBarEvent) {
        if (!isEnabled()) return
        combatSectionPattern.matchMatcher(event.message) {
            if (group("skillName").lowercase() != "combat") return
            parseCombatSection(event.message)
        }
    }

    private fun parseCombatSection(section: String) {
        val sb = StringBuilder()
        val nf = NumberFormat.getInstance(Locale.US)
        nf.maximumFractionDigits = 2
        if (lastParsedSkillSection == section) {
            sb.append(lastSkillProgressString)
        } else if (combatSectionPattern.matcher(section).find()) {
            combatSectionPattern.matchMatcher(section) {
                sb.append("+").append(group("gained"))
                val skillName = group("skillName")
                val skillPercent = group("percent") != null
                var parse = true
                if (skillPercent) {
                    percent = nf.parse(group("percent")).toFloat()
                    val level = XPInformation.getInstance().getSkillInfo(skillName).level
                    if (level > 0) {
                        totalSkillXp = SkillExperience.getExpForNextLevel(level)
                        currentSkillXp = totalSkillXp * percent / 100
                    } else {
                        parse = false
                    }
                } else {
                    currentSkillXp = nf.parse(group("current")).toFloat()
                    totalSkillXp = nf.parse(group("total")).toInt()
                }
                percent = 100f.coerceAtMost(percent)
                if (!parse) {
                    sb.append(" (").append(String.format("%.2f", percent)).append("%)")
                } else {
                    sb.append(" (").append(nf.format(currentSkillXp))
                    if (totalSkillXp != 0) {
                        sb.append("/")
                        sb.append(nf.format(totalSkillXp))
                    }
                    sb.append(")")
                }
                lastParsedSkillSection = section
                lastSkillProgressString = sb.toString()
            }
            if (sb.toString().isNotEmpty()) {
                skillText = sb.toString()
            }
        }
    }

    @SubscribeEvent
    fun onChat(event: LorenzChatEvent) {
        if (!isEnabled()) return
        if (LorenzUtils.skyBlockIsland != IslandType.DWARVEN_MINES) return
        for (opt in Option.values()) {
            val pattern = opt.pattern ?: continue
            pattern.matchMatcher(event.message) {
                when (opt) {
                    SORROWCOUNT, VOLTACOUNT, PLASMACOUNT, GHOSTLYBOOTS -> {
                        opt.add(1.0)
                        opt.add(1.0, true)
                        hidden?.totalMF = hidden?.totalMF?.plus(group("mf").substring(4).toDouble())
                                ?: group("mf").substring(4).toDouble()
                        TOTALDROPS.add(1.0)
                        if (opt == SORROWCOUNT)
                            GHOSTSINCESORROW.set(0.0)
                        update()
                    }

                    BAGOFCASH -> {
                        BAGOFCASH.add(1.0)
                        BAGOFCASH.add(1.0, true)
                        update()
                    }

                    KILLCOMBOCOINS -> {
                        KILLCOMBOCOINS.set(KILLCOMBOCOINS.get() + group("coin").toDouble())
                        update()
                    }

                    else -> {}
                }
            }
        }
        killComboExpiredPattern.matchMatcher(event.message) {
            if (KILLCOMBO.getInt() > MAXKILLCOMBO.getInt()) {
                MAXKILLCOMBO.set(group("combo").toDouble())
            }
            if (KILLCOMBO.getInt() > MAXKILLCOMBO.getInt(true)) {
                MAXKILLCOMBO.set(group("combo").toDouble(), true)
            }
            KILLCOMBOCOINS.set(0.0)
            KILLCOMBO.set(0.0)
            update()
        }
        //replace with BestiaryLevelUpEvent ?
        bestiaryPattern.matchMatcher(event.message.removeColor()) {
            val currentLevel = group("newLevel").toInt()

            when (val nextLevel = if (currentLevel >= 46) 47 else currentLevel + 1) {
                47 -> {
                    hidden?.bestiaryNextLevel = -1.0
                    hidden?.bestiaryCurrentKill = 3_000_000.0
                    hidden?.bestiaryKillNeeded = 0.0
                }

                else -> {
                    val killNeeded: Int = bestiaryData[nextLevel] ?: 0
                    hidden?.bestiaryNextLevel = nextLevel.toDouble()
                    hidden?.bestiaryCurrentKill = 0.0
                    hidden?.bestiaryKillNeeded = killNeeded.toDouble()
                }
            }
            update()
        }
    }

    @SubscribeEvent
    fun onPurseChange(event: PurseChangeEvent) {
        if (!isEnabled()) return
        if (LorenzUtils.skyBlockArea != "The Mist") return
        if (event.reason != PurseChangeCause.GAIN_MOB_KILL) return
        SCAVENGERCOINS.add(event.coins, true)
        SCAVENGERCOINS.add(event.coins)
    }

    @SubscribeEvent
    fun onInventoryOpen(event: InventoryOpenEvent) {
        if (!LorenzUtils.inSkyBlock) return
        val inventoryName = event.inventoryName
        if (inventoryName != "Bestiary ➜ Deep Caverns") return
        val stacks = event.inventoryItems
        val ghostStack = stacks[13] ?: return
        val bestiaryNextLevel = if (ghostStack.displayName == "§cGhost") 1 else Utils.parseIntOrRomanNumeral(ghostStack.displayName.substring(8)) + 1
        hidden?.bestiaryNextLevel = bestiaryNextLevel.toDouble()
        for (line in ghostStack.getLore()) {
            ghostXPPattern.matchMatcher(line.removeColor().trim()) {
                hidden?.bestiaryCurrentKill = group("current").formatNumber().toDouble()
                hidden?.bestiaryKillNeeded = group("total").formatNumber().toDouble()
            }
        }
        update()
    }

    fun isEnabled(): Boolean {
        return LorenzUtils.inSkyBlock && config.enabled && LorenzUtils.skyBlockIsland == IslandType.DWARVEN_MINES
    }

    private fun percent(number: Double): String {
        return "${((number / 3_000_000) * 100).roundToPrecision(4)}"
    }

    fun reset() {
        for (opt in Option.values()) {
            opt.set(0.0)
            opt.set(0.0, true)
        }
        hidden?.totalMF = 0.0
        update()
    }

    private fun isUsingCTGhostCounter(): Boolean {
        return ghostCounterV3File.exists() && ghostCounterV3File.isFile
    }

    fun importCTGhostCounterData() {
        val c = ProfileStorageData.profileSpecific?.ghostCounter ?: return
        if (isUsingCTGhostCounter()) {
            if (c.ctDataImported) {
                chat("§e[SkyHanni] §cYou already imported GhostCounterV3 data!")
                return
            }
            val json = ConfigManager.gson.fromJson(FileReader(ghostCounterV3File), com.google.gson.JsonObject::class.java)
            GHOSTSINCESORROW.add(json["ghostsSinceSorrow"].asDouble)
            SORROWCOUNT.add(json["sorrowCount"].asDouble)
            BAGOFCASH.add(json["BagOfCashCount"].asDouble)
            PLASMACOUNT.add(json["PlasmaCount"].asDouble)
            VOLTACOUNT.add(json["VoltaCount"].asDouble)
            GHOSTLYBOOTS.add(json["GhostlyBootsCount"].asDouble)
            KILLS.add(json["ghostsKilled"].asDouble)
            hidden?.totalMF = hidden?.totalMF?.plus(json["TotalMF"].asDouble) ?: json["TotalMF"].asDouble
            TOTALDROPS.add(json["TotalDrops"].asDouble)
            c.ctDataImported = true
            chat("§e[SkyHanni] §aImported data successfully!")
        } else
            chat("§e[SkyHanni] §cGhostCounterV3 ChatTriggers module not found!")
    }

    private fun String.formatText(value: Int, session: Int = -1): String {
        return Utils.chromaStringByColourCode(this.replace("%value%", value.addSeparators())
                .replace("%session%", session.addSeparators())
                .replace("&", "§"))
    }

    private fun String.formatText(t: String): String {
        return Utils.chromaStringByColourCode(this.replace("%value%", t)
                .replace("&", "§"))
    }

    private fun String.formatText(value: Double, session: Double): String {
        return Utils.chromaStringByColourCode(this.replace("%value%", value.roundToPrecision(2).addSeparators())
                .replace("%session%", session.roundToPrecision(2).addSeparators())
                .replace("&", "§"))
    }

    private fun String.formatBestiary(currentKill: Int, killNeeded: Int): String {
        return Utils.chromaStringByColourCode(
                this.replace("%currentKill%", if (config.showMax) bestiaryCurrentKill.addSeparators() else currentKill.addSeparators())
                        .replace("%percentNumber%", percent(bestiaryCurrentKill.toDouble()))
                        .replace("%killNeeded%", NumberUtil.format(killNeeded))
                        .replace("%currentLevel%", if (hidden?.bestiaryNextLevel?.toInt()!! < 0) "46" else "${hidden?.bestiaryNextLevel?.toInt()!! - 1}")
                        .replace("%nextLevel%", if (config.showMax) "46" else "${hidden?.bestiaryNextLevel?.toInt()!!}")
                        .replace("&", "§"))
    }

    enum class Option(val pattern: Pattern? = null) {
        KILLS,
        SORROWCOUNT("§6§lRARE DROP! §r§9Sorrow §r§b\\([+](?<mf>.*)% §r§b✯ Magic Find§r§b\\)".toPattern()),
        VOLTACOUNT("§6§lRARE DROP! §r§9Volta §r§b\\([+](?<mf>.*)% §r§b✯ Magic Find§r§b\\)".toPattern()),
        PLASMACOUNT("§6§lRARE DROP! §r§9Plasma §r§b\\([+](?<mf>.*)% §r§b✯ Magic Find§r§b\\)".toPattern()),
        GHOSTLYBOOTS("§6§lRARE DROP! §r§9Ghostly Boots §r§b\\([+](?<mf>.*)% §r§b✯ Magic Find§r§b\\)".toPattern()),
        BAGOFCASH("§eThe ghost's death materialized §r§61,000,000 coins §r§efrom the mists!".toPattern()),
        KILLCOMBOCOINS("[+]\\d+ Kill Combo [+](?<coin>.*) coins per kill".toPattern()),
        TOTALDROPS,
        GHOSTSINCESORROW,
        SCAVENGERCOINS,
        MAXKILLCOMBO,
        KILLCOMBO("[+]\\d+ Kill Combo [+](?<coin>.*) coins per kill".toPattern()),
        SKILLXPGAINED;

        fun add(i: Double, s: Boolean = false) {
            if (s)
                session[this] = session[this]?.plus(i) ?: i
            else
                hidden?.data?.set(this, hidden?.data?.get(this)?.plus(i) ?: i)
        }

        fun set(i: Double, s: Boolean = false) {
            if (s)
                session[this] = i
            else
                hidden?.data?.set(this, i)
        }

        fun getInt(s: Boolean = false): Int {
            return if (s)
                session[this]?.roundToInt() ?: 0
            else
                hidden?.data?.get(this)?.roundToInt() ?: 0
        }

        fun get(s: Boolean = false): Double {
            return if (s)
                session[this] ?: 0.0
            else
                hidden?.data?.get(this) ?: 0.0
        }
    }

    fun importFormatting() {
        val base64: String = try {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
        } catch (e: Exception) {
            return
        }

        if (base64.length <= exportPrefix.length) return
        val jsonString = try {
            val t = String(Base64.getDecoder().decode(base64.trim()))
            if (!t.startsWith(exportPrefix)) return
            t.substring(exportPrefix.length)
        } catch (e: IllegalArgumentException) {
            return
        }

        val list = try {
            JsonParser().parse(jsonString).asJsonArray
                    .filter { it.isJsonPrimitive }
                    .map { it.asString }
        } catch (e: Exception) {
            return
        }

        if (list.isNotEmpty()) {
            with(config.textFormatting) {
                titleFormat = list[0]
                ghostKilledFormat = list[1]
                sorrowsFormat = list[2]
                ghostSinceSorrowFormat = list[3]
                ghostKillPerSorrowFormat = list[4]
                voltasFormat = list[5]
                plasmasFormat = list[6]
                ghostlyBootsFormat = list[7]
                bagOfCashFormat = list[8]
                avgMagicFindFormat = list[9]
                scavengerCoinsFormat = list[10]
                killComboFormat = list[11]
                highestKillComboFormat = list[12]
                skillXPGainFormat = list[13]
                with(xpHourFormatting) {
                    base = list[14]
                    noData = list[15]
                    paused = list[16]
                }
                with(bestiaryFormatting) {
                    base = list[17]
                    openMenu = list[18]
                    maxed = list[19]
                    showMax_progress = list[20]
                    progress = list[21]
                }
                with(killHourFormatting) {
                    base = list[22]
                    noData = list[23]
                    paused = list[24]
                }
                with(etaFormatting) {
                    base = list[25]
                    maxed = list[26]
                    noData = list[27]
                    progress = list[28]
                    time = list[29]
                }
                moneyHourFormat = list[30]
            }
        }
    }

    fun exportFormatting() {
        val list = mutableListOf<String>()
        with(config.textFormatting) {
            list.add(titleFormat)
            list.add(ghostKilledFormat)
            list.add(sorrowsFormat)
            list.add(ghostSinceSorrowFormat)
            list.add(ghostKillPerSorrowFormat)
            list.add(voltasFormat)
            list.add(plasmasFormat)
            list.add(ghostlyBootsFormat)
            list.add(bagOfCashFormat)
            list.add(avgMagicFindFormat)
            list.add(scavengerCoinsFormat)
            list.add(killComboFormat)
            list.add(highestKillComboFormat)
            list.add(skillXPGainFormat)
            with(xpHourFormatting) {
                list.add(base)
                list.add(noData)
                list.add(paused)
            }
            with(bestiaryFormatting) {
                list.add(base)
                list.add(openMenu)
                list.add(maxed)
                list.add(showMax_progress)
                list.add(progress)
            }
            with(killHourFormatting) {
                list.add(base)
                list.add(noData)
                list.add(paused)
            }
            with(etaFormatting) {
                list.add(base)
                list.add(maxed)
                list.add(noData)
                list.add(progress)
                list.add(time)
            }
            list.add(moneyHourFormat)
        }
        val jsonArray = JsonArray()
        for (l in list) {
            jsonArray.add(JsonPrimitive(l))
        }
        val base64 = Base64.getEncoder().encodeToString((exportPrefix + jsonArray).toByteArray(StandardCharsets.UTF_8))
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(base64), null)
    }

    fun resetFormatting() {
        with(config.textFormatting) {
            titleFormat = "&6Ghost Counter"
            ghostKilledFormat = "  &6Ghost Killed: &b%value% &7(%session%)"
            sorrowsFormat = "  &6Sorrow: &b%value% &7(%session%)"
            ghostSinceSorrowFormat = "  &6Ghost since Sorrow: &b%value%"
            ghostKillPerSorrowFormat = "  &6Ghosts/Sorrow: &b%value%"
            voltasFormat = "  &6Volta: &b%value% &7(%session%)"
            plasmasFormat = "  &6Plasmas: &b%value% &7(%session%)"
            ghostlyBootsFormat = "  &6Ghostly Boots: &b%value% &7(%session%)"
            bagOfCashFormat = "  &6Bag Of Cash: &b%value% &7(%session%)"
            avgMagicFindFormat = "  &6Avg Magic Find: &b%value%"
            scavengerCoinsFormat = "  &6Scavenger Coins: &b%value% &7(%session%)"
            killComboFormat = "  &6Kill Combo: &b%value%"
            highestKillComboFormat = "  &6Highest Kill Combo: &b%value% &7(%session%)"
            skillXPGainFormat = "  &6Skill XP Gained: &b%value% &7(%session%)"
            with(xpHourFormatting) {
                base = "  &6XP/h: &b%value%"
                noData = "&bN/A"
                paused = "&c(PAUSED)"
            }
            with(bestiaryFormatting) {
                base = "  &6Bestiary %currentLevel%->%nextLevel%: &b%value%"
                openMenu = "§cOpen Bestiary Menu !"
                maxed = "%currentKill% (&c&lMaxed!)"
                showMax_progress = "%currentKill%/3M (%percentNumber%%)"
                progress = "%currentKill%/%killNeeded%"
            }
            with(killHourFormatting) {
                base = "  &6Kill/h: &b%value%"
                noData = "§bN/A"
                paused = "&c(PAUSED)"
            }
            with(etaFormatting) {
                base = "  &6ETA: &b%value%"
                maxed = "§c§lMAXED!"
                noData = "§bN/A"
                progress = "§b%value%"
                time = "&6%days%%hours%%minutes%%seconds%"
            }
            moneyHourFormat = "  &6$/h: &b%value%"
        }
    }
}