package at.hannibal2.skyhanni.mixins.transformers;

import at.hannibal2.skyhanni.events.EntityEquipmentChangeEvent;
import at.hannibal2.skyhanni.mixins.hooks.NetHandlerPlayClientHookKt;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.S04PacketEntityEquipment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(value = NetHandlerPlayClient.class, priority = 1001)
public abstract class MixinNetHandlerPlayClient implements INetHandlerPlayClient {

    @Shadow
    private WorldClient clientWorldController;

    @Inject(method = "addToSendQueue", at = @At("HEAD"), cancellable = true)
    private void onSendPacket(Packet<?> packet, CallbackInfo ci) {
        NetHandlerPlayClientHookKt.onSendPacket(packet, ci);
    }

    @Inject(method = "handleEntityEquipment", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;setCurrentItemOrArmor(ILnet/minecraft/item/ItemStack;)V", shift = At.Shift.AFTER), locals = LocalCapture.CAPTURE_FAILHARD)
    public void onEntityEquipment(S04PacketEntityEquipment packetIn, CallbackInfo ci, Entity entity) {
        new EntityEquipmentChangeEvent(entity, packetIn.getEquipmentSlot(), packetIn.getItemStack()).postAndCatch();
    }

}
