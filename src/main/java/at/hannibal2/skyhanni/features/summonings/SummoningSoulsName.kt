package at.hannibal2.skyhanni.features.summonings

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.utils.EntityUtils.getNameTagWith
import at.hannibal2.skyhanni.utils.EntityUtils.hasSkullTexture
import at.hannibal2.skyhanni.utils.LorenzUtils
import at.hannibal2.skyhanni.utils.LorenzUtils.sorted
import at.hannibal2.skyhanni.utils.LorenzVec
import at.hannibal2.skyhanni.utils.RenderUtils.drawString
import at.hannibal2.skyhanni.utils.getLorenzVec
import net.minecraft.client.Minecraft
import net.minecraft.entity.EntityLiving
import net.minecraft.entity.item.EntityArmorStand
import net.minecraftforge.client.event.RenderWorldLastEvent
import net.minecraftforge.event.world.WorldEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent

class SummoningSoulsName {

    private val texture =
        "ewogICJ0aW1lc3RhbXAiIDogMTYwMTQ3OTI2NjczMywKICAicHJvZmlsZUlkIiA6ICJmMzA1ZjA5NDI0NTg0ZjU" +
                "4YmEyYjY0ZjAyZDcyNDYyYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJqcm9ja2EzMyIsCiAgInNpZ25hdH" +
                "VyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgI" +
                "nVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS81YWY0MDM1ZWMwZGMx" +
                "NjkxNzc4ZDVlOTU4NDAxNzAyMjdlYjllM2UyOTQzYmVhODUzOTI5Y2U5MjNjNTk4OWFkIgogICAgfQogIH0KfQ"

    private val souls = mutableMapOf<EntityArmorStand, String>()
    private val mobsLastLocation = mutableMapOf<EntityLiving, LorenzVec>()
    private val mobsName = mutableMapOf<EntityLiving, String>()

    @SubscribeEvent
    fun onTick(event: TickEvent.ClientTickEvent) {
        if (!isEnabled()) return

        //TODO use packets instead of this
            check()
    }

    private fun check() {
        val minecraft = Minecraft.getMinecraft()
        val world = minecraft.theWorld
        for (entity in world.loadedEntityList) {
            if (souls.contains(entity)) continue

            if (entity is EntityArmorStand) {
                if (entity.hasSkullTexture(texture)) {
                    val soulLocation = entity.getLorenzVec()

                    val map = mutableMapOf<EntityLiving, Double>()
                    for ((mob, loc) in mobsLastLocation) {
                        val distance = loc.distance(soulLocation)
                        map[mob] = distance
                    }

                    val nearestMob = map.sorted().firstNotNullOfOrNull { it.key }
                    if (nearestMob != null) {
                        souls[entity] = mobsName[nearestMob]!!
                    }

                }
            }
        }

        for (entity in world.loadedEntityList) {
            if (entity is EntityLiving) {
                val consumer = entity.getNameTagWith(2, "§c❤")
                if (consumer != null) {
                    if (!consumer.name.contains("§e0")) {
                        mobsLastLocation[entity] = entity.getLorenzVec()
                        mobsName[entity] = consumer.name
                    }
                }
            }
        }

        souls.keys.removeIf { it !in world.loadedEntityList }
        //TODO fix overhead!
//        mobs.keys.removeIf { it !in world.loadedEntityList }
    }

    @SubscribeEvent
    fun onWorldRender(event: RenderWorldLastEvent) {
        if (!isEnabled()) return

        for ((entity, name) in souls) {
            val vec = entity.getLorenzVec()
            event.drawString(vec.add(0.0, 2.5, 0.0), name)
        }
    }

    @SubscribeEvent
    fun onWorldChange(event: WorldEvent.Load) {
        souls.clear()
        mobsLastLocation.clear()
        mobsName.clear()
    }

    private fun isEnabled(): Boolean {
        return LorenzUtils.inSkyBlock && SkyHanniMod.feature.summonings.summoningSoulDisplay
    }
}