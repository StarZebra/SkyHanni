package at.hannibal2.skyhanni.features.garden.farming

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.data.ClickType
import at.hannibal2.skyhanni.data.GardenCropMilestones.Companion.getCounter
import at.hannibal2.skyhanni.data.GardenCropMilestones.Companion.setCounter
import at.hannibal2.skyhanni.data.MayorElection
import at.hannibal2.skyhanni.events.CropClickEvent
import at.hannibal2.skyhanni.events.GardenToolChangeEvent
import at.hannibal2.skyhanni.events.PreProfileSwitchEvent
import at.hannibal2.skyhanni.features.garden.CropType
import at.hannibal2.skyhanni.features.garden.GardenAPI
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import kotlin.concurrent.fixedRateTimer

object GardenCropSpeed {
    private val config get() = SkyHanniMod.feature.garden
    private val cropsPerSecond: MutableMap<CropType, Int>? get() = GardenAPI.config?.cropsPerSecond
    private val latestBlocksPerSecond: MutableMap<CropType, Double>? get() = GardenAPI.config?.latestBlocksPerSecond

    var lastBrokenCrop: CropType? = null
    var lastBrokenTime = 0L

    var averageBlocksPerSecond = 0.0

    private val blocksSpeedList = mutableListOf<Int>()
    private var blocksBroken = 0
    private var secondsStopped = 0


    init {
        fixedRateTimer(name = "skyhanni-crop-milestone-speed", period = 1000L) {
            if (isEnabled()) {
                if (GardenAPI.mushroomCowPet) {
                    CropType.MUSHROOM.setCounter(CropType.MUSHROOM.getCounter() + blocksBroken)
                }
                checkSpeed()
                update()
            }
        }
    }

    @SubscribeEvent
    fun onPreProfileSwitch(event: PreProfileSwitchEvent) {
        lastBrokenCrop = null
    }

    @SubscribeEvent
    fun onGardenToolChange(event: GardenToolChangeEvent) {
        if (isEnabled()) {
            resetSpeed()
            update()
        }
    }

    private fun update() {
        GardenCropMilestoneDisplay.update()
    }

    @SubscribeEvent
    fun onBlockClick(event: CropClickEvent) {
        if (event.clickType != ClickType.LEFT_CLICK) return

        lastBrokenCrop = event.crop
        lastBrokenTime = System.currentTimeMillis()
        blocksBroken++
    }

    private fun checkSpeed() {
        val blocksBroken = blocksBroken.coerceAtMost(20)
        this.blocksBroken = 0

        if (blocksBroken == 0) {
            if (blocksSpeedList.size == 0) return
            secondsStopped++
        } else {
            if (secondsStopped >= config.blocksBrokenResetTime) {
                resetSpeed()
            }
            while (secondsStopped > 0) {
                blocksSpeedList.add(0)
                secondsStopped -= 1
            }
            blocksSpeedList.add(blocksBroken)
            if (blocksSpeedList.size == 2) {
                blocksSpeedList.removeFirst()
                blocksSpeedList.add(blocksBroken)
            }
            averageBlocksPerSecond = if (blocksSpeedList.size > 1) {
                blocksSpeedList.dropLast(1).average()
            } else 0.0
            GardenAPI.getCurrentlyFarmedCrop()?.let {
                if (averageBlocksPerSecond > 1) {
                    latestBlocksPerSecond?.put(it, averageBlocksPerSecond)
                }
            }
        }
    }

    private fun resetSpeed() {
        averageBlocksPerSecond = 0.0
        blocksSpeedList.clear()
        secondsStopped = 0
    }

    fun finneganPerkActive(): Boolean {
        val forcefullyEnabledAlwaysFinnegan = config.forcefullyEnabledAlwaysFinnegan
        val perkActive = MayorElection.isPerkActive("Finnegan", "Farming Simulator")
        return forcefullyEnabledAlwaysFinnegan || perkActive
    }

    fun isEnabled() = GardenAPI.inGarden()

    fun CropType.getSpeed() = cropsPerSecond?.get(this)

    fun CropType.setSpeed(speed: Int) {
        cropsPerSecond?.put(this, speed)
    }

    fun CropType.getLatestBlocksPerSecond() = latestBlocksPerSecond?.get(this)

    fun isSpeedDataEmpty() = cropsPerSecond?.values?.sum()?.let { it == 0 } ?: true
}